package coldplay.module.movement;

import coldplay.broker.ActionGuard;
import coldplay.broker.PacketLog;
import coldplay.broker.RotationManager;
import coldplay.broker.SlotGuard;
import coldplay.broker.SprintGuard;
import coldplay.event.EventMotion;
import coldplay.event.EventPriority;
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
import coldplay.util.RayTraceUtil;
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

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.function.Function;

public class Scaffold extends Module {
    private static final String HYPIXEL = "Hypixel";
    private static final String POLAR = "Polar";
    private static final String TELLY = "Telly";

    private static final double TURN_RATE = 180.0D;
    private static final float FALLBACK_PITCH = 85.0F;
    private static final int PRIORITY = ResourcePriority.NORMAL;
    private static final double MARKER_HALF = 0.05D;

    private static final double CLAMP_JITTER = 0.08D;

    private static final float SCAN_MIN_PITCH = 40.0F;
    private static final float SCAN_STEP = 0.25F;

    private static final int BACKFILL = 3;
    private static final int TELLY_PLACE_TICK = 3;
    private static final float MAX_PLACE_TURN = 38.0F;

    private static final float POLAR_STRAIGHT_OFFSET = 45.0F;
    private static final float POLAR_ENTER_AXIS = 20.0F;
    private static final float POLAR_LEAVE_AXIS = 25.0F;
    private static final double POLAR_SIDE_BAND = 0.2D;
    private static final float POLAR_SCAN_STEP = 0.15F;

    public final ModeSetting mode = add(new ModeSetting("Mode", HYPIXEL, HYPIXEL, POLAR, TELLY)
            .describe("Hypixel and Polar walk-bridge. Telly sprint-jump bridges."));

    private final HeaderSetting placementHeader = add(new HeaderSetting("Placement"));
    private final BooleanSetting autoSwitch = add(new BooleanSetting("AutoSwitch", true)
            .describe("Silently select blocks from your hotbar."));
    private final BooleanSetting keepY = add(new BooleanSetting("KeepY", true)
            .describe("Keep the bridge level when jumping."));
    private final NumberSetting expand = add(new NumberSetting("Expand", 1.0, 1.0, 6.0, 1.0)
            .describe("Number of cells ahead to try after the cell beneath you."));
    private final BooleanSetting safety = add(new BooleanSetting("Safety", true)
            .describe("Sneak when approaching an edge."));

    private final HeaderSetting debugHeader = add(new HeaderSetting("Debug"));
    private final BooleanSetting render = add(new BooleanSetting("Render", false)
            .describe("Show the pending placement hit point."));

    private final Random random = new Random();
    private Placement pending;
    private List<BlockPos> targets = new ArrayList<>();
    private int planeY = Integer.MIN_VALUE;
    private BlockPos ground;
    private boolean rising;
    private float heldPitch;
    private long insetSalt;

    private int offGroundTicks;
    private float tellyAnchor = Float.NaN;
    private float polarSide;
    private boolean polarStraight;

    public Scaffold() {
        super("Scaffold", Category.MOVEMENT, "Places blocks beneath you while bridging.");
        addAutoOff();
    }

    @Override
    protected void onEnable() {
        planeY = Integer.MIN_VALUE;
        heldPitch = FALLBACK_PITCH;
        insetSalt = random.nextLong();
        polarSide = -1.0F;
        polarStraight = true;
        ground = null;
        rising = false;
        offGroundTicks = 0;
        tellyAnchor = Float.NaN;
    }

    @Override
    protected void onDisable() {
        pending = null;
        SlotGuard.getInstance().release(this);
    }

    @EventTarget
    public void onUpdate(EventUpdate event) {
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP player = mc.thePlayer;
        WorldClient world = mc.theWorld;
        if (player == null || world == null) {
            return;
        }
        if (!event.isPre()) {
            if (mc.gameSettings.keyBindJump.isKeyDown()) {
                rising = true;
            }
            return;
        }
        if (player.onGround) {
            offGroundTicks = 0;
            ground = new BlockPos(player).down();
            rising = false;
        } else {
            offGroundTicks++;
        }

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

        if (polar() && pending != null && firePending(mc, player, world, held)) {
            pending = null;
        }
    }

    @EventTarget(priority = EventPriority.AIM)
    public void onAim(EventUpdate event) {
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP player = mc.thePlayer;
        if (!event.isPre() || player == null || mc.theWorld == null) {
            return;
        }
        RotationManager rotations = RotationManager.getInstance();
        float yaw = rotations.isActive() ? rotations.getServerYaw() : player.rotationYaw;
        float pitch = rotations.isActive() ? rotations.getServerPitch() : player.rotationPitch;
        float move = PlayerUtil.movementYaw(mc, player);
        if (hypixel()) {
            yaw += RotationManager.gcdSnap(MathHelper.wrapAngleTo180_float(backward(move) - yaw));
            yaw = avoidCornerYaw(yaw);
            rotations.request(this, yaw, aimHypixel(mc, player, yaw, pitch), PRIORITY, TURN_RATE);
        } else if (polar()) {
            rotations.request(this, polarYaw(move, polarStraight, polarSide), heldPitch, PRIORITY, TURN_RATE);
        } else {
            aimTelly(mc, player, rotations, yaw, pitch, move);
        }
    }

    @EventTarget(priority = EventPriority.NORMAL)
    public void onMotionAim(EventMotion event) {
        RotationManager rotations = RotationManager.getInstance();
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP player = mc.thePlayer;
        WorldClient world = mc.theWorld;
        if (!event.isPre() || !polar() || player == null || world == null || !rotations.owns(this)
                || mc.currentScreen != null) {
            return;
        }
        float move = PlayerUtil.movementYaw(mc, player);
        polarStraight = straightWalk(move, polarStraight);
        if (polarStraight) {
            BlockPos row = standingOn(world, player);
            polarSide = polarSide(player.posX - row.getX() - 0.5D, player.posZ - row.getZ() - 0.5D, move, polarSide);
        }
        float yaw = polarYaw(move, polarStraight, polarSide);

        if (!polarStraight) {
            yaw = avoidCornerYaw(yaw);
        }

        rotations.reaim(this, yaw, heldPitch, TURN_RATE);
        float aimYaw = rotations.getServerYaw();
        Vec3 eyes = player.getPositionEyes(1.0F);
        targets = candidateCells(player, world);
        float pitch = scanPitch(rotations.getServerPitch(), POLAR_SCAN_STEP,
                candidate -> polarClick(world, eyes, aimYaw, candidate));
        rotations.reaim(this, yaw, pitch, TURN_RATE);
        pending = polarClick(world, eyes, rotations.getServerYaw(), rotations.getServerPitch());
    }

    @EventTarget(priority = EventPriority.DRAIN - 1)
    public void onPlace(EventUpdate event) {
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP player = mc.thePlayer;
        WorldClient world = mc.theWorld;
        RotationManager rotations = RotationManager.getInstance();
        if (!event.isPre() || polar() || player == null || world == null || mc.currentScreen != null
                || !rotations.owns(this)) {
            return;
        }
        if (telly() && (offGroundTicks < TELLY_PLACE_TICK || turn(rotations.getSentYaw(), rotations.getSentPitch(),
                rotations.getServerYaw(), rotations.getServerPitch()) > MAX_PLACE_TURN)) {
            return;
        }
        ItemStack held = player.getHeldItem();
        Placement placement = clickAlong(world, player.getPositionEyes(1.0F), rotations.getServerLookVec());
        if (held == null || !(held.getItem() instanceof ItemBlock)) {
            return;
        }
        sendPlacement(mc, player, world, held, placement);
    }

    @EventTarget
    public void onStrafe(EventStrafe event) {
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP player = mc.thePlayer;
        if (player == null) {
            return;
        }
        boolean tellyMode = telly();
        boolean jumping = tellyMode && autoJump(event, mc, player);
        if (!tellyMode) {
            SprintGuard.getInstance().suppress();
        }

        if (!jumping && safety.get() && EdgeUtil.isApproachingEdge(mc, player, EdgeUtil.MIN_PROBE)) {
            event.applyForcedSneakSlowdown();
        }
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

    private float aimHypixel(Minecraft mc, EntityPlayerSP player, float yaw, float current) {
        WorldClient world = mc.theWorld;
        Vec3 eyes = player.getPositionEyes(1.0F);
        targets = candidateCells(player, world);
        return scanPitch(current, SCAN_STEP, pitch -> clickAlong(world, eyes, RotationManager.lookVec(yaw, pitch)));
    }

    private void aimTelly(Minecraft mc, EntityPlayerSP player, RotationManager rotations, float yaw, float pitch,
                          float move) {
        boolean placing = offGroundTicks >= TELLY_PLACE_TICK;

        if (player.onGround || Float.isNaN(tellyAnchor)) {
            tellyAnchor = move;
        }
        float yawStep = RotationManager.gcdSnap(MathHelper.wrapAngleTo180_float(
                tellyYaw(tellyAnchor, player.onGround, offGroundTicks) - yaw));
        if (placing) {
            float cap = floorToGcd(MAX_PLACE_TURN);
            yawStep = MathHelper.clamp_float(yawStep, -cap, cap);
        }

        float aimYaw = offGroundTicks > 0 && !placing
                ? tellyYaw(tellyAnchor, false, TELLY_PLACE_TICK) : yaw + yawStep;
        targets = candidateCells(player, mc.theWorld);
        pending = findPlacement(player, mc.theWorld, aimYaw);
        float aimed = pending == null ? Float.NaN : aimPitch(player.getPositionEyes(1.0F), pending, aimYaw);
        float pitchStep = Float.isNaN(aimed) ? 0.0F : RotationManager.gcdSnap(aimed - pitch);
        if (placing) {
            float budget = floorToGcd(MAX_PLACE_TURN - Math.abs(yawStep));
            pitchStep = MathHelper.clamp_float(pitchStep, -budget, budget);
        }
        rotations.request(this, yaw + yawStep, pitch + pitchStep, PRIORITY, TURN_RATE);
    }

    private float scanPitch(float current, float spacing, Function<Float, Placement> click) {
        float gcd = RotationManager.gcdStep();
        float step = gcd * Math.max(1, Math.round(spacing / gcd));
        float start = current + RotationManager.gcdSnap(SCAN_MIN_PITCH - current);
        int bestRank = Integer.MAX_VALUE;
        List<Float> hits = new ArrayList<>();
        for (int i = 0; start + i * step <= 90.0F; i++) {
            float pitch = start + i * step;
            Placement placement = click.apply(pitch);
            if (placement == null) {
                continue;
            }
            int rank = targets.indexOf(placement.target);
            if (rank < bestRank) {
                bestRank = rank;
                hits.clear();
            }
            if (rank == bestRank) {
                hits.add(pitch);
            }
        }
        if (hits.isEmpty()) {
            pending = null;
            return heldPitch;
        }
        Placement now = click.apply(current);
        heldPitch = now != null && targets.indexOf(now.target) == bestRank ? current : hits.get(hits.size() / 2);
        pending = click.apply(heldPitch);
        return heldPitch;
    }

    private Placement clickAlong(WorldClient world, Vec3 eyes, Vec3 look) {
        MovingObjectPosition hit = RayTraceUtil.traceToLook(world, eyes, look,
                PlacementUtil.SERVER_REACH, false, false, true);
        if (!RayTraceUtil.isBlockHit(hit)
                || !world.getBlockState(hit.getBlockPos()).getBlock().getMaterial().isSolid()) {
            return null;
        }
        BlockPos target = hit.getBlockPos().offset(hit.sideHit);
        if (!targets.contains(target) || !world.getBlockState(target).getBlock().isReplaceable(world, target)
                || !world.checkNoEntityCollision(new AxisAlignedBB(target, target.add(1, 1, 1)))) {
            return null;
        }
        return new Placement(target, hit.getBlockPos(), hit.sideHit, hit.hitVec);
    }

    private Placement polarClick(WorldClient world, Vec3 eyes, float yaw, float pitch) {
        Placement placement = clickAlong(world, eyes, RotationManager.lookVec(yaw, pitch));
        if (placement == null) {
            return null;
        }
        MovingObjectPosition exact = RayTraceUtil.traceToLook(world, eyes, exactLook(yaw, pitch),
                PlacementUtil.SERVER_REACH, false, false, true);
        return RayTraceUtil.matchesBlock(exact, placement.support, placement.face) ? placement : null;
    }

    private boolean firePending(Minecraft mc, EntityPlayerSP player, WorldClient world, ItemStack held) {
        RotationManager rotations = RotationManager.getInstance();
        if (!rotations.owns(this)) {
            return false;
        }

        Placement placement = polarClick(world, player.getPositionEyes(1.0F), rotations.getSentYaw(), rotations.getSentPitch());
        return sendPlacement(mc, player, world, held, placement)
                && !world.getBlockState(placement.target).getBlock().isReplaceable(world, placement.target);
    }

    private boolean sendPlacement(Minecraft mc, EntityPlayerSP player, WorldClient world,
                                  ItemStack held, Placement placement) {
        if (placement == null || !ActionGuard.getInstance().tryReserveAfterCleanTick(this)) {
            return false;
        }
        PacketLog.getInstance().tagged("Scaffold", () -> {
            if (mc.playerController.onPlayerRightClick(player, world, held,
                    placement.support, placement.face, placement.hitVec)) {
                player.swingItem();
            }
        });
        return true;
    }

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

    private Placement findPlacement(EntityPlayerSP player, WorldClient world, float yaw) {
        RotationManager rotations = RotationManager.getInstance();
        Vec3 eyes = player.getPositionEyes(1.0F);
        Vec3 look = rotations.isActive() ? RotationMath.lookVector(rotations.getServerPitch(), yaw) : null;
        for (BlockPos target : targets) {
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
                if (Float.isNaN(PlacementUtil.facePitch(eyes, candidate, yaw))) {
                    continue;
                }

                if (!world.checkNoEntityCollision(new AxisAlignedBB(target, target.add(1, 1, 1)))) {
                    break;
                }
                return candidate;
            }
        }
        return null;
    }

    private static float aimPitch(Vec3 eyes, Placement placement, float yaw) {
        if (placement.face != EnumFacing.UP) {
            return PlacementUtil.facePitch(eyes, placement, yaw);
        }
        return RotationMath.pitchTo(eyes.xCoord, eyes.yCoord, eyes.zCoord,
                placement.hitVec.xCoord, placement.hitVec.yCoord, placement.hitVec.zCoord);
    }

    private List<BlockPos> candidateCells(EntityPlayerSP player, WorldClient world) {
        boolean ascend = !keepY.get() && rising;
        if (ascend || player.onGround || planeY == Integer.MIN_VALUE) {
            planeY = new BlockPos(player).down().getY();
        }
        Set<BlockPos> cells = new LinkedHashSet<>();

        if (ascend && ground != null) {
            cells.add(new BlockPos(ground.getX(), planeY, ground.getZ()));
        }
        BlockPos foot = cell(player, 0.0D, 0.0D);
        cells.add(foot);

        if (!telly() && needsCorner(world, foot)) {
            for (EnumFacing dir : EnumFacing.Plane.HORIZONTAL) {
                cells.add(foot.offset(dir));
            }
        }
        Vec3 travel = new Vec3(player.motionX, 0.0D, player.motionZ).normalize();
        if (travel.lengthVector() != 0.0D) {
            if (telly()) {
                addAlong(world, cells, player, foot, travel, -1.0D, BACKFILL);
            }
            addAlong(world, cells, player, foot, travel, 1.0D, expand.get().intValue());
        }
        return new ArrayList<>(cells);
    }

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

    private BlockPos addWithCorners(WorldClient world, Set<BlockPos> cells, BlockPos next, BlockPos prev) {
        if (next.getX() != prev.getX() && next.getZ() != prev.getZ()
                && (needsCorner(world, next) || needsCorner(world, prev))) {
            cells.add(new BlockPos(next.getX(), planeY, prev.getZ()));
            cells.add(new BlockPos(prev.getX(), planeY, next.getZ()));
        }
        cells.add(next);
        return next;
    }

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

    private static BlockPos standingOn(WorldClient world, EntityPlayerSP player) {
        AxisAlignedBB box = player.getEntityBoundingBox();
        int y = MathHelper.floor_double(box.minY) - 1;
        BlockPos best = new BlockPos(player.posX, y, player.posZ);
        double bestDistance = Double.POSITIVE_INFINITY;
        for (int x = MathHelper.floor_double(box.minX); x <= MathHelper.floor_double(box.maxX - 1.0E-7D); x++) {
            for (int z = MathHelper.floor_double(box.minZ); z <= MathHelper.floor_double(box.maxZ - 1.0E-7D); z++) {
                BlockPos pos = new BlockPos(x, y, z);
                double dx = x + 0.5D - player.posX;
                double dz = z + 0.5D - player.posZ;
                if (dx * dx + dz * dz < bestDistance && world.getBlockState(pos).getBlock().getMaterial().isSolid()) {
                    best = pos;
                    bestDistance = dx * dx + dz * dz;
                }
            }
        }
        return best;
    }

    private Vec3 lockedHitVec(BlockPos support, EnumFacing face, Vec3 eyes, Vec3 dir) {
        if (dir == null) {
            return PlacementUtil.randomHitVec(random, support, face);
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
            return PlacementUtil.randomHitVec(random, support, face);
        }
        Random inset = new Random(MathHelper.getPositionRandom(support) ^ insetSalt ^ face.ordinal());
        EnumFacing.Axis axis = face.getAxis();
        return new Vec3(
                axis == EnumFacing.Axis.X ? hit.xCoord : clampFace(hit.xCoord, support.getX(), inset),
                axis == EnumFacing.Axis.Y ? hit.yCoord : clampFace(hit.yCoord, support.getY(), inset),
                axis == EnumFacing.Axis.Z ? hit.zCoord : clampFace(hit.zCoord, support.getZ(), inset));
    }

    private static double clampFace(double v, int base, Random inset) {
        double in = inset.nextDouble() * CLAMP_JITTER;
        return MathHelper.clamp_double(v, base + PlacementUtil.HIT_BAND_MIN + in, base + PlacementUtil.HIT_BAND_MAX - in);
    }

    private boolean hypixel() {
        return HYPIXEL.equals(mode.get());
    }

    private boolean polar() {
        return POLAR.equals(mode.get());
    }

    private boolean telly() {
        return TELLY.equals(mode.get());
    }

    private static float backward(float yaw) {
        return MathHelper.wrapAngleTo180_float(yaw + 180.0F);
    }

    private static float avoidCornerYaw(float yaw) {
        float step = RotationManager.gcdStep();
        return 180.0F - Math.abs(MathHelper.wrapAngleTo180_float(4.0F * yaw)) < 2.0F * step
                ? yaw + step : yaw;
    }

    static float polarYaw(float move, boolean straight, float side) {
        float offset = straight ? side * POLAR_STRAIGHT_OFFSET : 0.0F;
        return MathHelper.wrapAngleTo180_float(backward(move) + offset);
    }

    static boolean straightWalk(float moveYaw, boolean straight) {
        float axisYaw = EnumFacing.fromAngle(moveYaw).getHorizontalIndex() * 90.0F;
        float off = Math.abs(MathHelper.wrapAngleTo180_float(moveYaw - axisYaw));
        return off <= (straight ? POLAR_LEAVE_AXIS : POLAR_ENTER_AXIS);
    }

    static float polarSide(double offsetX, double offsetZ, float moveYaw, float side) {
        double axis = Math.toRadians(EnumFacing.fromAngle(moveYaw).getHorizontalIndex() * 90.0D);
        double right = -offsetX * Math.cos(axis) - offsetZ * Math.sin(axis);
        return Math.abs(right) > POLAR_SIDE_BAND ? Math.signum((float) right) : side;
    }

    static float tellyYaw(float moveYaw, boolean onGround, int offGroundTicks) {
        if (onGround) {
            return moveYaw;
        }
        if (offGroundTicks <= 1) {
            return moveYaw - 120.0F;
        }
        if (offGroundTicks == 2) {
            return moveYaw - 159.0F;
        }
        return moveYaw - 180.0F;
    }

    static float turn(float fromYaw, float fromPitch, float toYaw, float toPitch) {
        return Math.abs(MathHelper.wrapAngleTo180_float(toYaw - fromYaw)) + Math.abs(toPitch - fromPitch);
    }

    private static float floorToGcd(float degrees) {
        float gcd = RotationManager.gcdStep();
        return Math.max(0.0F, (float) Math.floor((degrees - 0.001F) / gcd) * gcd);
    }

    private static Vec3 exactLook(float yaw, float pitch) {
        double yawRad = Math.toRadians(yaw);
        double pitchRad = Math.toRadians(pitch);
        return new Vec3(-Math.sin(yawRad) * Math.cos(pitchRad), -Math.sin(pitchRad),
                Math.cos(yawRad) * Math.cos(pitchRad));
    }
}
