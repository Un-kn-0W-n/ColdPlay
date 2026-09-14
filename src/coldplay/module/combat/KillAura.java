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
            .describe("Top yaw speed in degrees per tick. A pace within the range is chosen per target; slider changes apply immediately."));
    public final RangeSetting pitchSpeed = add(new RangeSetting("Pitch Speed", 18.0, 22.0, 1.0, 180.0, 0.5)
            .describe("Top pitch speed in degrees per tick, using the same relative pace as yaw. Slider changes apply immediately."));
    public final RangeSetting yawAccel = add(new RangeSetting("Yaw Acceleration", 30.0, 50.0, 1.0, 100.0, 1.0).unit("%")
            .describe("How much of the gap to the top yaw speed is closed each tick. 100% starts at full speed."));
    public final RangeSetting pitchAccel = add(new RangeSetting("Pitch Acceleration", 30.0, 50.0, 1.0, 100.0, 1.0).unit("%")
            .describe("How much of the gap to the top pitch speed is closed each tick. 100% starts at full speed."));
    public final RangeSetting yawDecel = add(new RangeSetting("Yaw Deceleration", 20.0, 30.0, 0.0, 100.0, 1.0).unit("%")
            .describe("How early the yaw slows in on approach. 0% never brakes."));
    public final RangeSetting pitchDecel = add(new RangeSetting("Pitch Deceleration", 20.0, 30.0, 0.0, 100.0, 1.0).unit("%")
            .describe("How early the pitch slows in on approach. 0% never brakes."));
    public final NumberSetting rotationRange = add(new NumberSetting("Rotation Range", 5.0, 1.0, 8.0, 0.1)
            .describe("Start aiming at targets within this distance (blocks); attacking still waits for Attack Range."));
    public final NumberSetting fov = add(new NumberSetting("FOV", 90.0, 10.0, 360.0, 1.0).describe("Only target within this view cone (degrees)."));
    private static final String CENTER = "Center";
    private static final String LINEAR = "Linear";
    private static final String FIRST = "First";
    private final HeaderSetting combatHeader = add(new HeaderSetting("Combat"));
    public final RangeSetting cps = add(new RangeSetting("CPS", 8.0, 12.0, 1.0, 20.0, 1.0)
            .describe("Attack rate bounds (attacks per second); each attack delay is rolled between them."));
    public final NumberSetting range = add(new NumberSetting("Range", 4.0, 1.0, 6.0, 0.1).describe("Start attacking within this distance, in blocks."));
    public final ModeSetting raytrace = add(new ModeSetting("Raytrace", CENTER, CENTER, LINEAR, FIRST)
            .describe("Aim point on the target hitbox. Center: middle. Linear: a stable interior torso point per target. "
                    + "First: point your look reaches first."));
    private final HeaderSetting debugHeader = add(new HeaderSetting("Debug"));
    public final BooleanSetting render = add(new BooleanSetting("Render", false)
            .describe("Draw the aim raytrace: a line from your eyes to the targeted spot, marked with a tenth-of-a-block box, "
                    + "plus a HUD graph of the sent yaw/pitch per tick and the turn rates (drag it in the HUD editor)."));

    private Entity target;
    private final Random random = new Random();
    private long nextClickAt;
    private long lastSeenAt;
    private double aimPace;
    private double yawRate, pitchRate;
    private double aimX = .5, aimY = .6, aimZ = .5;
    private Entity aimTarget;

    private static final long WALL_GRACE_MS = 500;
    private static final double AIM_INSET = 0.05; // blocks
    private static final double MARKER_HALF = 0.05;

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
                || holdOffset(player, target) > f.fov
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
            yawRate = pitchRate = 0.0; // a new target is turned to from rest
            // The aim point is varied once per target, in hitbox space.
            aimX = .35 + random.nextDouble() * .3;
            aimY = .45 + random.nextDouble() * .3;
            aimZ = .35 + random.nextDouble() * .3;
            aimPace = random.nextDouble();
            aimTarget = victim;
        }
        Vec3 eyes = player.getPositionEyes(1.0F);
        Vec3 aim = aimPoint(victim.getEntityBoundingBox(), eyes);
        float[] want = RotationManager.angleTo(eyes.xCoord, eyes.yCoord, eyes.zCoord,
                aim.xCoord, aim.yCoord, aim.zCoord);

        // Measure the error from the look the broker will move, not the camera it left behind.
        float fromYaw = rotations.isActive() ? rotations.getServerYaw() : player.rotationYaw;
        float fromPitch = rotations.isActive() ? rotations.getServerPitch() : player.rotationPitch;
        yawRate = ramp(yawRate, paced(rotationSpeed),
                Math.abs(MathHelper.wrapAngleTo180_double(want[0] - fromYaw)),
                paced(yawAccel) / 100.0, paced(yawDecel) / 100.0);
        pitchRate = ramp(pitchRate, paced(pitchSpeed),
                Math.abs(want[1] - fromPitch),
                paced(pitchAccel) / 100.0, paced(pitchDecel) / 100.0);
        rotations.request(this, want[0], want[1], ResourcePriority.NORMAL, yawRate, pitchRate);
    }

    private void resetAim() {
        aimTarget = null;
    }

    // Read live so a slider edit lands next tick; only the pace is rolled per target.
    private double paced(RangeSetting setting) {
        return setting.getLo() + aimPace * (setting.getHi() - setting.getLo());
    }

    // Eases the rate toward a ceiling that drops near the target, once per tick; floored at one mouse count.
    private static double ramp(double rate, double cap, double error, double accel, double decel) {
        rate = Math.min(rate, cap); // a lowered slider has to bite on this tick
        double goal = decel > 0 ? Math.min(cap, error / (decel * 10.0)) : cap; // 100% brakes ten ticks out
        double gain = goal > rate ? accel : decel;
        return Math.max(rate + (goal - rate) * gain, RotationManager.gcdStep());
    }

    static long nextDeadline(long previous, long now, long delay) {
        return Math.max(previous, now - 50L) + delay;
    }

    private float holdOffset(EntityPlayerSP player, Entity entity) {
        RotationManager rm = RotationManager.getInstance();
        CombatManager cm = CombatManager.getInstance();
        return rm.owns(this)
                ? cm.angularOffset(player, entity, rm.getServerYaw(), rm.getServerPitch())
                : cm.angularOffset(player, entity);
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
        Vec3 aim = aimPoint(interpBox(victim, partialTicks), eyes);

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
        hud.report("RotationGraph", left, top, left + panelW, top + panelH);
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

    private Vec3 aimPoint(AxisAlignedBB box, Vec3 eyes) {
        AxisAlignedBB in = box.contract(AIM_INSET, AIM_INSET, AIM_INSET);
        Vec3 center = in.getCenter();
        String mode = raytrace.get();
        if (LINEAR.equals(mode)) {
            return new Vec3(in.minX + (in.maxX - in.minX) * aimX,
                    in.minY + (in.maxY - in.minY) * aimY,
                    in.minZ + (in.maxZ - in.minZ) * aimZ);
        }
        if (FIRST.equals(mode)) {
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
