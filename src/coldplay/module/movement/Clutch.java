package coldplay.module.movement;

import coldplay.broker.ActionGuard;
import coldplay.broker.PacketLog;
import coldplay.broker.RotationManager;
import coldplay.broker.SlotGuard;
import coldplay.event.EventPriority;
import coldplay.event.EventTarget;
import coldplay.event.EventUpdate;
import coldplay.module.Category;
import coldplay.module.Module;
import coldplay.setting.ModeSetting;
import coldplay.util.InvUtil;
import coldplay.util.PlacementUtil;
import coldplay.util.PlacementUtil.Placement;
import coldplay.util.RayTraceUtil;
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
import net.minecraft.util.Vec3;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.BooleanSupplier;

/** Places blocks under the player once nothing but the void is left below. */
public class Clutch extends Module {
    private static final String HYPIXEL = "Hypixel";

    private static final int PRIORITY = ResourcePriority.FALL_SAFETY;
    private static final double TURN_RATE = 180.0D;
    private static final float MAX_PLACE_TURN = 38.0F; // yaw + pitch on the tick a click goes out
    private static final int SCAN_RADIUS = 4; // cells around each catch cell
    private static final double BAND_JITTER = 0.08D;

    public final ModeSetting mode = add(new ModeSetting("Mode", HYPIXEL, HYPIXEL)
            .describe("Clicks along the look of the next movement packet, never on a big turn."));

    private final BooleanSupplier scaffolding;
    private final Random random = new Random();
    private long hitSalt;
    private Placement pending;

    public Clutch(BooleanSupplier scaffolding) {
        super("Clutch", Category.MOVEMENT, "Places blocks under you when you would fall into the void.");
        this.scaffolding = scaffolding;
        addAutoOff();
    }

    @Override
    protected void onEnable() {
        hitSalt = random.nextLong();
        pending = null;
    }

    @Override
    protected void onDisable() {
        pending = null;
        SlotGuard.getInstance().release(this);
    }

    // Ahead of AutoPearl's slot grab, so a block is tried before a pearl
    @EventTarget(priority = EventPriority.FALL_SAFETY + 1)
    public void onUpdate(EventUpdate event) {
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP player = mc.thePlayer;
        WorldClient world = mc.theWorld;
        if (!event.isPre() || player == null || world == null) {
            return;
        }
        pending = null;
        int slot = isBlock(player.getHeldItem()) ? player.inventory.currentItem : InvUtil.bestHotbarBlockSlot(player);
        // Scaffold places its own blocks mid-jump, which looks like the void until it clicks
        boolean free = mc.currentScreen == null && !player.capabilities.isFlying && !player.isRiding()
                && !scaffolding.getAsBoolean();
        Map<BlockPos, Integer> catches = slot == -1 || !free ? null : voidFall(world, player);
        RotationManager rotations = RotationManager.getInstance();
        Vec3 eyes = player.getPositionEyes(1.0F);
        Vec3 sent = rotations.getSentLookVec();
        Placement next = catches == null ? null : nextBlock(world, eyes, sent, catches, 0);
        float[] look = next == null ? null : lookAt(eyes, next);
        if (catches != null && (look == null
                || Scaffold.turn(rotations.getSentYaw(), rotations.getSentPitch(), look[0], look[1]) > MAX_PLACE_TURN)) {
            // Nothing to click on this tick, so line up next tick's click from where this tick's move leaves the eyes
            double[] step = step(player);
            Vec3 later = eyes.addVector(step[0], step[1], step[2]);
            next = nextBlock(world, later, sent, catches, 1);
            look = next == null ? null : lookAt(later, next);
        }
        if (next == null || !SlotGuard.getInstance().request(this, slot, PRIORITY)) {
            SlotGuard.getInstance().release(this);
            return;
        }
        pending = next;
        rotations.request(this, look[0], look[1], PRIORITY, TURN_RATE);
    }

    @EventTarget(priority = EventPriority.DRAIN - 1)
    public void onPlace(EventUpdate event) {
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP player = mc.thePlayer;
        WorldClient world = mc.theWorld;
        RotationManager rotations = RotationManager.getInstance();
        Placement placement = pending;
        if (!event.isPre() || placement == null || player == null || world == null || !rotations.owns(this)
                || Scaffold.turn(rotations.getSentYaw(), rotations.getSentPitch(),
                        rotations.getServerYaw(), rotations.getServerPitch()) > MAX_PLACE_TURN) {
            return;
        }
        ItemStack held = player.getHeldItem();
        // Hypixel traces a click from the last sent position along the look of the packet after it
        MovingObjectPosition hit = RayTraceUtil.traceToLook(world, player.getPositionEyes(1.0F),
                rotations.getServerLookVec(), PlacementUtil.SERVER_REACH, false, false, true);
        if (!isBlock(held) || !RayTraceUtil.matchesBlock(hit, placement.support, placement.face)
                || !ActionGuard.getInstance().tryReserveAfterCleanTick(this)) {
            return;
        }
        PacketLog.getInstance().tagged("Clutch", () -> {
            if (mc.playerController.onPlayerRightClick(player, world, held, hit.getBlockPos(), hit.sideHit, hit.hitVec)) {
                player.swingItem();
            }
        });
    }

    /**
     * The cell under the hitbox center at each block top the feet would pass, mapped to the move that passes
     * it (0 is this tick's), in fall order. Keys keep pushing in the air and are let go on landing. Null unless
     * the player ends up in the void with nothing below where the fall starts.
     */
    private static Map<BlockPos, Integer> voidFall(WorldClient world, EntityPlayerSP player) {
        // Vanilla keeps a sneaking player on the edge
        if (player.onGround && player.isSneaking()) {
            return null;
        }
        AxisAlignedBB box = player.getEntityBoundingBox();
        double[] step = step(player);
        double[] push = push(player);
        double mx = step[0];
        double my = step[1];
        double mz = step[2];
        boolean grounded = player.onGround;
        boolean fell = false;
        Map<BlockPos, Integer> catches = new LinkedHashMap<>();
        for (int move = 0; box.minY >= 0.0D; move++) {
            if (!grounded) {
                mx += push[0];
                mz += push[1];
            }
            // Entity.moveEntity without step-up: Y, then X, then Z
            List<AxisAlignedBB> solids = world.getCollidingBoundingBoxes(player, box.addCoord(mx, my, mz));
            double dy = my;
            for (AxisAlignedBB solid : solids) {
                dy = solid.calculateYOffset(box, dy);
            }
            int x = MathHelper.floor_double((box.minX + box.maxX) / 2.0D);
            int z = MathHelper.floor_double((box.minZ + box.maxZ) / 2.0D);
            for (int top = MathHelper.floor_double(box.minY); top > box.minY + dy; top--) {
                catches.putIfAbsent(new BlockPos(x, top - 1, z), move);
            }
            box = box.offset(0.0D, dy, 0.0D);
            double dx = mx;
            for (AxisAlignedBB solid : solids) {
                dx = solid.calculateXOffset(box, dx);
            }
            box = box.offset(dx, 0.0D, 0.0D);
            double dz = mz;
            for (AxisAlignedBB solid : solids) {
                dz = solid.calculateZOffset(box, dz);
            }
            box = box.offset(0.0D, 0.0D, dz);
            if (world.isAnyLiquid(box)) {
                return null;
            }
            // Friction comes from where the step started
            double friction = grounded ? 0.546D : 0.91D;
            grounded = dy != my && my < 0.0D;
            if (!grounded && !fell) {
                fell = true;
                AxisAlignedBB column = box.addCoord(0.0D, -box.minY - 1.0D, 0.0D);
                if (!world.getCollidingBoundingBoxes(player, column).isEmpty() || world.isAnyLiquid(column)) {
                    return null;
                }
            }
            my = (dy - 0.08D) * 0.98D;
            mx = dx == mx ? mx * friction : 0.0D;
            mz = dz == mz ? mz * friction : 0.0D;
            // Vanilla zeroes motion this small, so the player stays put
            if (grounded && Math.abs(mx) < 0.005D && Math.abs(mz) < 0.005D) {
                return null;
            }
        }
        return catches;
    }

    /** Last tick's air acceleration from the keys, as moveFlying applied it. */
    private static double[] push(EntityPlayerSP player) {
        float strafe = player.moveStrafing;
        float forward = player.moveForward;
        float input = strafe * strafe + forward * forward;
        if (input < 1.0E-4F) {
            return new double[2];
        }
        RotationManager rotations = RotationManager.getInstance();
        // MoveFix remapped the keys onto the spoofed yaw, which is still last tick's until the broker steps
        float yaw = (rotations.isActive() ? rotations.getServerYaw() : player.rotationYaw) * (float) Math.PI / 180.0F;
        float scale = player.jumpMovementFactor / Math.max(1.0F, MathHelper.sqrt_float(input));
        float sin = MathHelper.sin(yaw);
        float cos = MathHelper.cos(yaw);
        return new double[] {(strafe * cos - forward * sin) * scale, (forward * cos + strafe * sin) * scale};
    }

    /** This tick's move as the fall predictor guesses it. */
    private static double[] step(EntityPlayerSP player) {
        // Friction already cut a grounded player's motion, so the last step is the better guess
        return player.onGround
                ? new double[] {player.posX - player.prevPosX, player.motionY, player.posZ - player.prevPosZ}
                : new double[] {player.motionX, player.motionY, player.motionZ};
    }

    /**
     * First block of the shortest chain, one block a tick from {@code lead} ticks on, that reaches a catch cell
     * before the feet pass it. Ties go to the higher cell.
     */
    private Placement nextBlock(WorldClient world, Vec3 eyes, Vec3 look, Map<BlockPos, Integer> catches, int lead) {
        Placement best = null;
        int bestBlocks = Integer.MAX_VALUE;
        int bestMove = Integer.MAX_VALUE;
        for (Map.Entry<BlockPos, Integer> entry : catches.entrySet()) {
            BlockPos target = entry.getKey();
            int move = entry.getValue();
            if (eyes.yCoord - (target.getY() + 1) > PlacementUtil.SERVER_REACH) {
                break;
            }
            for (int dx = -SCAN_RADIUS; dx <= SCAN_RADIUS; dx++) {
                for (int dz = -SCAN_RADIUS; dz <= SCAN_RADIUS; dz++) {
                    int blocks = Math.abs(dx) + Math.abs(dz) + 1;
                    if (lead + blocks > move + 1 || blocks > bestBlocks || blocks == bestBlocks && move >= bestMove) {
                        continue;
                    }
                    BlockPos cell = target.add(dx, 0, dz);
                    if (!placeable(world, cell)) {
                        continue;
                    }
                    Placement placement = PlacementUtil.find(world, cell, eyes, PlacementUtil.SERVER_REACH,
                            (support, face) -> hitPoint(eyes, look, support, face), support -> false);
                    if (placement != null) {
                        best = placement;
                        bestBlocks = blocks;
                        bestMove = move;
                    }
                }
            }
        }
        return best;
    }

    private static float[] lookAt(Vec3 eyes, Placement placement) {
        return RotationManager.angleTo(eyes.xCoord, eyes.yCoord, eyes.zCoord,
                placement.hitVec.xCoord, placement.hitVec.yCoord, placement.hitVec.zCoord);
    }

    /** Where the sent look crosses the face, pulled into its middle band, so the turn onto it stays small. */
    private Vec3 hitPoint(Vec3 eyes, Vec3 look, BlockPos support, EnumFacing face) {
        Vec3 end = eyes.addVector(look.xCoord * 8.0D, look.yCoord * 8.0D, look.zCoord * 8.0D);
        double pin = 0.5D + face.getAxisDirection().getOffset() * PlacementUtil.FACE_PIN;
        Vec3 cross;
        switch (face.getAxis()) {
            case X: cross = eyes.getIntermediateWithXValue(end, support.getX() + pin); break;
            case Y: cross = eyes.getIntermediateWithYValue(end, support.getY() + pin); break;
            default: cross = eyes.getIntermediateWithZValue(end, support.getZ() + pin); break;
        }
        if (cross == null) {
            cross = new Vec3(support.getX() + 0.5D, support.getY() + 0.5D, support.getZ() + 0.5D);
        }
        // The same face keeps the same inset all enable, so the point does not jitter between ticks
        Random inset = new Random(MathHelper.getPositionRandom(support) ^ hitSalt ^ face.ordinal());
        EnumFacing.Axis axis = face.getAxis();
        return new Vec3(
                axis == EnumFacing.Axis.X ? support.getX() + pin : band(cross.xCoord, support.getX(), inset),
                axis == EnumFacing.Axis.Y ? support.getY() + pin : band(cross.yCoord, support.getY(), inset),
                axis == EnumFacing.Axis.Z ? support.getZ() + pin : band(cross.zCoord, support.getZ(), inset));
    }

    private static double band(double v, int base, Random inset) {
        double in = inset.nextDouble() * BAND_JITTER;
        return MathHelper.clamp_double(v, base + PlacementUtil.HIT_BAND_MIN + in, base + PlacementUtil.HIT_BAND_MAX - in);
    }

    /** Empty, and nothing standing in it. */
    private static boolean placeable(WorldClient world, BlockPos cell) {
        return world.getBlockState(cell).getBlock().isReplaceable(world, cell)
                && world.checkNoEntityCollision(new AxisAlignedBB(cell, cell.add(1, 1, 1)));
    }

    private static boolean isBlock(ItemStack stack) {
        return stack != null && stack.getItem() instanceof ItemBlock;
    }
}
