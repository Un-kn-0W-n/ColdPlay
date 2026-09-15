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

/**
 * Hypixel and Polar walk-bridge behind a spoofed backward look, Telly sprint-jump bridges. Hypixel and Telly
 * click what this tick's look raytraces onto, the way vanilla does. Polar traces its pitch after physics and
 * clicks what the sent look raytraces onto on the next tick.
 */
public class Scaffold extends Module {

    private static final String HYPIXEL = "Hypixel";
    private static final String POLAR = "Polar";
    private static final String TELLY = "Telly";


    public final ModeSetting mode = add(new ModeSetting("Mode", HYPIXEL, HYPIXEL, POLAR, TELLY)
            .describe("Hypixel: walk-bridge holding yaw - 180, pitch raytraced onto the support block. "
                    + "Polar: walk-bridge holding "
                    + "yaw - 180 (45 back toward the middle of the bridge on a straight walk, none on a "
                    + "diagonal), pitch raytraced onto the side of the support. Telly: sprint-jump "
                    + "bridge, auto-jumps facing forward, turns back 120, 159 and 180 degrees over the first "
                    + "three airborne ticks and places from the third on, only on a tick that turns 38 degrees "
                    + "or less and whose look raytraces onto the support."));

    private final HeaderSetting placementHeader = add(new HeaderSetting("Placement"));
    private final BooleanSetting autoSwitch = add(new BooleanSetting("AutoSwitch", true)
            .describe("Silently switch to a block in your hotbar while scaffolding."));
    private final BooleanSetting keepY = add(new BooleanSetting("KeepY", true)
            .describe("On: lock the bridge plane to the Y you last stood on, never builds upward. "
                    + "Off: bridges flat too, but jumping raises the plane with you "
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

    private static final double CLAMP_JITTER = 0.08D; // max inset from a face-band bound, blocks

    private static final float SCAN_MIN_PITCH = 40.0F;
    private static final float SCAN_STEP = 0.25F; // degrees between pitch samples

    private static final int BACKFILL = 3; // Telly: cells covered behind the player each tick
    private static final int TELLY_PLACE_TICK = 3; // first airborne tick allowed to click
    private static final float MAX_PLACE_TURN = 38.0F; // yaw plus pitch degrees on a placing tick, the server flags past 40

    private static final float POLAR_STRAIGHT_OFFSET = 45.0F; // degrees off the hold on a straight walk
    private static final float POLAR_ENTER_AXIS = 20.0F; // degrees off a world axis to start a straight walk
    private static final float POLAR_LEAVE_AXIS = 25.0F; // and to end one
    private static final double POLAR_SIDE_BAND = 0.2D; // blocks off the row centre before the offset changes side
    private static final float POLAR_SCAN_STEP = 0.15F; // the edge windows are a few tenths of a degree wide


    private final Random rand = new Random();
    private Placement pending;
    private List<BlockPos> wanted = new ArrayList<>(); // Hypixel and Telly: this tick's cells, best first
    private int planeY = Integer.MIN_VALUE; // MIN_VALUE until seeded
    private BlockPos ground; // cell last stood on
    private boolean rising; // jump key seen since leaving the ground
    private float heldPitch; // Hypixel and Polar: last aimed pitch
    private long insetSalt;

    private int offGroundTicks; // counted at EventUpdate PRE, before onAim reads it
    private float polarSide; // Polar: -1 or +1, the way a straight walk offsets
    private boolean polarStraight;

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
        polarSide = -1.0F;
        polarStraight = true;
        ground = null;
        rising = false;
        offGroundTicks = 0;
    }

    @Override
    protected void onDisable() {
        pending = null;
        SlotGuard.getInstance().release(this);
    }


    private boolean hypixel() {
        return HYPIXEL.equals(mode.get());
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

    static float polarYaw(float move, boolean straight, float side) {
        float offset = straight ? side * POLAR_STRAIGHT_OFFSET : 0.0F;
        return MathHelper.wrapAngleTo180_float(backward(move) + offset);
    }

    /** Travel within a few degrees of a world axis, with some slack so a wobbling mouse cannot toggle the offset. */
    static boolean straightWalk(float moveYaw, boolean straight) {
        float axisYaw = EnumFacing.fromAngle(moveYaw).getHorizontalIndex() * 90.0F;
        float off = Math.abs(MathHelper.wrapAngleTo180_float(moveYaw - axisYaw));
        return off <= (straight ? POLAR_LEAVE_AXIS : POLAR_ENTER_AXIS);
    }

    /**
     * The offset whose ray slants back toward the middle of the bridge row, from the player's offset off the
     * middle of the block it stands on. The ray on the far side of the middle misses the support for part of
     * each stride.
     */
    static float polarSide(double offsetX, double offsetZ, float moveYaw, float side) {
        double axis = Math.toRadians(EnumFacing.fromAngle(moveYaw).getHorizontalIndex() * 90.0D);
        double right = -offsetX * Math.cos(axis) - offsetZ * Math.sin(axis);
        return Math.abs(right) > POLAR_SIDE_BAND ? Math.signum((float) right) : side;
    }

    /** The solid block under the feet nearest the player's middle, else the cell under its middle. */
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

    /** The camera on the ground, then 120, 159 and 180 degrees back over the first airborne ticks. */
    static float tellyYaw(float cameraYaw, boolean onGround, int offGroundTicks) {
        if (onGround) {
            return cameraYaw;
        }
        if (offGroundTicks <= 1) {
            return cameraYaw - 120.0F;
        }
        if (offGroundTicks == 2) {
            return cameraYaw - 159.0F;
        }
        return cameraYaw - 180.0F;
    }

    /** Yaw plus pitch change between two looks. */
    static float turn(float fromYaw, float fromPitch, float toYaw, float toPitch) {
        return Math.abs(MathHelper.wrapAngleTo180_float(toYaw - fromYaw)) + Math.abs(toPitch - fromPitch);
    }

    /** Whole mouse steps just under {@code degrees}, so a snapped turn cannot round past it. */
    private static float floorToGcd(float degrees) {
        float gcd = RotationManager.gcdStep();
        return Math.max(0.0F, (float) Math.floor((degrees - 0.001F) / gcd) * gcd);
    }

    /** The look request. Hypixel and Telly aim here; Polar re-aims after physics in onMotionAim. */
    @EventTarget(priority = EventPriority.AIM)
    public void onAim(EventUpdate event) {
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP player = mc.thePlayer;
        if (!event.isPre() || player == null || mc.theWorld == null) {
            return;
        }
        RotationManager rm = RotationManager.getInstance();
        float yaw = rm.isActive() ? rm.getServerYaw() : player.rotationYaw;
        float pitch = rm.isActive() ? rm.getServerPitch() : player.rotationPitch;
        float move = PlayerUtil.movementYaw(mc, player);
        if (hypixel()) {
            // On the mouse grid, so the look that goes out is exactly the one traced. An exact diagonal runs
            // through block corners where no stepping stone can be hit, so it sits one mouse step off.
            yaw += RotationManager.gcdSnap(MathHelper.wrapAngleTo180_float(backward(move) - yaw));
            if (180.0F - Math.abs(MathHelper.wrapAngleTo180_float(4.0F * yaw)) < 2.0F * RotationManager.gcdStep()) {
                yaw += RotationManager.gcdStep();
            }
            rm.request(this, yaw, aimHypixel(mc, player, yaw, pitch), PRIORITY, TURN_RATE);
        } else if (polar()) {
            rm.request(this, polarYaw(move, polarStraight, polarSide), heldPitch, PRIORITY, TURN_RATE);
        } else {
            aimTelly(mc, player, rm, yaw, pitch);
        }
    }

    /**
     * Turns to the telly yaw and aims the pitch at the first reachable cell, from the position the server
     * still holds. A placing tick turns at most MAX_PLACE_TURN, yaw first.
     */
    private void aimTelly(Minecraft mc, EntityPlayerSP player, RotationManager rm, float yaw, float pitch) {
        boolean placing = offGroundTicks >= TELLY_PLACE_TICK;
        float yawStep = RotationManager.gcdSnap(MathHelper.wrapAngleTo180_float(
                tellyYaw(player.rotationYaw, player.onGround, offGroundTicks) - yaw));
        if (placing) {
            float cap = floorToGcd(MAX_PLACE_TURN);
            yawStep = MathHelper.clamp_float(yawStep, -cap, cap);
        }
        // Mid-spin, aim along the finished turn so the first placing tick only corrects a little.
        float aimYaw = offGroundTicks > 0 && !placing
                ? tellyYaw(player.rotationYaw, false, TELLY_PLACE_TICK) : yaw + yawStep;
        wanted = new ArrayList<>(candidateCells(mc, player, mc.theWorld));
        pending = findPlacement(mc, player, mc.theWorld, aimYaw);
        float aimed = pending == null ? Float.NaN : aimPitch(player.getPositionEyes(1.0F), pending, aimYaw);
        float pitchStep = Float.isNaN(aimed) ? 0.0F : RotationManager.gcdSnap(aimed - pitch);
        if (placing) {
            float budget = floorToGcd(MAX_PLACE_TURN - Math.abs(yawStep));
            pitchStep = MathHelper.clamp_float(pitchStep, -budget, budget);
        }
        rm.request(this, yaw + yawStep, pitch + pitchStep, PRIORITY, TURN_RATE);
    }

    private float aimHypixel(Minecraft mc, EntityPlayerSP player, float yaw, float current) {
        WorldClient world = mc.theWorld;
        Vec3 eyes = player.getPositionEyes(1.0F);
        wanted = new ArrayList<>(candidateCells(mc, player, world));
        return scanPitch(current, SCAN_STEP, pitch -> clickAlong(world, eyes, RotationManager.lookVec(yaw, pitch)));
    }

    /**
     * Pitch whose click fills the best wanted cell. Keeps the current pitch while it still does, otherwise
     * takes the middle of the pitches that do.
     */
    private float scanPitch(float current, float spacing, Function<Float, Placement> click) {
        float gcd = RotationManager.gcdStep();
        float step = gcd * Math.max(1, Math.round(spacing / gcd));
        float start = current + RotationManager.gcdSnap(SCAN_MIN_PITCH - current);
        int bestRank = Integer.MAX_VALUE;
        List<Float> hits = new ArrayList<>();
        for (int i = 0; start + i * step <= 90.0F; i++) {
            float pitch = start + i * step;
            Placement p = click.apply(pitch);
            if (p == null) {
                continue;
            }
            int rank = wanted.indexOf(p.target);
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
        heldPitch = now != null && wanted.indexOf(now.target) == bestRank ? current : hits.get(hits.size() / 2);
        pending = click.apply(heldPitch);
        return heldPitch;
    }

    /** What a click along {@code look} would place, or null unless it fills one of this tick's cells. */
    private Placement clickAlong(WorldClient world, Vec3 eyes, Vec3 look) {
        MovingObjectPosition hit = RayTraceUtil.traceToLook(world, eyes, look,
                PlacementUtil.SERVER_REACH, false, false, true);
        if (!RayTraceUtil.isBlockHit(hit)
                || !world.getBlockState(hit.getBlockPos()).getBlock().getMaterial().isSolid()) {
            return null;
        }
        BlockPos target = hit.getBlockPos().offset(hit.sideHit);
        if (!wanted.contains(target) ||!world.getBlockState(target).getBlock().isReplaceable(world, target)
                || !world.checkNoEntityCollision(new AxisAlignedBB(target, target.add(1, 1, 1)))) {
            return null;
        }
        return new Placement(target, hit.getBlockPos(), hit.sideHit, hit.hitVec);
    }

    /**
     * Polar's click along a look. The table trig here and the server's can put a grazing ray on different
     * faces, so exact trig has to agree on the block and face.
     */
    private Placement polarClick(WorldClient world, Vec3 eyes, float yaw, float pitch) {
        Placement p = clickAlong(world, eyes, RotationManager.lookVec(yaw, pitch));
        if (p == null) {
            return null;
        }
        MovingObjectPosition exact = RayTraceUtil.traceToLook(world, eyes, exactLook(yaw, pitch),
                PlacementUtil.SERVER_REACH, false, false, true);
        return RayTraceUtil.matchesBlock(exact, p.support, p.face) ? p : null;
    }

    private static Vec3 exactLook(float yaw, float pitch) {
        double yawRad = Math.toRadians(yaw);
        double pitchRad = Math.toRadians(pitch);
        return new Vec3(-Math.sin(yawRad) * Math.cos(pitchRad), -Math.sin(pitchRad),
                Math.cos(yawRad) * Math.cos(pitchRad));
    }

    /**
     * Hypixel and Telly click once this tick's look is final, from the position the server still holds.
     * Vanilla traces its click the same way, before the movement packet that carries the look, and the
     * server checks the click against that look.
     */
    @EventTarget(priority = EventPriority.DRAIN - 1)
    public void onPlace(EventUpdate event) {
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP player = mc.thePlayer;
        WorldClient world = mc.theWorld;
        RotationManager rm = RotationManager.getInstance();
        if (!event.isPre() || polar() || player == null || world == null || mc.currentScreen != null
                || !rm.owns(this)) {
            return;
        }
        if (telly() && (offGroundTicks < TELLY_PLACE_TICK || turn(rm.getSentYaw(), rm.getSentPitch(),
                rm.getServerYaw(), rm.getServerPitch()) > MAX_PLACE_TURN)) {
            return;
        }
        ItemStack held = player.getHeldItem();
        Placement p = clickAlong(world, player.getPositionEyes(1.0F), rm.getServerLookVec());
        if (held == null || !(held.getItem() instanceof ItemBlock) || p == null
                || !ActionGuard.getInstance().tryReserveAfterCleanTick(this)) {
            return;
        }
        PacketLog.getInstance().tagged("Scaffold", () -> {
            if (mc.playerController.onPlayerRightClick(player, world, held, p.support, p.face, p.hitVec)) {
                player.swingItem();
            }
        });
    }

    /** Polar aims from the position this tick's packet carries, after movement. */
    @EventTarget(priority = EventPriority.NORMAL)
    public void onMotionAim(EventMotion event) {
        RotationManager rm = RotationManager.getInstance();
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP player = mc.thePlayer;
        WorldClient world = mc.theWorld;
        if (!event.isPre() || !polar() || player == null || world == null || !rm.owns(this)
                || mc.currentScreen != null) {
            return;
        }
        float move = PlayerUtil.movementYaw(mc, player);
        polarStraight = straightWalk(move, polarStraight);
        if (polarStraight) {
            // The row the player stands on, not the cell under its middle, which drifts off the bridge first.
            BlockPos row = standingOn(world, player);
            polarSide = polarSide(player.posX - row.getX() - 0.5D, player.posZ - row.getZ() - 0.5D, move, polarSide);
        }
        float yaw = polarYaw(move, polarStraight, polarSide);
        // Like Hypixel, an exact diagonal only grazes block corners, so it sits one mouse step off.
        if (!polarStraight
                && 180.0F - Math.abs(MathHelper.wrapAngleTo180_float(4.0F * yaw)) < 2.0F * RotationManager.gcdStep()) {
            yaw += RotationManager.gcdStep();
        }
        // Land the yaw first, so the scan runs on the mouse grid of the look this packet carries.
        rm.reaim(this, yaw, heldPitch, TURN_RATE);
        float aimYaw = rm.getServerYaw();
        Vec3 eyes = player.getPositionEyes(1.0F);
        wanted = new ArrayList<>(candidateCells(mc, player, world));
        float pitch = scanPitch(rm.getServerPitch(), POLAR_SCAN_STEP,
                candidate -> polarClick(world, eyes, aimYaw, candidate));
        rm.reaim(this, yaw, pitch, TURN_RATE);
        pending = polarClick(world, eyes, rm.getServerYaw(), rm.getServerPitch());
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
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP player = mc.thePlayer;
        WorldClient world = mc.theWorld;
        if (player == null || world == null) {
            return;
        }
        if (!event.isPre()) {
            // Pre runs before key input, post after it. A tapped jump is released before the feet clear
            // the next block, so it holds until landing.
            // keyBindJump, not movementInput.jump, which auto-jump and Velocity also write.
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
        // The previous motion packet aimed at this cell from the current position.
        if (polar() && pending != null && firePending(mc, player, world, held)) {
            pending = null;
        }
    }

    private boolean firePending(Minecraft mc, EntityPlayerSP player, WorldClient world, ItemStack held) {
        RotationManager rm = RotationManager.getInstance();
        if (!rm.owns(this)) {
            return false;
        }
        // The place goes out before this tick's look packet, so it is the click the sent look makes.
        Placement p = polarClick(world, player.getPositionEyes(1.0F), rm.getSentYaw(), rm.getSentPitch());
        if (p == null || !ActionGuard.getInstance().tryReserveAfterCleanTick(this)) {
            return false;
        }
        PacketLog.getInstance().tagged("Scaffold", () -> {
            if (mc.playerController.onPlayerRightClick(player, world, held, p.support, p.face, p.hitVec)) {
                player.swingItem();
            }
        });
        return !world.getBlockState(p.target).getBlock().isReplaceable(world, p.target);
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


    /** Telly's first cell whose face a look along {@code yaw} can reach. */
    private Placement findPlacement(Minecraft mc, EntityPlayerSP player, WorldClient world, float yaw) {
        RotationManager rm = RotationManager.getInstance();
        Vec3 eyes = player.getPositionEyes(1.0F);
        Vec3 look = rm.isActive() ? RotationMath.lookVector(rm.getServerPitch(), yaw) : null;
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
                if (Float.isNaN(PlacementUtil.facePitch(eyes, candidate, yaw))) {
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

    /** Pitch onto the face along {@code yaw}, NaN when unreachable. Top faces aim straight at the hit point. */
    private static float aimPitch(Vec3 eyes, Placement p, float yaw) {
        if (p.face != EnumFacing.UP) {
            return PlacementUtil.facePitch(eyes, p, yaw);
        }
        return RotationMath.pitchTo(eyes.xCoord, eyes.yCoord, eyes.zCoord,
                p.hitVec.xCoord, p.hitVec.yCoord, p.hitVec.zCoord);
    }

    /** Ascend step, foot and its stepping stones, Telly backfill, then cells ahead along the travel. */
    private Set<BlockPos> candidateCells(Minecraft mc, EntityPlayerSP player, WorldClient world) {
        boolean ascend = !keepY.get() && rising;
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
