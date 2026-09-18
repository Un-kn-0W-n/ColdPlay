package coldplay.module.combat;

import coldplay.event.EventPriority;
import coldplay.event.EventRender2D;
import coldplay.event.EventRender3D;
import coldplay.event.EventTarget;
import coldplay.event.EventUpdate;
import coldplay.hud.HudState;
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
import coldplay.util.RenderUtil;
import coldplay.broker.RotationManager;
import coldplay.util.ResourcePriority;
import coldplay.util.font.CustomFont;
import coldplay.util.font.Fonts;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RayPicker;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.entity.Entity;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.MathHelper;
import net.minecraft.util.Vec3;
import org.lwjgl.opengl.GL11;

import java.util.Random;

public class KillAura extends Module {
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
                    + "plus a HUD graph of the sent yaw/pitch per tick and the turn rates (drag it in the HUD editor)."));
    private final NumberSetting graphScale = add(HudState.scaleSetting("Graph Scale"));

    private Entity target;
    private final Random random = new Random();
    private long nextClickAt;
    private long lastSeenAt;
    private double aimPace;
    private double yawBase, pitchBase; // ramp state, before the gain
    private double yawRate, pitchRate; // what the broker is handed
    private double handGain, yawGain, pitchGain;
    private double driftHand, driftX, driftY, driftZ;
    private int holdTicks, moveTicks;
    private Entity aimTarget;

    private static final long WALL_GRACE_MS = 500;
    private static final double AIM_INSET = 0.05; // blocks
    private static final double MARKER_HALF = 0.05;
    // Drift and gain decay toward rest each tick and take a gaussian kick, so the noise is
    // correlated across ticks like a hand rather than white dither.
    private static final double DRIFT_PULL = 0.18; // about five ticks of memory
    private static final double DRIFT_STEP = 0.035;
    private static final double DRIFT_CLAMP = 0.22;
    // A hand drags the aim point along a line, it does not jitter each axis on its own.
    private static final double DRIFT_SHARE = 0.75; // share^2 + solo^2 = 1 holds the old amplitude
    private static final double DRIFT_SOLO = 0.66;
    private static final double DRIFT_VERTICAL = 1.3; // pitch ends up carrying about half of yaw's travel
    // One multiplicative gain drives both axes, so a fast yaw tick is a fast pitch tick. In log
    // space, which makes it heavy tailed: a real turn is mostly small steps with the odd flick.
    private static final double GAIN_PULL = 0.35;
    private static final double GAIN_STEP = 0.90;
    private static final double GAIN_CLAMP = 1.9; // e^1.9, so about sevenfold either way
    private static final double SOLO_PULL = 0.50; // what is left after the shared part, per axis
    private static final double SOLO_STEP = 0.12;
    private static final double SOLO_CLAMP = 0.80;
    // The hand moves in bursts and rests between them. Run lengths are geometric with these means,
    // measured from the combat windows of a legit recording: about eight ticks moving, four resting.
    private static final double MOVE_CONTINUE = 0.873;
    private static final double HOLD_CONTINUE = 0.85;
    private static final int MOVE_MAX = 200;
    private static final int HOLD_MAX = 40;
    private static final double HOLD_MARGIN = 0.9; // end a rest before the frozen ray walks off the box
    // The turn curve is fixed; the gain is what makes it differ from one turn to the next.
    private static final double ACCEL = 0.40; // fraction of the gap to top speed closed each tick
    private static final double DECEL = 0.25; // starts braking about two and a half ticks out

    // Rotation graph ring buffer, one sample per tick of the wire look and ramp rates.
    private static final int GRAPH_TICKS = 200;
    private static final int GRAPH_PLOT_H = 40;
    private static final int GRAPH_PAD = 4;
    private static final int GRAPH_GAP = 2;
    private static final int COLOR_BOX = 0xF0101014;
    private static final int COLOR_BORDER = 0xFFB9B9C2;
    private static final int COLOR_AXIS = 0xFF3A3A44;
    private static final int COLOR_YAW = 0xFF5FB3FF;
    private static final int COLOR_PITCH = 0xFFFFB454;
    private static final int COLOR_YAW_RATE = 0xFF7FE38C;
    private static final int COLOR_PITCH_RATE = 0xFFE58CFF;
    private final float[] sentYaw = new float[GRAPH_TICKS];
    private final float[] sentPitch = new float[GRAPH_TICKS];
    private final float[] sentYawRate = new float[GRAPH_TICKS];
    private final float[] sentPitchRate = new float[GRAPH_TICKS];
    private int graphHead; // next write slot
    private int graphCount;
    @Override
    public String getSuffix() {
        return lock.get();
    }

    /** Registered for the whole session so the panel can be placed while KillAura is off. */
    public Object graph(HudState hud) {
        hud.registerScale("RotationGraph", graphScale);
        return new Object() {
            @EventTarget
            public void onRender2D(EventRender2D event) {
                drawGraph(event, hud);
            }
        };
    }

    public KillAura() {
        super("KillAura", Category.COMBAT,
                "Silently turns toward the nearest target: camera stays put, the look is sent to the server and shown in third person.");
        rotationSpeed.label("Yaw Speed"); // renaming the setting would orphan saved profiles
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

    @EventTarget(priority = EventPriority.NORMAL + 2)
    public void onUpdate(EventUpdate event) {
        if (!event.isPre()) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP player = mc.thePlayer;
        if (render.get() && player != null) {
            sampleGraph(player);
        }
        if (player == null || mc.theWorld == null || GameStateTracker.getInstance().isCombatInactive()) {
            clearTarget();
            return;
        }
        if (target != null && target.worldObj != mc.theWorld) {
            clearTarget();
        }
        // No rotations are requested while a screen is up, so attacking would hit blind.
        if (paused(mc, player)) {
            return;
        }
        long now = System.currentTimeMillis();
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
            // A failed Switch re-pick keeps the current target through the wall grace.
            if (picked != null || lost) {
                target = picked;
                lastSeenAt = now;
            }
        }
        if (target == null) {
            return;
        }

        PlayerPacketState packets = player.sendQueue.getNetworkManager().getPlayerPackets();
        PlayerPacketState.Pose pose = packets.getPose();
        // PRE keeps the click before this tick's movement packet.
        if (RotationManager.getInstance().owns(this) && now >= nextClickAt
                && !SlotGuard.getInstance().isBusyAbove(ResourcePriority.NORMAL)
                && !player.isUsingItem() && rayHitsTarget(player, target, pose)
                && ActionGuard.getInstance().tryReserve(this)) {
            // Vanilla sends the swing before the attack.
            Entity victim = target;
            packets.atPose(pose, dispatched -> canDispatchAttack(mc, player, victim, dispatched),
                    () -> PacketLog.getInstance().tagged("KillAura", () -> {
                player.swingItem();
                mc.playerController.attackEntity(player, victim);
            }));
            // From the old deadline, not now, so the carried remainder mixes 2- and 3-tick gaps.
            nextClickAt = nextDeadline(nextClickAt, now, CpsDelay.sample(random, cps.getLo(), cps.getHi()));
        }
    }

    private static boolean paused(Minecraft mc, EntityPlayerSP player) {
        return mc.currentScreen != null || !mc.inGameHasFocus || mc.getRenderViewEntity() != player || player.isRiding();
    }

    private boolean canDispatchAttack(Minecraft mc, EntityPlayerSP player, Entity victim, PlayerPacketState.Pose pose) {
        return mc.isCallingFromMinecraftThread()
                && isEnabled() && target == victim && mc.currentScreen == null && mc.inGameHasFocus
                && mc.thePlayer == player && mc.theWorld == player.worldObj
                && !GameStateTracker.getInstance().isCombatInactive() && RotationManager.getInstance().owns(this)
                && !victim.isDead && !player.isUsingItem()
                && !SlotGuard.getInstance().isBusyAbove(ResourcePriority.NORMAL)
                && rayHitsTarget(player, victim, pose);
    }

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
        // Same states that stop onUpdate from attacking.
        if (victim == null || victim.worldObj != mc.theWorld || paused(mc, player)) {
            resetAim();
            return;
        }
        RotationManager rotations = RotationManager.getInstance();
        if (aimTarget != victim) {
            yawBase = pitchBase = yawRate = pitchRate = 0.0; // a new target is turned to from rest
            handGain = yawGain = pitchGain = 0.0;
            driftHand = driftX = driftY = driftZ = 0.0;
            holdTicks = 0;
            moveTicks = burst(MOVE_CONTINUE, MOVE_MAX);
            aimPace = random.nextDouble();
            aimTarget = victim;
        }
        // A resting hand does not wander either, so the aim point freezes with the look.
        if (holdTicks <= 0) {
            driftHand = drift(driftHand);
            driftX = drift(driftX);
            driftY = drift(driftY);
            driftZ = drift(driftZ);
        }
        Vec3 eyes = player.getPositionEyes(1.0F);
        AxisAlignedBB in = aimBox(victim.getEntityBoundingBox());
        Vec3 aim = aimPoint(in, eyes);
        float[] want = RotationManager.angleTo(eyes.xCoord, eyes.yCoord, eyes.zCoord,
                aim.xCoord, aim.yCoord, aim.zCoord);

        // Measure the error from the look the broker will move, not the camera it left behind.
        float fromYaw = rotations.isActive() ? rotations.getServerYaw() : player.rotationYaw;
        float fromPitch = rotations.isActive() ? rotations.getServerPitch() : player.rotationPitch;
        double yawError = Math.abs(MathHelper.wrapAngleTo180_double(want[0] - fromYaw));
        double pitchError = Math.abs(want[1] - fromPitch);
        boolean onTarget = onTarget(eyes, in, yawError, pitchError);
        if (holdTicks > 0 && !onTarget) {
            holdTicks = 0; // the target walked out from under the frozen ray
        }
        yawBase = ramp(yawBase, paced(rotationSpeed), yawError, ACCEL, DECEL);
        pitchBase = ramp(pitchBase, paced(pitchSpeed), pitchError, ACCEL, DECEL);
        handGain = gain(handGain, GAIN_PULL, GAIN_STEP, GAIN_CLAMP);
        yawGain = gain(yawGain, SOLO_PULL, SOLO_STEP, SOLO_CLAMP);
        pitchGain = gain(pitchGain, SOLO_PULL, SOLO_STEP, SOLO_CLAMP);
        double hand = Math.exp(handGain);
        yawRate = geared(yawBase, hand, yawGain);
        pitchRate = geared(pitchBase, hand, pitchGain);

        if (holdTicks > 0) {
            // Re-requesting the look the broker already holds steps it by nothing, so the wire
            // look repeats exactly, the way it does while a hand is off the mouse.
            holdTicks--;
            if (holdTicks == 0) {
                moveTicks = burst(MOVE_CONTINUE, MOVE_MAX);
            }
            rotations.request(this, fromYaw, fromPitch, ResourcePriority.NORMAL, yawRate, pitchRate);
            return;
        }
        if (moveTicks > 0) {
            moveTicks--;
        }
        // A rest that is due waits for the ray to be on the target, so it never starts off it.
        if (moveTicks <= 0 && onTarget) {
            holdTicks = burst(HOLD_CONTINUE, HOLD_MAX);
        }
        rotations.request(this, want[0], want[1], ResourcePriority.NORMAL, yawRate, pitchRate);
    }

    private void resetAim() {
        aimTarget = null;
        holdTicks = 0;
    }

    /** Geometric run length: one tick, plus a coin that keeps landing heads. */
    private int burst(double keep, int cap) {
        int ticks = 1;
        while (ticks < cap && random.nextDouble() < keep) {
            ticks++;
        }
        return ticks;
    }

    /** True while the frozen look would still land on the hitbox, with a margin to end a rest early. */
    private static boolean onTarget(Vec3 eyes, AxisAlignedBB in, double yawError, double pitchError) {
        Vec3 center = in.getCenter();
        double dx = center.xCoord - eyes.xCoord;
        double dy = center.yCoord - eyes.yCoord;
        double dz = center.zCoord - eyes.zCoord;
        double flat = Math.max(Math.sqrt(dx * dx + dz * dz), 0.5);
        double reach = Math.max(Math.sqrt(flat * flat + dy * dy), 0.5);
        // The box is square in X and Z for anything that gets targeted, so either half-width will do.
        return yawError < Math.toDegrees(Math.atan2((in.maxX - in.minX) * 0.5, flat)) * HOLD_MARGIN
                && pitchError < Math.toDegrees(Math.atan2((in.maxY - in.minY) * 0.5, reach)) * HOLD_MARGIN;
    }

    // Read live so a slider edit lands next tick; only the pace is rolled per target.
    private double paced(RangeSetting setting) {
        return setting.getLo() + aimPace * (setting.getHi() - setting.getLo());
    }

    // Eases the rate toward a ceiling that drops near the target, once per tick. There is no floor:
    // a rate under one mouse count snaps to nothing and carries in the broker's remainder, which is
    // how a settled aim produces the single-count corrections and idle ticks a real one does.
    private static double ramp(double rate, double cap, double error, double accel, double decel) {
        rate = Math.min(rate, cap); // a lowered slider has to bite on this tick
        double goal = decel > 0 ? Math.min(cap, error / (decel * 10.0)) : cap; // 100% brakes ten ticks out
        double step = goal > rate ? accel : decel;
        return Math.max(rate + (goal - rate) * step, 0.0);
    }

    // One step of a mean-reverting walk. The pull forgets the past, the gaussian adds the new wander.
    private double drift(double offset) {
        return MathHelper.clamp_double(offset - DRIFT_PULL * offset + DRIFT_STEP * random.nextGaussian(),
                -DRIFT_CLAMP, DRIFT_CLAMP);
    }

    private double gain(double state, double pull, double step, double clamp) {
        return MathHelper.clamp_double(state - pull * state + step * random.nextGaussian(), -clamp, clamp);
    }

    // The shared hand gain times what is left of this axis, both multiplicative. Overshoot is not a
    // risk: the broker clamps the step to the remaining error, so a spike only closes the gap sooner.
    private static double geared(double rate, double hand, double solo) {
        return Math.max(rate * hand * Math.exp(solo), 0.0);
    }

    static long nextDeadline(long previous, long now, long delay) {
        return Math.max(previous, now - 50L) + delay;
    }

    private boolean rayHitsTarget(EntityPlayerSP player, Entity victim, PlayerPacketState.Pose pose) {
        if (!pose.isKnown()) {
            return false;
        }
        double reach = range.get() + CombatManager.getInstance().getReachBonus();
        return RayPicker.pick(player.worldObj, player, pose.eyes(player.getEyeHeight()), pose.look(),
                reach, reach, 1.0D, null, false, false, true).getEntity() == victim;
    }

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

        // Starting at the camera collapses the tracer to one pixel.
        Vec3 camLook = player.getLook(partialTicks);
        Vec3 tracerStart = eyes.add(camLook);
        RenderUtil.drawTracerMarker(tracerStart, aim, MARKER_HALF,
                255, 60, 60, 180, 90, 255, 2.0F);
    }

    private void sampleGraph(EntityPlayerSP player) {
        RotationManager rm = RotationManager.getInstance();
        boolean aiming = aimTarget != null; // idle ticks record a zero rate
        sentYaw[graphHead] = rm.isActive() ? rm.getServerYaw() : player.rotationYaw;
        sentPitch[graphHead] = rm.isActive() ? rm.getServerPitch() : player.rotationPitch;
        sentYawRate[graphHead] = aiming ? (float) yawRate : 0.0F;
        sentPitchRate[graphHead] = aiming ? (float) pitchRate : 0.0F;
        graphHead = (graphHead + 1) % GRAPH_TICKS;
        graphCount = Math.min(graphCount + 1, GRAPH_TICKS);
    }

    private void drawGraph(EventRender2D event, HudState hud) {
        if (!hud.isEditing() && !(isEnabled() && render.get())) {
            return;
        }
        ScaledResolution resolution = event.getResolution();
        Fonts.load(resolution.getScaleFactor());
        if (!Fonts.isLoaded()) {
            return;
        }
        CustomFont font = Fonts.list;
        int textH = font.getHeight();
        int panelW = GRAPH_PAD * 2 + GRAPH_TICKS;
        int panelH = GRAPH_PAD * 2 + textH * 2 + GRAPH_PLOT_H * 2 + GRAPH_GAP * 3;
        HudState.Position pos = hud.getOrCreate("RotationGraph",
                resolution.getScaledWidth() - panelW - GRAPH_PAD, GRAPH_PAD,
                resolution.getScaledWidth(), resolution.getScaledHeight());
        int left = pos.x;
        int top = pos.y;
        float s = graphScale.get().floatValue();
        hud.report("RotationGraph", left, top, left + panelW, top + panelH, left, top, s);
        RenderUtil.pushScale(left, top, s);
        RenderUtil.drawBorderedRect(left, top, left + panelW, top + panelH, COLOR_BOX, COLOR_BORDER);

        int x0 = left + GRAPH_PAD;
        int legendY = top + GRAPH_PAD;
        int deltaTop = legendY + textH + GRAPH_GAP;
        int rateTop = deltaTop + GRAPH_PLOT_H + GRAPH_GAP;
        int valuesY = rateTop + GRAPH_PLOT_H + GRAPH_GAP;
        RenderUtil.hLine(x0, x0 + GRAPH_TICKS, deltaTop + GRAPH_PLOT_H / 2, COLOR_AXIS); // zero line
        RenderUtil.hLine(x0, x0 + GRAPH_TICKS, rateTop + GRAPH_PLOT_H - 1, COLOR_AXIS);

        double m = Math.max(rotationSpeed.getHi(), pitchSpeed.getHi()); // live sliders set the scale
        GlStateManager.enableBlend();
        GlStateManager.disableTexture2D();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        plot(sentYaw, true, true, x0, deltaTop, m, COLOR_YAW);
        plot(sentPitch, true, false, x0, deltaTop, m, COLOR_PITCH);
        plot(sentYawRate, false, false, x0, rateTop, m, COLOR_YAW_RATE);
        plot(sentPitchRate, false, false, x0, rateTop, m, COLOR_PITCH_RATE);
        GlStateManager.enableTexture2D();

        int lx = label(font, "yaw", x0, legendY, COLOR_YAW);
        lx = label(font, "pitch", lx, legendY, COLOR_PITCH);
        lx = label(font, "yaw rate", lx, legendY, COLOR_YAW_RATE);
        label(font, "pitch rate", lx, legendY, COLOR_PITCH_RATE);

        int last = (graphHead + GRAPH_TICKS - 1) % GRAPH_TICKS;
        int prev = (last + GRAPH_TICKS - 1) % GRAPH_TICKS;
        double dYaw = graphCount < 2 ? 0.0 : MathHelper.wrapAngleTo180_double(sentYaw[last] - sentYaw[prev]);
        double dPitch = graphCount < 2 ? 0.0 : sentPitch[last] - sentPitch[prev];
        String deltas = String.format("yaw %+.1f  pitch %+.1f", dYaw, dPitch);
        String rates = String.format("rate %.1f / %.1f", sentYawRate[last], sentPitchRate[last]);
        font.drawStringWithShadow(deltas, x0, valuesY, 0xFFFFFFFF);
        font.drawStringWithShadow(rates, x0 + GRAPH_TICKS - font.getStringWidth(rates), valuesY, 0xFFFFFFFF);
        GlStateManager.popMatrix();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        GlStateManager.enableBlend();
    }

    /** One line strip, newest tick at the right; {@code delta} plots the per-tick change centred on zero. */
    private void plot(float[] series, boolean delta, boolean wrap, int x0, int top, double m, int color) {
        if (graphCount < 2) {
            return;
        }
        int r = color >> 16 & 0xFF, g = color >> 8 & 0xFF, b = color & 0xFF;
        Tessellator tessellator = Tessellator.getInstance();
        WorldRenderer wr = tessellator.getWorldRenderer();
        wr.begin(GL11.GL_LINE_STRIP, DefaultVertexFormats.POSITION_COLOR);
        int oldest = (graphHead - graphCount + GRAPH_TICKS) % GRAPH_TICKS;
        int x = x0 + GRAPH_TICKS - graphCount;
        for (int i = 0; i < graphCount; i++, x++) {
            int at = (oldest + i) % GRAPH_TICKS;
            double y;
            if (delta) {
                double step = i == 0 ? 0.0 : series[at] - series[(at + GRAPH_TICKS - 1) % GRAPH_TICKS];
                if (wrap) {
                    step = MathHelper.wrapAngleTo180_double(step);
                }
                y = top + GRAPH_PLOT_H / 2.0 - MathHelper.clamp_double(step, -m, m) / m * (GRAPH_PLOT_H / 2.0);
            } else {
                y = top + GRAPH_PLOT_H - 1 - MathHelper.clamp_double(series[at], 0.0, m) / m * (GRAPH_PLOT_H - 1);
            }
            wr.pos(x + 0.5, y, 0.0).color(r, g, b, 255).endVertex();
        }
        tessellator.draw();
    }

    private static int label(CustomFont font, String text, int x, int y, int color) {
        font.drawStringWithShadow(text, x, y, color);
        return x + font.getStringWidth(text) + 6;
    }

    private static AxisAlignedBB interpBox(Entity victim, float partialTicks) {
        Vec3 p = RenderUtil.interpolatedPosition(victim, partialTicks);
        return victim.getEntityBoundingBox().offset(p.xCoord - victim.posX, p.yCoord - victim.posY, p.zCoord - victim.posZ);
    }

    private static AxisAlignedBB aimBox(AxisAlignedBB box) {
        return box.contract(AIM_INSET, AIM_INSET, AIM_INSET);
    }

    private Vec3 aimPoint(AxisAlignedBB in, Vec3 eyes) {
        Vec3 base = basePoint(in, eyes);
        double wanderX = driftX * DRIFT_SOLO + driftHand * DRIFT_SHARE;
        double wanderY = driftY * DRIFT_SOLO + driftHand * DRIFT_SHARE;
        double wanderZ = driftZ * DRIFT_SOLO + driftHand * DRIFT_SHARE;
        // Wandering inside the hitbox keeps the ray on the target, so the drift never costs a hit.
        return in.closestPoint(base.addVector((in.maxX - in.minX) * wanderX,
                (in.maxY - in.minY) * DRIFT_VERTICAL * wanderY,
                (in.maxZ - in.minZ) * wanderZ));
    }

    private Vec3 basePoint(AxisAlignedBB in, Vec3 eyes) {
        Vec3 center = in.getCenter();
        if (FIRST.equals(raytrace.get())) {
            Vec3 look = RotationManager.getInstance().getServerLookVec();
            Vec3 toCenter = center.subtract(eyes);
            // Looking away from the box, a probe along the look would pick the far face.
            if (toCenter.dotProduct(look) <= 0.0D) {
                return in.closestPoint(eyes);
            }
            double dist = toCenter.lengthVector();
            Vec3 probe = eyes.addVector(look.xCoord * dist, look.yCoord * dist, look.zCoord * dist);
            return in.closestPoint(probe);
        }
        return center;
    }

    private CombatManager.Filters filters() {
        return new CombatManager.Filters(players.get(), mobs.get(), animals.get(), invisible.get(), npcs.get(),
                Math.max(rotationRange.get(), range.get()), fov.get(), true, true);
    }
}
