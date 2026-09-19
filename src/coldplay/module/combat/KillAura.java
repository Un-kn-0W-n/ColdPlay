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
import coldplay.util.CpsDelay;
import coldplay.util.HandProfile;
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
    public final RangeSetting rotationSpeed = add(new RangeSetting("Rotation Speed", 18.0, 22.0, 1.0, 180.0, 0.5)
            .describe("Baseline yaw speed in degrees per tick, before the per-tick hand gain, which both slows and "
                    + "flicks around it. A pace within the range is chosen per target; slider changes apply immediately."));
    public final RangeSetting pitchSpeed = add(new RangeSetting("Pitch Speed", 18.0, 22.0, 1.0, 180.0, 0.5)
            .describe("Baseline pitch speed in degrees per tick, using the same relative pace and the same hand gain "
                    + "as yaw. Slider changes apply immediately."));
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
                    + "plus a HUD graph of the broker yaw/pitch per tick and the turn rates (drag it in the HUD editor)."));
    private final NumberSetting graphScale = add(HudState.scaleSetting("Graph Scale"));

    // ---- State ----

    /** The entity we are fighting. Null means idle; see the three stop paths in the class doc. */
    private Entity target;
    private final Random random = new Random();
    private final HandProfile hand = new HandProfile(rotationSpeed, pitchSpeed);
    /** Earliest time the next attack may go out, in ms. Advanced through {@link #nextDeadline}. */
    private long nextClickAt;
    /** Last time the target was actually visible, for the wall grace below. */
    private long lastSeenAt;
    /** Mirrors of the last {@link HandProfile.Step}, used only by the broker and the debug graph. */
    private double yawRate, pitchRate;

    private HandProfile.Wander wander = HandProfile.REST;
    /** The target the hand is currently acquiring; a change here restarts the turn from rest. */
    private Entity aimTarget;

    /** How long a target stays ours after it breaks line of sight. */
    private static final long WALL_GRACE_MS = 500;
    /** Shrinks the hitbox before aiming, so the aim point never sits exactly on an edge. */
    private static final double AIM_INSET = 0.05;
    /** Fraction of the hitbox's angular size that still counts as "on target" for a rest. */
    private static final double HOLD_MARGIN = 0.9;
    private final KillAuraDebug debug = new KillAuraDebug(graphScale, rotationSpeed, pitchSpeed);

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
        rotationSpeed.label("Yaw Speed");
        invisible.label("Invisibles");
        range.label("Attack Range");
        addAutoOff();
    }

    @Override
    protected void onEnable() {
        nextClickAt = 0L;
        lastSeenAt = 0L;
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
        if (render.get()) {
            debug.sample(player, yawRate, pitchRate);
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
                target = picked;
                lastSeenAt = now;
            }
        }
    }

    private void attemptAttack(Minecraft mc, EntityPlayerSP player, long now) {
        PlayerPacketState packets = player.sendQueue.getNetworkManager().getPlayerPackets();
        PlayerPacketState.Pose pose = packets.getPose();
        if (now >= nextClickAt && clearToAttack(player, target, pose)
                && ActionGuard.getInstance().tryReserve(this)) {
            Entity victim = target;
            packets.atPose(pose, dispatched -> canDispatchAttack(mc, player, victim, pose, dispatched),
                    () -> PacketLog.getInstance().tagged("KillAura", () -> {
                player.swingItem();
                mc.playerController.attackEntity(player, victim);
            }));
            nextClickAt = nextDeadline(nextClickAt, now, CpsDelay.sample(random, cps.getLo(), cps.getHi()));
        }
    }

    private static boolean paused(Minecraft mc, EntityPlayerSP player) {
        return mc.currentScreen != null || !mc.inGameHasFocus || mc.getRenderViewEntity() != player || player.isRiding();
    }

    private boolean clearToAttack(EntityPlayerSP player, Entity victim, PlayerPacketState.Pose pose) {
        return RotationManager.getInstance().owns(this)
                && !SlotGuard.getInstance().isBusyAbove(ResourcePriority.NORMAL)
                && !player.isUsingItem()
                && rayHitsTarget(player, victim, pose);
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
        double reach = range.get() + CombatManager.getInstance().getReachBonus();
        return RayPicker.pick(player.worldObj, player, pose.eyes(player.getEyeHeight()), pose.look(),
                reach, reach, 1.0D, null, false, false, true).getEntity() == victim;
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
        RotationManager rotations = RotationManager.getInstance();
        if (aimTarget != victim) {
            hand.retarget();
            aimTarget = victim;
        }
        wander = hand.wander();
        Vec3 eyes = player.getPositionEyes(1.0F);
        AxisAlignedBB in = aimBox(victim.getEntityBoundingBox());
        Vec3 aim = aimPoint(in, eyes, wander);
        float[] want = RotationManager.angleTo(eyes.xCoord, eyes.yCoord, eyes.zCoord,
                aim.xCoord, aim.yCoord, aim.zCoord);

        float fromYaw = rotations.isActive() ? rotations.getServerYaw() : player.rotationYaw;
        float fromPitch = rotations.isActive() ? rotations.getServerPitch() : player.rotationPitch;
        boolean onTarget = onTarget(eyes, in, HandProfile.yawError(want[0], fromYaw),
                HandProfile.pitchError(want[1], fromPitch));
        HandProfile.Step step = hand.step(fromYaw, fromPitch, want[0], want[1], onTarget);
        yawRate = step.yawRate;
        pitchRate = step.pitchRate;
        rotations.request(this, step.yaw, step.pitch, ResourcePriority.NORMAL, yawRate, pitchRate);
    }

    private void resetAim() {
        aimTarget = null;
        yawRate = pitchRate = 0.0;
        wander = HandProfile.REST;
        hand.reset();
    }

    // ---- Aim geometry ----

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

    private Vec3 aimPoint(AxisAlignedBB in, Vec3 eyes, HandProfile.Wander offset) {
        Vec3 base = basePoint(in, eyes);
        return in.closestPoint(base.addVector((in.maxX - in.minX) * offset.x,
                (in.maxY - in.minY) * offset.y,
                (in.maxZ - in.minZ) * offset.z));
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
        Vec3 aim = aimPoint(aimBox(interpBox(victim, partialTicks)), eyes, wander);

        KillAuraDebug.drawTracer(eyes, player.getLook(partialTicks), aim);
    }

    private CombatManager.Filters filters() {
        return new CombatManager.Filters(players.get(), mobs.get(), animals.get(), invisible.get(), npcs.get(),
                Math.max(rotationRange.get(), range.get()), fov.get(), true, true);
    }
}
