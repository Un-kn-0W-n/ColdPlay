package coldplay.module.combat;

import coldplay.event.EventHurt;
import coldplay.event.EventPriority;
import coldplay.event.EventRender;
import coldplay.event.EventRender2D;
import coldplay.event.EventRender3D;
import coldplay.event.EventTarget;
import coldplay.event.EventUpdate;
import coldplay.gui.Glass;
import coldplay.gui.GlassShader;
import coldplay.gui.Theme;
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
import coldplay.util.ProjectionUtil;
import coldplay.util.RenderUtil;
import coldplay.util.RayTraceUtil;
import coldplay.broker.RotationManager;
import coldplay.util.ResourcePriority;
import coldplay.util.font.CustomFont;
import coldplay.util.font.FontRef;
import coldplay.util.font.Fonts;
import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraft.world.World;
import org.lwjgl.BufferUtils;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.function.BooleanSupplier;

public final class Breaker extends Module {
    private static final String HYPIXEL = "Hypixel";
    private static final String LEGIT = "Legit";
    private static final String PROGRESS = "Progress";
    private static final String CUSTOM = "Custom";

    private final ModeSetting mode = add(new ModeSetting("Mode", HYPIXEL, HYPIXEL, LEGIT)
            .describe("Hypixel: dig through cover sightlessly (one-adjacent rule). "
                    + "Legit: dig only what a sight ray toward the bed hits, tunneling layer by layer."));
    private final NumberSetting range = add(new NumberSetting("Range", 4.5, 1.0, 6.0, 0.1)
            .describe("Max distance to the bed, in blocks."));
    private final BooleanSetting ignoreOwnBed = add(new BooleanSetting("Ignore Own Bed", true)
            .describe("Skip the bed you spawned next to."));
    private final BooleanSetting progressBar = add(new BooleanSetting("Progress Bar", true)
            .describe("Ring around the crosshair that fills with break progress, with the block's name under it."));
    private final BooleanSetting highlight = add(new BooleanSetting("Highlight", true)
            .describe("Corner brackets on the digging block that grow until they meet as it breaks."));
    private final ModeSetting colorMode = add(new ModeSetting("Color", PROGRESS, PROGRESS, CUSTOM)
            .describe("Progress: red to green as the block breaks. Custom: the Highlight Color."));
    private final ColorSetting highlightColor = add(new ColorSetting("Highlight Color", 0x00FF64)
            .describe("Bracket and ring colour in Custom."));

    private static final int PRIORITY = ResourcePriority.NORMAL;
    private static final double TURN_RATE = 45.0; // deg/tick
    private static final int POST_BREAK_COOLDOWN = 2; // ticks
    private static final int HURT_PAUSE_TICKS = 3;

    // Sizes are GUI px.
    private static final float BRACKET_MIN = 0.16F; // of each edge at 0%, they meet in the middle at 100%
    private static final float BRACKET_GROW = 0.34F;
    private static final int FACE_TINT = 36; // alpha of the smoke on the faces you see
    private static final float LINE_W = 1.5F;
    private static final float UNDER_W = 2.7F;
    private static final int UNDER = 0x59000000;
    // stacked strokes that stand in for the design's blurred glow
    private static final float[] GLOW_W = {14.0F, 10.0F, 6.0F, 3.0F};
    private static final float[] GLOW_A = {0.08F, 0.13F, 0.2F, 0.29F};
    private static final long POP_NANOS = 300_000_000L;
    private static final float POP_GROW = 0.18F;
    private static final float POP_W = 1.2F;
    private static final long HOLD_NANOS = 250_000_000L; // bridges the cooldown between two blocks
    private static final float RING_R = 11.25F;
    private static final float RING_W = 1.5F;
    private static final float RING_UNDER_W = 2.625F;
    private static final int RING_UNDER = 0x47000000;
    private static final int RING_TRACK = 0x38FFFFFF;
    private static final float CHIP_TOP = 18.0F; // below the crosshair
    private static final float CHIP_H = 15.0F;
    private static final float CHIP_PAD = 6.0F;
    private static final float CHIP_GAP = 4.5F;
    private static final float LIFT = 3.0F; // slides this far while fading
    private static final int TEXT = 0xFFF4F6F8;
    private static final int TEXT_DIM = 0xC7F4F6F8;
    private static final Glass CHIP_GLASS = new Glass(0x800E1015, 0x800E1015, 0x2EFFFFFF, 10.5F, 1.4F, 16.5F, 6.0F, 0.26F);
    private static final FontRef NAME_FONT = new FontRef(Fonts.GEIST_MEDIUM, 7.875F);
    private static final FontRef PERCENT_FONT = new FontRef(Fonts.GEIST_MONO_MEDIUM, 7.875F);

    private final BooleanSupplier killAuraWorking;
    private Aim aim; // null when idle or hurt-paused
    private Lock lock; // held until stale
    private BlockPos digging;
    private String lastMode;
    private int postBreakCooldown;
    private int hurtPauseTicks;

    private final Animation progressAnim = new Animation(0.0, 18.0);
    private final Animation shownAnim = new Animation(0.0, 16.0);

    // What the visuals follow; the name and progress stay while they fade out.
    private BlockPos shownPos;
    private AxisAlignedBB shownBox;
    private String shownName = "";
    private float shownProgress;
    private long shownAt; // nanoTime of the last frame the dig was live
    private float shown;
    private AxisAlignedBB popBox;
    private long popAt;

    // gluProject scratch; the corner arms are framebuffer px, projected in the world pass and drawn on the HUD
    private final FloatBuffer modelview = BufferUtils.createFloatBuffer(16);
    private final FloatBuffer projection = BufferUtils.createFloatBuffer(16);
    private final IntBuffer viewport = BufferUtils.createIntBuffer(16);
    private final float[] segment = new float[4];
    private final float[] arms = new float[96];
    private final boolean[] armShown = new boolean[24];
    private final float[] popArms = new float[96];
    private final boolean[] popArmShown = new boolean[24];
    private boolean armsReady;
    private boolean popReady;

    public Breaker(BooleanSupplier killAuraWorking) {
        super("Breaker", Category.COMBAT,
                "Breaks nearby beds. Hypixel: one cover block, then through cover. Legit: tunnels what it sees.");
        this.killAuraWorking = killAuraWorking;
        addAutoOff();
        colorMode.visibleWhen(() -> highlight.get() || progressBar.get()).indent(1);
        highlightColor.visibleWhen(() -> (highlight.get() || progressBar.get()) && CUSTOM.equals(colorMode.get())).indent(1);
    }

    @Override
    protected void onDisable() {
        abandonTarget();
        postBreakCooldown = 0;
        hurtPauseTicks = 0;
        progressAnim.set(0.0);
        shownAnim.set(0.0);
        shownPos = null;
        shownAt = 0L;
        shown = 0.0F;
        popBox = null;
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

    /** Follows the dig once per frame: its box, name and eased progress, the fade, and the pop when it breaks. */
    @EventTarget
    public void onRender(EventRender event) {
        Minecraft mc = Minecraft.getMinecraft();
        long now = System.nanoTime();
        BlockPos pos = digging;
        if (digLive(mc, pos)) {
            if (!pos.equals(shownPos)) {
                shownPos = pos;
                shownName = blockName(mc.theWorld, pos);
            }
            Block block = mc.theWorld.getBlockState(pos).getBlock();
            block.setBlockBoundsBasedOnState(mc.theWorld, pos);
            shownBox = block.getSelectedBoundingBox(mc.theWorld, pos);
            shownProgress = animatedProgress(mc);
            shownAt = now;
        } else if (shownPos != null) {
            if (mc.theWorld.isAirBlock(shownPos)) {
                popBox = shownBox;
                popAt = now;
                shownProgress = 1.0F;
            }
            shownPos = null;
        }
        shown = (float) shownAnim.update(now - shownAt < HOLD_NANOS ? 1.0 : 0.0);
    }

    @EventTarget
    public void onRender3D(EventRender3D event) {
        armsReady = false;
        popReady = false;
        if (!highlight.get()) {
            return;
        }
        long age = System.nanoTime() - popAt;
        boolean pop = popBox != null && age < POP_NANOS;
        if (shownPos == null && !pop) {
            return;
        }
        RenderManager view = Minecraft.getMinecraft().getRenderManager();
        ProjectionUtil.captureMatrices(modelview, projection, viewport);
        if (shownPos != null) {
            RenderUtil.beginWorldOverlay(1.0F);
            RenderUtil.drawFilledBox(shownBox, 0x0E, 0x10, 0x15, FACE_TINT);
            RenderUtil.endWorldOverlay();
            projectArms(shownBox, BRACKET_MIN + BRACKET_GROW * shownProgress, view, arms, armShown);
            armsReady = true;
        }
        if (pop) {
            float s = POP_GROW * age / POP_NANOS / 2.0F;
            AxisAlignedBB grown = popBox.expand((popBox.maxX - popBox.minX) * s, (popBox.maxY - popBox.minY) * s,
                    (popBox.maxZ - popBox.minZ) * s);
            // brackets halfway along every edge are the whole box
            projectArms(grown, 0.5F, view, popArms, popArmShown);
            popReady = true;
        }
    }

    @EventTarget
    public void onRender2D(EventRender2D event) {
        ScaledResolution sr = event.getResolution();
        float scale = sr.getScaleFactor();
        if (popReady) {
            float fade = 1.0F - (System.nanoTime() - popAt) / (float) POP_NANOS;
            int color = accent(1.0F);
            for (int i = 0; i < GLOW_W.length; i++) {
                strokeArms(popArms, popArmShown, scale, GLOW_W[i], Theme.withAlpha(color, Math.round(255 * GLOW_A[i] * fade)));
            }
            strokeArms(popArms, popArmShown, scale, POP_W, Theme.withAlpha(0xFFFFFF, Math.round(230 * fade)));
        }
        if (armsReady) {
            int color = accent(shownProgress);
            for (int i = 0; i < GLOW_W.length; i++) {
                strokeArms(arms, armShown, scale, GLOW_W[i], Theme.withAlpha(color, Math.round(255 * GLOW_A[i])));
            }
            strokeArms(arms, armShown, scale, UNDER_W, UNDER);
            strokeArms(arms, armShown, scale, LINE_W, color);
        }
        popReady = false;
        armsReady = false;
        if (progressBar.get() && shown > 0.01F) {
            drawReticle(sr);
        }
    }

    /** A ring around the crosshair that fills clockwise, and a glass chip under it with the block and percent. */
    private void drawReticle(ScaledResolution sr) {
        Fonts.load(sr.getScaleFactor()); // lazy init, needs a live GL context
        if (!Fonts.isLoaded()) {
            return;
        }
        float a = shown;
        // the vanilla crosshair is centered half a pixel right of and below the middle
        float cx = sr.getScaledWidth() / 2 + 0.5F;
        float cy = sr.getScaledHeight() / 2 + 0.5F + (1.0F - a) * LIFT;
        float d = RING_R * 2.0F;
        GlassShader.arc(cx - RING_R, cy - RING_R, d, d, RING_R, RING_UNDER_W, 0.0F, 1.0F, Theme.applyAlpha(RING_UNDER, a));
        GlassShader.arc(cx - RING_R, cy - RING_R, d, d, RING_R, RING_W, 0.0F, 1.0F, Theme.applyAlpha(RING_TRACK, a));
        GlassShader.arc(cx - RING_R, cy - RING_R, d, d, RING_R, RING_W, 0.0F, shownProgress,
                Theme.applyAlpha(accent(shownProgress), a));

        CustomFont nameFont = NAME_FONT.get();
        CustomFont percentFont = PERCENT_FONT.get();
        String percent = (int) (shownProgress * 100.0F) + "%";
        int percentW = percentFont.getStringWidth(percent);
        float chipW = CHIP_PAD + nameFont.getStringWidth(shownName) + CHIP_GAP + percentW + CHIP_PAD;
        float chipX = cx - chipW / 2.0F;
        float chipY = cy + CHIP_TOP;
        GlassShader.capture();
        GlassShader.frost(chipX, chipY, chipW, CHIP_H, CHIP_H / 2.0F, CHIP_GLASS, a);
        float nameY = chipY + (CHIP_H - nameFont.getHeight()) / 2.0F;
        nameFont.drawString(shownName, chipX + CHIP_PAD, nameY, Theme.applyAlpha(TEXT_DIM, a));
        percentFont.drawString(percent, chipX + chipW - CHIP_PAD - percentW,
                nameY + nameFont.getAscent() - percentFont.getAscent(), Theme.applyAlpha(TEXT, a));
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        GlStateManager.enableBlend();
    }

    /** Three arms per corner, each {@code reach} of the way along its edge, as framebuffer px segments. */
    private void projectArms(AxisAlignedBB box, float reach, RenderManager view, float[] out, boolean[] shownOut) {
        for (int i = 0; i < 8; i++) {
            double x = (i & 1) == 0 ? box.minX : box.maxX;
            double y = (i & 2) == 0 ? box.minY : box.maxY;
            double z = (i & 4) == 0 ? box.minZ : box.maxZ;
            for (int k = 0; k < 3; k++) {
                double ex = k == 0 ? x + ((i & 1) == 0 ? 1 : -1) * (box.maxX - box.minX) * reach : x;
                double ey = k == 1 ? y + ((i & 2) == 0 ? 1 : -1) * (box.maxY - box.minY) * reach : y;
                double ez = k == 2 ? z + ((i & 4) == 0 ? 1 : -1) * (box.maxZ - box.minZ) * reach : z;
                int arm = i * 3 + k;
                shownOut[arm] = ProjectionUtil.projectSegment(x - view.viewerPosX, y - view.viewerPosY, z - view.viewerPosZ,
                        ex - view.viewerPosX, ey - view.viewerPosY, ez - view.viewerPosZ,
                        modelview, projection, viewport, segment);
                System.arraycopy(segment, 0, out, arm * 4, 4);
            }
        }
    }

    /** One stroke of every corner. Two arms share a joined polyline so a translucent stroke does not double up at the corner. */
    private static void strokeArms(float[] arms, boolean[] shown, float scale, float width, int color) {
        for (int i = 0; i < 8; i++) {
            int a = i * 3, b = a + 1, c = a + 2;
            if (shown[a] && shown[b] && arms[a * 4] == arms[b * 4] && arms[a * 4 + 1] == arms[b * 4 + 1]) {
                GlassShader.polyline(arms[a * 4 + 2] / scale, arms[a * 4 + 3] / scale, arms[a * 4] / scale,
                        arms[a * 4 + 1] / scale, arms[b * 4 + 2] / scale, arms[b * 4 + 3] / scale, width, color);
            } else {
                strokeArm(arms, shown, a, scale, width, color);
                strokeArm(arms, shown, b, scale, width, color);
            }
            strokeArm(arms, shown, c, scale, width, color);
        }
    }

    private static void strokeArm(float[] arms, boolean[] shown, int arm, float scale, float width, int color) {
        if (shown[arm]) {
            int o = arm * 4;
            GlassShader.line(arms[o] / scale, arms[o + 1] / scale, arms[o + 2] / scale, arms[o + 3] / scale, width, color);
        }
    }

    private int accent(float progress) {
        if (CUSTOM.equals(colorMode.get())) {
            return 0xFF000000 | highlightColor.red() << 16 | highlightColor.green() << 8 | highlightColor.blue();
        }
        return Theme.healthColor(progress);
    }

    /** The item name, so dyed wool reads "Red Wool"; blocks without an item fall back to the block name. */
    private static String blockName(World world, BlockPos pos) {
        Block block = world.getBlockState(pos).getBlock();
        Item item = block.getItem(world, pos);
        if (item == null) {
            return block.getLocalizedName();
        }
        return new ItemStack(item, 1, block.getDamageValue(world, pos)).getDisplayName();
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
