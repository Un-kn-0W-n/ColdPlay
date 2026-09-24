package coldplay.module.combat;

import coldplay.event.EventPriority;
import coldplay.event.EventRender3D;
import coldplay.event.EventTarget;
import coldplay.event.EventUpdate;
import coldplay.hud.HudState;
import coldplay.hud.KillAuraDebug;
import coldplay.module.Category;
import coldplay.module.Module;
import coldplay.setting.BooleanSetting;
import coldplay.setting.HeaderSetting;
import coldplay.setting.ModeSetting;
import coldplay.setting.NumberSetting;
import coldplay.setting.RangeSetting;
import coldplay.broker.ActionGuard;
import coldplay.broker.GameStateTracker;
import coldplay.broker.PacketLog;
import coldplay.broker.PlayerPacketState;
import coldplay.broker.SlotGuard;
import coldplay.broker.CombatManager;
import coldplay.util.AimShaper;
import coldplay.util.ClickRhythm;
import coldplay.util.CpsDelay;
import coldplay.util.HumanLimits;
import coldplay.util.RenderUtil;
import coldplay.broker.RotationManager;
import coldplay.util.ResourcePriority;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.renderer.RayPicker;
import net.minecraft.entity.Entity;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.Vec3;

import java.util.Random;

public class KillAura extends Module {

    // ---- Settings ----

    private final HeaderSetting filtersHeader = add(new HeaderSetting("Filters"));
    public final BooleanSetting players = add(new BooleanSetting("Players", true).describe("Target other players."));
    public final BooleanSetting mobs = add(new BooleanSetting("Mobs", false).describe("Target hostile mobs."));
    public final BooleanSetting animals = add(new BooleanSetting("Animals", false).describe("Target passive animals."));
    public final BooleanSetting invisible = add(new BooleanSetting("Invisible", false).describe("Also target invisible entities."));
    public final BooleanSetting npcs = add(new BooleanSetting("NPCs", false).describe("Also target NPCs (villagers / fake players)."));
    private static final String SINGLE = "Single";
    private static final String SWITCH = "Switch";
    private final HeaderSetting priorityHeader = add(new HeaderSetting("Priority"));
    public final ModeSetting priority = add(new ModeSetting("Target", CombatManager.PRIORITY_DISTANCE,
            CombatManager.PRIORITY_HEALTH, CombatManager.PRIORITY_DISTANCE)
            .describe("Which candidate to pick: Health = lowest real health, Distance = closest."));
    public final ModeSetting lock = add(new ModeSetting("Lock", SINGLE, SINGLE, SWITCH)
            .describe("Single: hold one target until it leaves range or FOV. Switch: re-pick the best target every tick."));
    private final HeaderSetting rotationsHeader = add(new HeaderSetting("Rotations"));
    public final NumberSetting rotationSpeed = add(new NumberSetting("Rotation Speed", 20.0, 1.0, 180.0, 0.5)
            .describe("Ceiling on the turn, in degrees per tick. With Humanize on this is the fastest the turn may "
                    + "go rather than the speed it holds, and it is itself capped at " + (int) HumanLimits.TURN_RATE
                    + " deg/tick however high this is set; with it off the turn simply runs at this speed. "
                    + "Slider changes apply immediately."));
    public final BooleanSetting humanize = add(new BooleanSetting("Humanize", true)
            .describe("Shape the turn like a hand and keep every number inside what a hand can do. Shaping: "
                    + "accelerate and brake, vary the speed tick to tick, carry pitch slower than yaw, wander the "
                    + "aim point around the hitbox, throw past a distant target and correct back, pause briefly "
                    + "once settled. Limits: turn no faster than " + (int) HumanLimits.TURN_RATE + " deg/tick, "
                    + "reach no further than " + HumanLimits.REACH + " blocks including any Reach bonus, click no "
                    + "faster than " + (int) HumanLimits.CPS_MAX + " CPS and never at one fixed cadence, wait a "
                    + "reaction before turning to a new target, and swing on the beat even when the ray misses "
                    + "rather than only ever landing hits. Off: none of this applies and the settings are used "
                    + "exactly as configured, which is faster and trivial to spot."));
    public final NumberSetting rotationRange = add(new NumberSetting("Rotation Range", 5.0, 1.0, 8.0, 0.1)
            .describe("Start aiming at targets within this distance (blocks); attacking still waits for Attack Range."));
    public final NumberSetting fov = add(new NumberSetting("FOV", 90.0, 10.0, 360.0, 1.0).describe("Only target within this view cone (degrees)."));
    private static final String CENTER = "Center";
    private static final String FIRST = "First";
    private final HeaderSetting combatHeader = add(new HeaderSetting("Combat"));
    public final RangeSetting cps = add(new RangeSetting("CPS", 8.0, 12.0, 1.0, 20.0, 1.0)
            .describe("Attack rate bounds (attacks per second); each attack delay is rolled between them."));
    public final NumberSetting range = add(new NumberSetting("Range", 4.0, 1.0, 6.0, 0.1).describe("Start attacking within this distance, in blocks."));
    public final ModeSetting raytrace = add(new ModeSetting("Raytrace", CENTER, CENTER, FIRST)
            .describe("Aim point on the target hitbox, before the drift wanders off it. Center: middle. "
                    + "First: point your look reaches first."));
    private final HeaderSetting debugHeader = add(new HeaderSetting("Debug"));
    public final BooleanSetting render = add(new BooleanSetting("Render", false)
            .describe("Draw the aim raytrace: a line from your eyes to the targeted spot, marked with a tenth-of-a-block box, "
                    + "plus a HUD graph comparing your camera against the broker look per tick and the turn rates "
                    + "(drag it in the HUD editor)."));
    private final NumberSetting graphScale = add(HudState.scaleSetting("Graph Scale"));

    // ---- State ----

    /** The entity we are fighting. Null means idle; see the three stop paths in the class doc. */
    private Entity target;
    private final Random random = new Random();
    /** Turn shaping. Shares {@link #random} so a seeded aura replays exactly, graph or no graph. */
    private final AimShaper shaper = new AimShaper(random);
    /** Inter-click gaps with a hand's distribution. Shares {@link #random} for seeded replay. */
    private final ClickRhythm rhythm = new ClickRhythm(random);
    /** Earliest time the next attack may go out, in ms. Advanced through {@link #nextDeadline}. */
    private long nextClickAt;
    /**
     * Earliest time the hand may act on the current target, in ms. Set whenever the target's
     * identity changes, so a newly acquired one is not already being tracked the tick it appears.
     */
    private long reactionAt;
    /** Last time the target was actually visible, for the wall grace below. */
    private long lastSeenAt;
    /** The rates handed to the broker last tick; used only by the debug graph. */
    private double turnRate, pitchRate;
    /** This tick's aim-point offset, shared by the aim and the tracer so they cannot disagree. */
    private AimShaper.Drift drift = AimShaper.REST;
    /** The target the hand is currently acquiring; a change here restarts the turn from rest. */
    private Entity aimTarget;
    /** Whether {@link #shaper} is currently primed; toggling Humanize mid-fight restarts it. */
    private boolean shaping;

    /** How long a target stays ours after it breaks line of sight. */
    private static final long WALL_GRACE_MS = 500;
    /** Shrinks the hitbox before aiming, so the aim point never sits exactly on an edge. */
    private static final double AIM_INSET = 0.05;
    /** Fraction of the hitbox's angular size that still counts as "on target" for a rest. */
    private static final double HOLD_MARGIN = 0.9;
    private final KillAuraDebug debug = new KillAuraDebug(graphScale, rotationSpeed);

    // ---- Construction and lifecycle ----

    @Override
    public String getSuffix() {
        return lock.get();
    }

    /** Registered independently so the graph remains placeable while the module is disabled. */
    public Object graph(HudState hud) {
        return debug.graph(hud, () -> isEnabled() && render.get());
    }

    public KillAura() {
        super("KillAura", Category.COMBAT,
                "Silently turns toward the nearest target: camera stays put, the look is sent to the server and shown in third person.");
        invisible.label("Invisibles");
        range.label("Attack Range");
        addAutoOff();
    }

    @Override
    protected void onEnable() {
        nextClickAt = 0L;
        lastSeenAt = 0L;
        reactionAt = 0L;
        rhythm.reset();
        resetAim();
    }

    @Override
    protected void onDisable() {
        clearTarget();
    }

    private void clearTarget() {
        target = null;
        resetAim();
        RotationManager.getInstance().cancel(this);
        CombatManager.getInstance().setNextAttackAt(0L);
    }

    public boolean isWorking() {
        return target != null;
    }

    // ---- Phase 1: decide and hit (PRE @ NORMAL+2) ----

    @EventTarget(priority = EventPriority.NORMAL + 2)
    public void onUpdate(EventUpdate event) {
        if (!event.isPre()) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP player = mc.thePlayer;
        if (player == null) {
            clearTarget();
            return;
        }
        if (mc.theWorld == null || GameStateTracker.getInstance().isCombatInactive()) {
            clearTarget();
            return;
        }
        if (target != null && target.worldObj != mc.theWorld) {
            clearTarget();
        }
        if (paused(mc, player)) {
            return;
        }
        long now = System.currentTimeMillis();
        selectTarget(player, now);
        if (target != null) {
            attemptAttack(mc, player, now);
        }
        // Velocity lands held knockback on this click
        CombatManager.getInstance().setNextAttackAt(target == null ? 0L
                : humanize.get() ? Math.max(nextClickAt, reactionAt) : nextClickAt);
    }

    private void selectTarget(EntityPlayerSP player, long now) {
        CombatManager cm = CombatManager.getInstance();
        CombatManager.Filters f = filters();
        if (target != null && cm.canSee(player, target)) {
            lastSeenAt = now;
        }
        boolean lost = target == null || !cm.isValid(player, target, f)
                || cm.angularOffset(player, target) > f.fov
                || now - lastSeenAt > WALL_GRACE_MS;
        if (SWITCH.equals(lock.get()) || lost) {
            Entity picked = cm.acquire(player, f, priority.get());
            if (picked != null || lost) {
                if (picked != target) {
                    // Nobody is already pointed at something they have not noticed yet. Without a
                    // latency here every acquisition and every Switch re-pick starts turning on the
                    // very tick the target became eligible, and a reaction time of zero is not a
                    // borderline reading - it is a value no session in any training set contains.
                    reactionAt = picked == null ? 0L : now + HumanLimits.reactionMs(random);
                }
                target = picked;
                lastSeenAt = now;
            }
        }
    }

    /** Whether the hand has finished reacting and may turn to or hit the current target. */
    private boolean reacted(long now) {
        return !humanize.get() || now >= reactionAt;
    }

    private void attemptAttack(Minecraft mc, EntityPlayerSP player, long now) {
        if (now < nextClickAt || !reacted(now)) {
            return;
        }
        PlayerPacketState packets = player.sendQueue.getNetworkManager().getPlayerPackets();
        PlayerPacketState.Pose pose = packets.getPose();
        if (!clearToSwing(player)) {
            return;
        }
        Entity victim = target;
        boolean hits = rayHitsTarget(player, victim, pose);
        // With Humanize off the click is withheld until it would land, so every swing the client
        // ever sends is a hit and the hit ratio is exactly one. Nobody plays like that; even the
        // best sessions in a training set are full of swings at air, and a ratio of 1.0 sits
        // outside the support of that data no matter how good the rotations feeding it are. On,
        // the click goes out on its own cadence and the reach ray only decides whether anything
        // is told to take damage - which is all a real click has ever done.
        if (!hits && !humanize.get()) {
            return;
        }
        if (!ActionGuard.getInstance().tryReserve(this)) {
            return;
        }
        if (hits) {
            packets.atPose(pose, dispatched -> canDispatchAttack(mc, player, victim, pose, dispatched),
                    () -> PacketLog.getInstance().tagged("KillAura", () -> {
                player.swingItem();
                mc.playerController.attackEntity(player, victim);
            }));
        } else {
            // A miss is still a click: the arm swings and nothing is told to take damage. The swing
            // is deliberately sent outside atPose, since the pose validation exists to drop an
            // attack whose ray went stale and there is no attack here to drop.
            PacketLog.getInstance().tagged("KillAura", player::swingItem);
        }
        nextClickAt = nextDeadline(nextClickAt, now, clickDelay());
    }

    /**
     * The next inter-click gap. Humanize replaces the uniform roll with a tempo that carries across
     * clicks and a skewed spread around it, because a flat interval histogram with two hard edges
     * and no autocorrelation describes no hand that has ever held a mouse.
     */
    private long clickDelay() {
        return humanize.get() ? rhythm.sample(cps.getLo(), cps.getHi())
                : CpsDelay.sample(random, cps.getLo(), cps.getHi());
    }

    private static boolean paused(Minecraft mc, EntityPlayerSP player) {
        return mc.currentScreen != null || !mc.inGameHasFocus || mc.getRenderViewEntity() != player || player.isRiding();
    }

    /** Everything a click needs that is not the reach ray, so a miss can still be swung. */
    private boolean clearToSwing(EntityPlayerSP player) {
        return RotationManager.getInstance().owns(this)
                && !SlotGuard.getInstance().isBusyAbove(ResourcePriority.NORMAL)
                && !player.isUsingItem();
    }

    private boolean clearToAttack(EntityPlayerSP player, Entity victim, PlayerPacketState.Pose pose) {
        return clearToSwing(player) && rayHitsTarget(player, victim, pose);
    }

    private boolean canDispatchAttack(Minecraft mc, EntityPlayerSP player, Entity victim,
                                      PlayerPacketState.Pose validated, PlayerPacketState.Pose dispatched) {

        if (dispatched == validated) {
            return true;
        }
        return mc.isCallingFromMinecraftThread()
                && isEnabled() && target == victim && !paused(mc, player)
                && mc.thePlayer == player && mc.theWorld == player.worldObj
                && !GameStateTracker.getInstance().isCombatInactive()
                && !victim.isDead
                && clearToAttack(player, victim, dispatched);
    }

    /** Does a ray cast from {@code pose} - the server-side look, not the camera - land on the victim? */
    private boolean rayHitsTarget(EntityPlayerSP player, Entity victim, PlayerPacketState.Pose pose) {
        if (!pose.isKnown()) {
            return false;
        }
        double reach = attackReach();
        return RayPicker.pick(player.worldObj, player, pose.eyes(player.getEyeHeight()), pose.look(),
                reach, reach, 1.0D, null, false, false, true).getEntity() == victim;
    }

    /**
     * How far the click is allowed to land. Humanize caps the total rather than the setting, so a
     * Reach bonus cannot be added on top of an already legal range to put it over: the distance
     * between two hitboxes at the moment of a hit falls straight out of the packet stream, needs no
     * behavioural model to read, and is not something a shaped rotation has any bearing on.
     */
    private double attackReach() {
        double reach = range.get() + CombatManager.getInstance().getReachBonus();
        return humanize.get() ? HumanLimits.reach(reach) : reach;
    }

    static long nextDeadline(long previous, long now, long delay) {
        return Math.max(previous, now - 50L) + delay;
    }

    // ---- Phase 2: turn (PRE @ AIM, the last word before the broker drains) ----

    @EventTarget(priority = EventPriority.AIM)
    public void onAim(EventUpdate event) {
        if (!event.isPre()) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP player = mc.thePlayer;
        if (player == null || GameStateTracker.getInstance().isCombatInactive()) {
            clearTarget();
            return;
        }
        Entity victim = target;
        if (victim == null || victim.worldObj != mc.theWorld || paused(mc, player)) {
            resetAim();
            return;
        }
        if (!reacted(System.currentTimeMillis())) {
            // Still reacting: request nothing, so the broker eases the spoof back toward the camera
            // exactly as it would if no target had been found. The turn begins from rest when the
            // window closes, because resetAim leaves aimTarget null and the hand is primed there.
            resetAim();
            return;
        }
        RotationManager rotations = RotationManager.getInstance();
        // A new target, or Humanize flipped under us, restarts the hand. Priming draws randomness,
        // so the unshaped path resets instead: with Humanize off the aim must be fully determined.
        boolean human = humanize.get();
        if (aimTarget != victim || human != shaping) {
            if (human) {
                shaper.retarget();
            } else {
                shaper.reset();
            }
            aimTarget = victim;
            shaping = human;
        }
        drift = human ? shaper.drift() : AimShaper.REST;
        Vec3 eyes = player.getPositionEyes(1.0F);
        AxisAlignedBB in = aimBox(victim.getEntityBoundingBox());
        Vec3 aim = aimPoint(in, eyes);
        float[] want = RotationManager.angleTo(eyes.xCoord, eyes.yCoord, eyes.zCoord,
                aim.xCoord, aim.yCoord, aim.zCoord);
        if (!human) {
            // Straight at the hitbox at exactly the slider, both axes the same: no shaping at all.
            turnRate = pitchRate = rotationSpeed.get();
            rotations.request(this, want[0], want[1], ResourcePriority.NORMAL, turnRate);
            return;
        }

        // Shaping measures its error from the look actually on the wire, not from the camera the
        // player is still steering with, or a silent aura would brake against the wrong distance.
        float fromYaw = rotations.isActive() ? rotations.getServerYaw() : player.rotationYaw;
        float fromPitch = rotations.isActive() ? rotations.getServerPitch() : player.rotationPitch;
        boolean onTarget = onTarget(eyes, in, AimShaper.yawError(want[0], fromYaw),
                AimShaper.pitchError(want[1], fromPitch));
        // The shaper derives its whole cruising band from this ceiling, so a non-human ceiling
        // buys a perfectly hand-shaped curve at a speed no wrist reaches. Clamp before shaping.
        AimShaper.Step step = shaper.step(fromYaw, fromPitch, want[0], want[1],
                HumanLimits.turnRate(rotationSpeed.get()), onTarget);
        turnRate = step.yawRate;
        pitchRate = step.pitchRate;
        rotations.request(this, step.yaw, step.pitch, ResourcePriority.NORMAL, turnRate, pitchRate);
    }

    private void resetAim() {
        aimTarget = null;
        shaping = false;
        turnRate = pitchRate = 0.0;
        drift = AimShaper.REST;
        shaper.reset();
    }

    // ---- Phase 3: diagnostics (PRE @ DRAIN-1, after the broker has stepped) ----

    /**
     * Sampled once the broker has produced this tick's look, so the camera series and the wire
     * series describe the same tick instead of sitting one apart. This must stay purely passive:
     * no randomness, no state the aim reads back.
     */
    @EventTarget(priority = EventPriority.DRAIN - 1)
    public void onDiagnostics(EventUpdate event) {
        if (!event.isPre() || !render.get()) {
            return;
        }
        EntityPlayerSP player = Minecraft.getMinecraft().thePlayer;
        if (player != null) {
            debug.sample(player, turnRate, pitchRate);
        }
    }

    // ---- Aim geometry ----

    /** Would freezing here still leave the ray inside the target? A hand only rests once aimed. */
    private static boolean onTarget(Vec3 eyes, AxisAlignedBB in, double yawError, double pitchError) {
        Vec3 center = in.getCenter();
        double dx = center.xCoord - eyes.xCoord;
        double dy = center.yCoord - eyes.yCoord;
        double dz = center.zCoord - eyes.zCoord;
        double flat = Math.max(Math.sqrt(dx * dx + dz * dz), 0.5);
        double reach = Math.sqrt(flat * flat + dy * dy);
        return yawError < Math.toDegrees(Math.atan2((in.maxX - in.minX) * 0.5, flat)) * HOLD_MARGIN
                && pitchError < Math.toDegrees(Math.atan2((in.maxY - in.minY) * 0.5, reach)) * HOLD_MARGIN;
    }

    private static AxisAlignedBB aimBox(AxisAlignedBB box) {
        return box.contract(AIM_INSET, AIM_INSET, AIM_INSET);
    }

    /**
     * The point on the hitbox to aim at this tick: the raytrace base, nudged by the hand's current
     * drift and pulled back inside the box. Reads {@link #drift} rather than taking it as an
     * argument so the tracer and the aim cannot drift apart within a tick.
     */
    private Vec3 aimPoint(AxisAlignedBB in, Vec3 eyes) {
        Vec3 base = basePoint(in, eyes);
        return in.closestPoint(base.addVector((in.maxX - in.minX) * drift.x,
                (in.maxY - in.minY) * drift.y,
                (in.maxZ - in.minZ) * drift.z));
    }

    private Vec3 basePoint(AxisAlignedBB in, Vec3 eyes) {
        Vec3 center = in.getCenter();
        if (FIRST.equals(raytrace.get())) {
            Vec3 look = RotationManager.getInstance().getServerLookVec();
            Vec3 toCenter = center.subtract(eyes);
            if (toCenter.dotProduct(look) <= 0.0D) {
                return in.closestPoint(eyes);
            }
            double dist = toCenter.lengthVector();
            Vec3 probe = eyes.addVector(look.xCoord * dist, look.yCoord * dist, look.zCoord * dist);
            return in.closestPoint(probe);
        }
        return center;
    }

    /** The hitbox where it is being drawn this frame, so the tracer does not lag the entity. */
    private static AxisAlignedBB interpBox(Entity victim, float partialTicks) {
        Vec3 p = RenderUtil.interpolatedPosition(victim, partialTicks);
        return victim.getEntityBoundingBox().offset(p.xCoord - victim.posX, p.yCoord - victim.posY, p.zCoord - victim.posZ);
    }

    // ---- Debug rendering ----

    @EventTarget
    public void onRender3D(EventRender3D event) {
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP player = mc.thePlayer;
        Entity victim = target;
        if (!render.get() || player == null || victim == null) {
            return;
        }
        float partialTicks = event.getPartialTicks();
        Vec3 eyes = player.getPositionEyes(partialTicks);
        Vec3 aim = aimPoint(aimBox(interpBox(victim, partialTicks)), eyes);

        KillAuraDebug.drawTracer(eyes, player.getLook(partialTicks), aim);
    }

    private CombatManager.Filters filters() {
        // Eligibility keeps the configured range: rotating toward someone still out of reach is
        // ordinary, and it is attackReach that decides what a click may actually touch. The cone is
        // capped, since engaging something behind you is awareness rather than aim and no turn
        // shaping covers for it.
        double cone = humanize.get() ? HumanLimits.fov(fov.get()) : fov.get();
        return new CombatManager.Filters(players.get(), mobs.get(), animals.get(), invisible.get(), npcs.get(),
                Math.max(rotationRange.get(), range.get()), cone, true, true);
    }
}
