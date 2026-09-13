package coldplay.module.movement;

import coldplay.broker.ActionGuard;
import coldplay.broker.PacketLog;
import coldplay.broker.RotationManager;
import coldplay.broker.SlotGuard;
import coldplay.broker.SprintGuard;
import coldplay.event.EventMotion;
import coldplay.event.EventPriority;
import coldplay.event.EventRender;
import coldplay.event.EventRender3D;
import coldplay.event.EventStrafe;
import coldplay.event.EventTarget;
import coldplay.event.EventUpdate;
import coldplay.module.Category;
import coldplay.module.Module;
import coldplay.setting.BooleanSetting;
import coldplay.setting.HeaderSetting;
import coldplay.setting.ModeSetting;
import coldplay.setting.NumberSetting;
import coldplay.util.EdgeUtil;
import coldplay.util.InvUtil;
import coldplay.util.PlacementUtil;
import coldplay.util.PlacementUtil.Placement;
import coldplay.util.PlayerUtil;
import coldplay.util.RenderUtil;
import coldplay.util.ResourcePriority;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.RotationMath;
import net.minecraft.util.Vec3;

import java.util.LinkedHashSet;
import java.util.Random;
import java.util.Set;

/**
 * Hypixel and Polar walk-bridge behind a spoofed backward look, Telly sprint-jump bridges. Each tick
 * picks a cell, aims after physics and fires once the sent look lands on its face.
 */
public class Scaffold extends Module {

    private static final String HYPIXEL = "Hypixel";
    private static final String POLAR = "Polar";
    private static final String TELLY = "Telly";


    public final ModeSetting mode = add(new ModeSetting("Mode", HYPIXEL, HYPIXEL, POLAR, TELLY)
            .describe("Hypixel: walk-bridge, pitch aimed onto each face. Polar: walk-bridge holding "
                    + "yaw - 180 and a locked 78.5 pitch until you disable it. Telly: sprint-jump "
                    + "bridge, auto-jumps facing forward, spins back mid-air and places only when the "
                    + "sent look raytraces onto the support."));

    private final HeaderSetting placementHeader = add(new HeaderSetting("Placement"));
    private final BooleanSetting autoSwitch = add(new BooleanSetting("AutoSwitch", true)
            .describe("Silently switch to a block in your hotbar while scaffolding."));
    private final BooleanSetting keepY = add(new BooleanSetting("KeepY", true)
            .describe("On: lock the bridge plane to the Y you last stood on, never builds upward. "
                    + "Off: bridges flat too, but holding the jump key raises the plane with you "
                    + "(+1 per jump, tower or incline on demand)."));
    private final NumberSetting expand = add(new NumberSetting("Expand", 1.0, 1.0, 6.0, 1.0)
            .describe("Cells ahead along your travel to try after the one under you, nearest first."));
    private final BooleanSetting safety = add(new BooleanSetting("Safety", true)
            .describe("Hard no-fall: auto-sneak at the leading edge while the covering block isn't placed "
                    + "yet. Vanilla's sneak edge-clamp then makes walking off the bridge impossible."));

    private final HeaderSetting debugHeader = add(new HeaderSetting("Debug"));
    private final BooleanSetting render = add(new BooleanSetting("Render", false)
            .describe("Draw the placement raytrace: a line from your eyes to the queued scaffold hit point, "
                    + "marked with a tenth-of-a-block box."));


    private static final double TURN_RATE = 180.0D; // deg/tick
    private static final float FALLBACK_PITCH = 85.0F;
    private static final int PRIORITY = ResourcePriority.NORMAL;
    private static final double MARKER_HALF = 0.05D;

    private static final float AIM_YAW_NOISE_BOUND = 1.5F; // degrees either side of the hold
    private static final double CLAMP_JITTER = 0.08D; // max inset from a face-band bound, blocks

    private static final int BACKFILL = 3; // Telly: cells covered behind the player each tick
    static final float AIM_CLAMP = 22.0F; // degrees, inside MoveFix's 22.5 bucket

    private static final float POLAR_PITCH = 78.5F;
    private static final float POLAR_STRAIGHT_CLAMP = 45.0F; // degrees, a straight walk needs more than AIM_CLAMP
    private static final float POLAR_AXIS_TOLERANCE = 22.5F; // degrees off a world axis
    private static final int POLAR_NOISE_COUNTS = 3; // mouse counts either side of the hold


    private final Random rand = new Random();
    private final RotationManager.SentLook sent = new RotationManager.SentLook();
    private Placement pending;
    private int planeY = Integer.MIN_VALUE; // MIN_VALUE until seeded
    private BlockPos ground; // cell last stood on
    private float yawNoise; // degrees off the mode's hold
    private float heldPitch; // last aimed pitch
    private long insetSalt;

    private float baseYaw; // Telly: movement yaw at the last grounded tick

    public Scaffold() {
        super("Scaffold", Category.MOVEMENT,
                "Bridges beneath you: Hypixel and Polar walk-bridge behind a spoofed backward look, "
                        + "Telly sprint-jump bridges.");
        addAutoOff();
    }

    @Override
    protected void onEnable() {
        planeY = Integer.MIN_VALUE;
        heldPitch = FALLBACK_PITCH;
        insetSalt = rand.nextLong();
        sent.reset();
        ground = null;
    }

    @Override
    protected void onDisable() {
        pending = null;
        SlotGuard.getInstance().release(this);
    }


    private boolean telly() {
        return TELLY.equals(mode.get());
    }

    private boolean polar() {
        return POLAR.equals(mode.get());
    }

    private static float backward(float yaw) {
        return MathHelper.wrapAngleTo180_float(yaw + 180.0F);
    }

    private float polarYaw(float move, Vec3 eyes, Placement p) {
        float hold = backward(move);
        float bent = p == null || !straightWalk(move) ? hold : aimYaw(hold, eyes, p, POLAR_STRAIGHT_CLAMP);
        // Noise after the bend, or the bend would cancel it back out.
        return MathHelper.wrapAngleTo180_float(bent + yawNoise);
    }

    static boolean straightWalk(float moveYaw) {
        float axisYaw = EnumFacing.fromAngle(moveYaw).getHorizontalIndex() * 90.0F;
        return Math.abs(MathHelper.wrapAngleTo180_float(moveYaw - axisYaw)) <= POLAR_AXIS_TOLERANCE;
    }

    /** Whether the locked-pitch ray along {@code yaw} enters the clicked face. */
    static boolean polarReaches(Vec3 eyes, Placement p, float yaw) {
        return PlacementUtil.faceHit(eyes, RotationMath.lookVector(POLAR_PITCH, yaw), p) != null;
    }

    private float holdYaw() {
        return backward(baseYaw);
    }

    /** The hold bent toward the queued hit by at most {@code clamp} degrees. */
    static float aimYaw(float hold, Vec3 eyes, Placement p, float clamp) {
        float bend = MathHelper.wrapAngleTo180_float(
                RotationMath.yawTo(eyes.xCoord, eyes.zCoord, p.hitVec.xCoord, p.hitVec.zCoord) - hold);
        return MathHelper.wrapAngleTo180_float(hold + MathHelper.clamp_float(bend, -clamp, clamp));
    }

    /** The yaw request. onMotionAim aims the pitch, except Polar's, which is locked. */
    @EventTarget
    public void onRender(EventRender event) {
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP player = mc.thePlayer;
        if (player == null || mc.theWorld == null) {
            return;
        }
        RotationManager rm = RotationManager.getInstance();
        float pitch = rm.isActive() ? rm.getServerPitch() : player.rotationPitch;
        float move = PlayerUtil.movementYaw(mc, player);
        Vec3 eyes = player.getPositionEyes(1.0F);
        // The yaw noise rides these frames. wireLookReady needs the look to change every tick.
        if (polar()) {
            rm.request(this, polarYaw(move, eyes, pending), POLAR_PITCH, PRIORITY, TURN_RATE);
        } else if (!telly()) {
            rm.request(this, MathHelper.wrapAngleTo180_float(backward(move) + yawNoise), pitch, PRIORITY, TURN_RATE);
        } else if (player.onGround) {
            rm.request(this, move, pitch, PRIORITY, TURN_RATE);
        } else {
            Placement p = pending;
            rm.request(this, p == null ? holdYaw() : aimYaw(holdYaw(), eyes, p, AIM_CLAMP), pitch, PRIORITY, TURN_RATE);
        }
    }

    /** Aims the pitch onto the queued face from the position this tick's packet carries. */
    @EventTarget(priority = EventPriority.NORMAL)
    public void onMotionAim(EventMotion event) {
        RotationManager rm = RotationManager.getInstance();
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP player = mc.thePlayer;
        // A reserved tick means a place just went out, and the next packet must repeat that look.
        if (!event.isPre() || player == null || !rm.owns(this) || ActionGuard.getInstance().isReserved()) {
            return;
        }
        Vec3 eyes = player.getPositionEyes(1.0F);
        if (polar()) {
            rm.reaim(this, polarYaw(PlayerUtil.movementYaw(mc, player), eyes, pending), POLAR_PITCH, TURN_RATE);
            return;
        }
        float pitch = pending == null ? Float.NaN : aimPitch(eyes, pending, rm.getServerYaw());
        if (!Float.isNaN(pitch)) {
            heldPitch = pitch;
        } else {
            // Telly is still spinning. Walking keeps the last aim so the pitch does not jump at every edge.
            pitch = telly() ? rm.getServerPitch() : heldPitch;
        }
        rm.reaim(this, rm.getServerYaw(), pitch, TURN_RATE);
    }

    @EventTarget
    public void onRender3D(EventRender3D event) {
        Minecraft mc = Minecraft.getMinecraft();
        Placement placement = pending;
        if (!render.get() || mc.thePlayer == null || mc.theWorld == null || placement == null) {
            return;
        }
        RenderUtil.drawTracerMarker(mc.thePlayer.getPositionEyes(event.getPartialTicks()), placement.hitVec,
                MARKER_HALF, 255, 60, 60, 180, 90, 255, 2.0F);
    }


    @EventTarget
    public void onStrafe(EventStrafe event) {
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP player = mc.thePlayer;
        if (player == null) {
            return;
        }
        boolean jumping = telly() && autoJump(event, mc, player);
        // Sprinting while placing is its own flag.
        if (!telly()) {
            SprintGuard.getInstance().suppress();
        }
        // Not on the jump tick: the sneak slowdown would drop forward under vanilla's sprint threshold.
        if (!jumping && safety.get() && EdgeUtil.isApproachingEdge(mc, player, EdgeUtil.MIN_PROBE)) {
            event.applyForcedSneakSlowdown();
        }
    }

    private boolean autoJump(EventStrafe event, Minecraft mc, EntityPlayerSP player) {
        ItemStack held = player.getHeldItem();
        if (!player.onGround || !PlayerUtil.anyMoveKeyDown(mc)
                || held == null || !(held.getItem() instanceof ItemBlock)) {
            return false;
        }
        if (PlayerUtil.canVanillaSprint(player, event.getForward())) {
            player.setSprinting(true);
        }
        player.movementInput.jump = true;
        return true;
    }


    @EventTarget
    public void onUpdate(EventUpdate event) {
        if (!event.isPre()) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP player = mc.thePlayer;
        WorldClient world = mc.theWorld;
        if (player == null || world == null) {
            return;
        }
        if (player.onGround) {
            baseYaw = PlayerUtil.movementYaw(mc, player);
            ground = new BlockPos(player).down();
        }
        // A vanilla client cannot turn, switch or place with a screen open.
        if (mc.currentScreen != null) {
            pending = null;
            return;
        }
        if (autoSwitch.get()) {
            equipBlock(player);
        }
        ItemStack held = player.getHeldItem();
        if (held == null || !(held.getItem() instanceof ItemBlock)) {
            pending = null;
            return;
        }
        // Fire the cell onMotionAim aimed at before the re-search can replace it.
        boolean fired = pending != null && firePending(mc, player, world, held);
        pending = findPlacement(mc, player, world);
        // The edge window may close before the next tick, so try the new cell now too.
        if (!fired && pending != null && firePending(mc, player, world, held)) {
            pending = null;
        }
    }

    private void rollLookNoise() {
        float gcd = RotationManager.gcdStep();
        yawNoise = stepNoise(rand, yawNoise, gcd, polar() ? POLAR_NOISE_COUNTS * gcd : AIM_YAW_NOISE_BOUND);
    }

    /** One mouse count either way, turned back at the bound. Never a zero step. */
    static float stepNoise(Random rand, float value, float gcd, float bound) {
        float step = rand.nextBoolean() ? gcd : -gcd;
        return Math.abs(value + step) > bound ? value - step : value + step;
    }

    private boolean firePending(Minecraft mc, EntityPlayerSP player, WorldClient world, ItemStack held) {
        Placement p = pending;
        if (!wireLookReady()) {
            return false;
        }
        // Judged against the look already sent, since the place goes out before this tick's look packet.
        MovingObjectPosition mop = PlacementUtil.rayTraceGate(p, player, world, mc, sent.lookVec());
        if (mop == null || player.getPositionEyes(1.0F).distanceTo(mop.hitVec) > PlacementUtil.SERVER_REACH) {
            return false;
        }
        if (!ActionGuard.getInstance().tryReserveAfterCleanTick(this)) {
            return false;
        }
        PacketLog.getInstance().tagged("Scaffold", () -> {
            mc.playerController.onPlayerRightClick(player, world, held, p.support, p.face, mop.hitVec);
            player.swingItem();
        });
        return true;
    }

    /** DRAIN priority, after RotationManager has rewritten the look this packet carries. */
    @EventTarget(priority = EventPriority.DRAIN)
    public void onMotion(EventMotion event) {
        if (event.isPre()) {
            sent.record(event.getYaw(), event.getPitch());
        } else {
            rollLookNoise(); // at POST, so the frames before the next packet already carry it
        }
    }

    /** Ours on the wire and changing this tick. A place is only judged against a look that changed. */
    private boolean wireLookReady() {
        RotationManager rm = RotationManager.getInstance();
        return rm.owns(this) && sent.changedTo(rm.getServerYaw(), rm.getServerPitch());
    }


    /** Switches before the held stack runs dry. */
    private void equipBlock(EntityPlayerSP player) {
        ItemStack held = player.getHeldItem();
        if (held != null && held.getItem() instanceof ItemBlock && held.stackSize > 1) {
            return;
        }
        int slot = InvUtil.bestHotbarBlockSlot(player);
        if (slot != -1) {
            SlotGuard.getInstance().request(this, slot);
        }
    }


    private Placement findPlacement(Minecraft mc, EntityPlayerSP player, WorldClient world) {
        RotationManager rm = RotationManager.getInstance();
        Vec3 eyes = player.getPositionEyes(1.0F);
        float move = PlayerUtil.movementYaw(mc, player);
        // Telly and Polar aim from their hold ray, not the live look.
        Vec3 look = !rm.isActive() ? null
                : telly() ? RotationMath.lookVector(rm.getServerPitch(), holdYaw())
                : polar() ? RotationMath.lookVector(POLAR_PITCH, backward(move))
                : rm.getServerLookVec();
        for (BlockPos target : candidateCells(mc, player, world)) {
            if (!world.getBlockState(target).getBlock().isReplaceable(world, target)) {
                continue;
            }
            for (EnumFacing dir : PlacementUtil.SUPPORT_ORDER) {
                BlockPos support = target.offset(dir);
                EnumFacing face = dir.getOpposite();
                if (!world.getBlockState(support).getBlock().getMaterial().isSolid()
                        || !PlacementUtil.sideClickLegal(face, support, eyes)) {
                    continue;
                }
                Placement candidate = new Placement(target, support, face, lockedHitVec(support, face, eyes, look));
                if (!reachable(rm, move, eyes, candidate)) {
                    continue;
                }
                // Vanilla refuses a place into any live entity, the player included. Last, so it runs once.
                if (!world.checkNoEntityCollision(new AxisAlignedBB(target, target.add(1, 1, 1)))) {
                    break;
                }
                return candidate;
            }
        }
        return null;
    }

    /** Whether the mode's yaw can put the sent look into the candidate's face. */
    private boolean reachable(RotationManager rm, float move, Vec3 eyes, Placement c) {
        if (telly()) {
            return !Float.isNaN(PlacementUtil.facePitch(eyes, c, aimYaw(holdYaw(), eyes, c, AIM_CLAMP)));
        }
        if (polar()) {
            return polarReaches(eyes, c, polarYaw(move, eyes, c));
        }
        return !rm.isActive() || !Float.isNaN(aimPitch(eyes, c, rm.getServerYaw()));
    }

    /** Pitch onto the face along {@code yaw}, NaN when unreachable. Top faces aim straight at the hit point. */
    private static float aimPitch(Vec3 eyes, Placement p, float yaw) {
        if (p.face != EnumFacing.UP) {
            return PlacementUtil.facePitch(eyes, p, yaw);
        }
        return RotationMath.pitchTo(eyes.xCoord, eyes.yCoord, eyes.zCoord,
                p.hitVec.xCoord, p.hitVec.yCoord, p.hitVec.zCoord);
    }

    /** Ascend step, foot and its stepping stones, Telly backfill, then cells ahead along the travel. */
    private Iterable<BlockPos> candidateCells(Minecraft mc, EntityPlayerSP player, WorldClient world) {
        // keyBindJump, not movementInput.jump, which auto-jump and Velocity also write.
        boolean ascend = !keepY.get() && mc.gameSettings.keyBindJump.isKeyDown();
        if (ascend || player.onGround || planeY == Integer.MIN_VALUE) {
            planeY = new BlockPos(player).down().getY();
        }
        Set<BlockPos> cells = new LinkedHashSet<>();
        // The cell above the block you jumped from is the only raised cell the backward look can reach.
        if (ascend && ground != null) {
            cells.add(new BlockPos(ground.getX(), planeY, ground.getZ()));
        }
        BlockPos foot = cell(player, 0.0D, 0.0D);
        cells.add(foot);
        // A foot cell with no support gets a stepping stone beside it.
        if (!telly() && needsCorner(world, foot)) {
            for (EnumFacing dir : EnumFacing.Plane.HORIZONTAL) {
                cells.add(foot.offset(dir));
            }
        }
        Vec3 travel = travel(player);
        if (travel == null) {
            return cells;
        }
        if (telly()) {
            // Cells crossed during the spin could not fire yet.
            addAlong(world, cells, player, foot, travel, -1.0D, BACKFILL);
        }
        addAlong(world, cells, player, foot, travel, 1.0D, expand.get().intValue());
        return cells;
    }

    /** Adds {@code steps} cells past {@code from} along {@code dir}, nearest first. A negative sign walks backward. */
    private void addAlong(WorldClient world, Set<BlockPos> cells, EntityPlayerSP player,
                          BlockPos from, Vec3 dir, double sign, int steps) {
        BlockPos prev = from;
        for (int i = 1; i <= steps; i++) {
            prev = addWithCorners(world, cells, cell(player, dir.xCoord * sign * i, dir.zCoord * sign * i), prev);
        }
    }

    private BlockPos cell(EntityPlayerSP player, double offsetX, double offsetZ) {
        return new BlockPos(player.posX + offsetX, planeY, player.posZ + offsetZ);
    }

    /** Unit horizontal travel direction, or null when standing still. */
    private static Vec3 travel(EntityPlayerSP player) {
        Vec3 dir = new Vec3(player.motionX, 0.0D, player.motionZ).normalize();
        return dir.lengthVector() == 0.0D ? null : dir;
    }

    /** Adds {@code next}, with corner cells first when the step from {@code prev} is diagonal. */
    private BlockPos addWithCorners(WorldClient world, Set<BlockPos> cells, BlockPos next, BlockPos prev) {
        if (next.getX() != prev.getX() && next.getZ() != prev.getZ()
                && (needsCorner(world, next) || needsCorner(world, prev))) {
            cells.add(new BlockPos(next.getX(), planeY, prev.getZ()));
            cells.add(new BlockPos(prev.getX(), planeY, next.getZ()));
        }
        cells.add(next);
        return next;
    }

    /** Replaceable with no solid neighbour to place against. */
    private static boolean needsCorner(WorldClient world, BlockPos cell) {
        if (!world.getBlockState(cell).getBlock().isReplaceable(world, cell)) {
            return false;
        }
        for (EnumFacing dir : PlacementUtil.SUPPORT_ORDER) {
            if (world.getBlockState(cell.offset(dir)).getBlock().getMaterial().isSolid()) {
                return false;
            }
        }
        return true;
    }

    /** Where the look ray meets the face, clamped into the legal band. Random without a usable ray. */
    private Vec3 lockedHitVec(BlockPos support, EnumFacing face, Vec3 eyes, Vec3 dir) {
        if (dir == null) {
            return PlacementUtil.randomHitVec(rand, support, face);
        }
        double reach = PlacementUtil.SERVER_REACH;
        Vec3 end = eyes.addVector(dir.xCoord * reach, dir.yCoord * reach, dir.zCoord * reach);
        double pin = 0.5D + face.getAxisDirection().getOffset() * PlacementUtil.FACE_PIN;
        Vec3 hit;
        switch (face.getAxis()) {
            case X: hit = eyes.getIntermediateWithXValue(end, support.getX() + pin); break;
            case Y: hit = eyes.getIntermediateWithYValue(end, support.getY() + pin); break;
            default: hit = eyes.getIntermediateWithZValue(end, support.getZ() + pin); break;
        }
        if (hit == null) {
            return PlacementUtil.randomHitVec(rand, support, face);
        }
        Random inset = new Random(MathHelper.getPositionRandom(support) ^ insetSalt ^ face.ordinal());
        EnumFacing.Axis axis = face.getAxis();
        return new Vec3(
                axis == EnumFacing.Axis.X ? hit.xCoord : clampFace(hit.xCoord, support.getX(), inset),
                axis == EnumFacing.Axis.Y ? hit.yCoord : clampFace(hit.yCoord, support.getY(), inset),
                axis == EnumFacing.Axis.Z ? hit.zCoord : clampFace(hit.zCoord, support.getZ(), inset));
    }

    /** Clamps into the face's legal band, narrowed by a per-face inset. */
    private static double clampFace(double v, int base, Random inset) {
        double in = inset.nextDouble() * CLAMP_JITTER;
        return MathHelper.clamp_double(v, base + PlacementUtil.HIT_BAND_MIN + in, base + PlacementUtil.HIT_BAND_MAX - in);
    }
}
