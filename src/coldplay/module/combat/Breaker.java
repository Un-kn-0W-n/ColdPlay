package coldplay.module.combat;

import coldplay.event.EventHurt;
import coldplay.event.EventPriority;
import coldplay.event.EventRender2D;
import coldplay.event.EventRender3D;
import coldplay.event.EventTarget;
import coldplay.event.EventUpdate;
import coldplay.module.Category;
import coldplay.module.Module;
import coldplay.setting.BooleanSetting;
import coldplay.setting.ColorSetting;
import coldplay.setting.ModeSetting;
import coldplay.setting.NumberSetting;
import coldplay.util.Animation;
import coldplay.broker.ActionGuard;
import coldplay.broker.BedTracker;
import coldplay.util.BedUtil;
import coldplay.broker.DigGuard;
import coldplay.util.RenderUtil;
import coldplay.util.RayTraceUtil;
import coldplay.broker.RotationManager;
import coldplay.util.ResourcePriority;
import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;

import java.util.function.BooleanSupplier;

public final class Breaker extends Module {
    private static final String HYPIXEL = "Hypixel";
    private static final String LEGIT = "Legit";

    private final ModeSetting mode = add(new ModeSetting("Mode", HYPIXEL, HYPIXEL, LEGIT)
            .describe("Hypixel: dig through cover sightlessly (one-adjacent rule). "
                    + "Legit: dig only what a sight ray toward the bed hits, tunneling layer by layer."));
    private final NumberSetting range = add(new NumberSetting("Range", 4.5, 1.0, 6.0, 0.1)
            .describe("Max distance to the bed, in blocks."));
    private final BooleanSetting ignoreOwnBed = add(new BooleanSetting("Ignore Own Bed", true)
            .describe("Skip the bed you spawned next to."));
    private final BooleanSetting progressBar = add(new BooleanSetting("Progress Bar", true)
            .describe("HUD bar above the hotbar showing live break progress."));
    private final BooleanSetting highlight = add(new BooleanSetting("Highlight", true)
            .describe("Box on the digging block whose fill rises with break progress."));
    private final ColorSetting highlightColor = add(new ColorSetting("Highlight Color", 0x00FF64)
            .describe("Highlight box colour."));

    private static final int PRIORITY = ResourcePriority.NORMAL;
    private static final double TURN_RATE = 45.0; // deg/tick
    private static final int POST_BREAK_COOLDOWN = 2; // ticks
    private static final int HURT_PAUSE_TICKS = 3;

    private static final int BAR_WIDTH = 100;
    private static final int BAR_HEIGHT = 7;
    private static final int BAR_BOTTOM_OFFSET = 60; // px above the hotbar
    private static final int HIGHLIGHT_FILL_ALPHA = 90;

    private final BooleanSupplier killAuraWorking;
    private Aim aim; // null when idle or hurt-paused
    private Lock lock; // held until stale
    private BlockPos digging;
    private String lastMode;
    private int postBreakCooldown;
    private int hurtPauseTicks;

    private final Animation progressAnim = new Animation(0.0, 18.0);

    public Breaker(BooleanSupplier killAuraWorking) {
        super("Breaker", Category.COMBAT,
                "Breaks nearby beds. Hypixel: one cover block, then through cover. Legit: tunnels what it sees.");
        this.killAuraWorking = killAuraWorking;
        addAutoOff();
        highlightColor.visibleWhen(highlight::get).indent(1);
    }

    @Override
    protected void onDisable() {
        abandonTarget();
        postBreakCooldown = 0;
        hurtPauseTicks = 0;
        progressAnim.set(0.0);
    }

    @EventTarget
    public void onHurt(EventHurt event) {
        hurtPauseTicks = HURT_PAUSE_TICKS;
    }

    /** Runs right after KillAura (NORMAL + 2) so dig and rotation are freed before ordinary handlers. */
    @EventTarget(priority = EventPriority.NORMAL + 1)
    public void onEarlyYield(EventUpdate event) {
        if (event.isPre()) {
            yieldToKillAura();
        }
    }

    @EventTarget
    public void onUpdate(EventUpdate event) {
        if (!event.isPre()) {
            return;
        }
        if (yieldToKillAura()) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP player = mc.thePlayer;
        if (!ready(mc, player)) {
            abandonTarget();
            return;
        }

        String selectedMode = mode.get();
        if (!selectedMode.equals(lastMode)) {
            lastMode = selectedMode;
            abandonTarget();
        }
        boolean legit = LEGIT.equals(selectedMode);

        if (hurtPauseTicks > 0) {
            hurtPauseTicks--;
            aim = null;
            return;
        }

        if (postBreakCooldown > 0) {
            postBreakCooldown--;
        }

        if (digging != null && mc.theWorld.isAirBlock(digging)) {
            abandonTarget();
            postBreakCooldown = POST_BREAK_COOLDOWN;
            return;
        }

        int radius = MathHelper.ceiling_double_int(range.get()) + 1;
        BlockPos excludeOwn = ignoreOwnBed.get() ? BedTracker.getInstance().ownBedFoot() : null;
        BedUtil.Bed bed = BedUtil.findNearestBed(radius, excludeOwn);
        if (bed == null) {
            abandonTarget();
            return;
        }

        Aim picked = legit ? pickLegit(mc, player, bed) : pickHypixel(player, bed);
        if (picked == null) {
            abandonTarget();
            return;
        }
        aim = picked;

        if (postBreakCooldown > 0 || !RotationManager.getInstance().owns(this)) {
            return;
        }
        EnumFacing digFace = legit ? tracedDigFace(mc, player, picked.pos) : picked.face;
        if (digFace == null || !ActionGuard.getInstance().tryReserve(this)) {
            return;
        }
        dig(mc, player, picked.pos, digFace);
    }

    @EventTarget(priority = EventPriority.AIM)
    public void onAim(EventUpdate event) {
        if (!event.isPre()) {
            return;
        }
        if (yieldToKillAura()) {
            return;
        }

        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP player = mc.thePlayer;
        Aim aim = this.aim;
        if (player == null || aim == null || mc.currentScreen != null
                || !mc.inGameHasFocus || hurtPauseTicks > 0) {
            return;
        }
        Vec3 eyes = player.getPositionEyes(1.0F);
        Vec3 point = aim.hitVec != null ? aim.hitVec : Vec3.atFaceCenter(aim.pos, aim.face);
        float[] angles = RotationManager.angleTo(eyes.xCoord, eyes.yCoord, eyes.zCoord,
                point.xCoord, point.yCoord, point.zCoord);
        RotationManager.getInstance().request(this, angles[0], angles[1], PRIORITY, TURN_RATE);
    }

    @EventTarget
    public void onRender3D(EventRender3D event) {
        if (!highlight.get()) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        BlockPos pos = digging;
        if (!digLive(mc, pos)) {
            return;
        }
        float progress = animatedProgress(mc);

        Block block = mc.theWorld.getBlockState(pos).getBlock();
        block.setBlockBoundsBasedOnState(mc.theWorld, pos);
        AxisAlignedBB box = block.getSelectedBoundingBox(mc.theWorld, pos);
        AxisAlignedBB fill = new AxisAlignedBB(box.minX, box.minY, box.minZ,
                box.maxX, box.minY + (box.maxY - box.minY) * progress, box.maxZ);

        RenderUtil.beginWorldOverlay(2.0F);

        if (progress > 0.0F) {
            RenderUtil.drawFilledBox(fill, highlightColor.red(), highlightColor.green(),
                    highlightColor.blue(), HIGHLIGHT_FILL_ALPHA);
        }
        RenderGlobal.drawOutlinedBoundingBox(box, highlightColor.red(), highlightColor.green(),
                highlightColor.blue(), 255);

        RenderUtil.endWorldOverlay();
    }

    @EventTarget
    public void onRender2D(EventRender2D event) {
        if (!progressBar.get()) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (!digLive(mc, digging)) {
            return;
        }
        float progress = animatedProgress(mc);

        ScaledResolution sr = event.getResolution();
        int centerX = sr.getScaledWidth() / 2;
        int bottom = sr.getScaledHeight() - BAR_BOTTOM_OFFSET;
        int top = bottom - BAR_HEIGHT;
        int left = centerX - BAR_WIDTH / 2;
        int right = centerX + BAR_WIDTH / 2;

        RenderUtil.outline(left, top, right, bottom, 1, 0xFF000000);
        int fillWidth = Math.round((BAR_WIDTH - 2) * progress);
        RenderUtil.rectBounds(left + 1, top + 1, left + 1 + fillWidth, bottom - 1,
                RenderUtil.lerpRedGreen(progress));

        String label = (int) (progress * 100.0F) + "%";
        mc.fontRendererObj.drawStringWithShadow(label,
                centerX - mc.fontRendererObj.getStringWidth(label) / 2.0F, top - 10, 0xFFFFFFFF);
    }

    private static boolean ready(Minecraft mc, EntityPlayerSP player) {
        return player != null && mc.theWorld != null && mc.playerController != null
                && mc.currentScreen == null && mc.inGameHasFocus && player.getHealth() > 0.0F;
    }

    /** Hits the nearer half of an exposed bed, or holds one cover block until it goes stale. */
    private Aim pickHypixel(EntityPlayerSP player, BedUtil.Bed bed) {
        double reach = range.get();
        if (BedUtil.isBedExposed(bed)) {
            lock = null;
            double footReach = BedUtil.reachBlock(bed.foot);
            double headReach = BedUtil.reachBlock(bed.head);
            if (footReach > reach && headReach > reach) {
                return null;
            }
            BlockPos half = footReach <= headReach ? bed.foot : bed.head;
            return new Aim(half, EnumFacing.UP, null);
        }

        Minecraft mc = Minecraft.getMinecraft();
        if (lock != null && lockStale(mc, lock, bed, reach)) {
            lock = null;
        }
        if (lock == null) {
            BlockPos cover = BedUtil.pickAdjacentToBreak(bed, player.getPositionEyes(1.0F), reach);
            if (cover == null) {
                return null;
            }
            lock = new Lock(cover, bed.foot, null);
        }
        // The face is re-derived every tick because the eyes move.
        return new Aim(lock.pos, faceTowardBlock(player.getPositionEyes(1.0F), lock.pos), null);
    }

    /** Digs only what a sight ray toward the bed hits; the pick stays frozen while valid. */
    private Aim pickLegit(Minecraft mc, EntityPlayerSP player, BedUtil.Bed bed) {
        double reach = Math.min(range.get(), mc.playerController.getBlockReachDistance());
        Vec3 eyes = player.getPositionEyes(1.0F);

        if (lock != null && (lock.sight == null || lockStale(mc, lock, bed, reach)
                || !sightStillOn(mc, eyes, lock))) {
            lock = null;
        }
        if (lock == null) {
            MovingObjectPosition hit = BedUtil.firstBlockToward(bed, eyes, reach);
            if (hit == null) {
                return null;
            }
            BlockPos pos = hit.getBlockPos();
            lock = new Lock(pos, bed.foot, new Aim(pos, hit.sideHit, hit.hitVec));
        }
        return lock.sight;
    }

    private static boolean lockStale(Minecraft mc, Lock lock, BedUtil.Bed bed, double reach) {
        return !bed.foot.equals(lock.bedFoot)
                || mc.theWorld.isAirBlock(lock.pos)
                || BedUtil.reachBlock(lock.pos) > reach;
    }

    private static boolean sightStillOn(Minecraft mc, Vec3 eyes, Lock lock) {
        MovingObjectPosition hit = RayTraceUtil.trace(mc.theWorld, eyes, lock.sight.hitVec, false, false, true);
        return RayTraceUtil.matchesBlock(hit, lock.pos);
    }

    /** Faces an occluded block geometrically, by the largest eye delta. */
    private static EnumFacing faceTowardBlock(Vec3 eyes, BlockPos pos) {
        Vec3 center = Vec3.atBlockCenter(pos);
        double dx = eyes.xCoord - center.xCoord;
        double dy = eyes.yCoord - center.yCoord;
        double dz = eyes.zCoord - center.zCoord;
        double ax = Math.abs(dx), ay = Math.abs(dy), az = Math.abs(dz);
        if (ax >= ay && ax >= az) {
            return dx > 0 ? EnumFacing.EAST : EnumFacing.WEST;
        }
        if (ay >= ax && ay >= az) {
            return dy > 0 ? EnumFacing.UP : EnumFacing.DOWN;
        }
        return dz > 0 ? EnumFacing.SOUTH : EnumFacing.NORTH;
    }

    private static EnumFacing tracedDigFace(Minecraft mc, EntityPlayerSP player, BlockPos pos) {
        Vec3 eyes = player.getPositionEyes(1.0F);
        Vec3 look = RotationManager.getInstance().getSentLookVec();
        double reach = mc.playerController.getBlockReachDistance();
        MovingObjectPosition hit = RayTraceUtil.traceToLook(mc.theWorld, eyes, look, reach, false, false, true);
        return RayTraceUtil.matchesBlock(hit, pos) ? hit.sideHit : null;
    }

    private void dig(Minecraft mc, EntityPlayerSP player, BlockPos pos, EnumFacing face) {
        coldplay.broker.PacketLog.getInstance().tagged("Breaker", () -> {
            if (digging == null || !digging.equals(pos)) {
                mc.playerController.clickBlock(pos, face);
            } else {
                mc.playerController.onPlayerDamageBlock(pos, face);
            }
            player.swingItem();
        });
        digging = pos;
        DigGuard.claim();
    }

    private static boolean digLive(Minecraft mc, BlockPos pos) {
        return pos != null && mc.thePlayer != null && mc.theWorld != null
                && mc.playerController != null && mc.playerController.getIsHittingBlock()
                && !mc.theWorld.isAirBlock(pos);
    }

    private float animatedProgress(Minecraft mc) {
        float raw = MathHelper.clamp_float(mc.playerController.getCurBlockDamageMP(), 0.0F, 1.0F);
        // Snap down on regress (new block, server reset); the bar must not ease backward.
        if (raw < progressAnim.get()) {
            progressAnim.set(raw);
        }
        return (float) progressAnim.update(raw);
    }

    private boolean yieldToKillAura() {
        if (!killAuraWorking.getAsBoolean()) {
            return false;
        }
        abandonTarget();
        progressAnim.set(0.0);
        return true;
    }

    private void abandonTarget() {
        aim = null;
        lock = null;
        // Release first so our own reset can send ABORT.
        DigGuard.release();
        if (digging != null) {
            digging = null;
            Minecraft mc = Minecraft.getMinecraft();
            if (mc.playerController != null) {
                coldplay.broker.PacketLog.getInstance().tagged("Breaker", () -> { mc.playerController.resetBlockRemoving(); });
            }
        }
    }

    /** One tick's pick; hitVec is null under Hypixel. */
    private static final class Aim {
        final BlockPos pos;
        final EnumFacing face;
        final Vec3 hitVec;

        Aim(BlockPos pos, EnumFacing face, Vec3 hitVec) {
            this.pos = pos;
            this.face = face;
            this.hitVec = hitVec;
        }
    }

    /** Pick held until stale; sight is null under Hypixel. */
    private static final class Lock {
        final BlockPos pos;
        final BlockPos bedFoot;
        final Aim sight;

        Lock(BlockPos pos, BlockPos bedFoot, Aim sight) {
            this.pos = pos;
            this.bedFoot = bedFoot;
            this.sight = sight;
        }
    }
}
