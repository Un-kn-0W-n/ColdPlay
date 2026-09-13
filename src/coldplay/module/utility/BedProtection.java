package coldplay.module.utility;

import coldplay.event.EventRender;
import coldplay.event.EventRender3D;
import coldplay.event.EventPriority;
import coldplay.event.EventTarget;
import coldplay.event.EventUpdate;
import coldplay.module.Category;
import coldplay.module.Module;
import coldplay.setting.BedGridSetting;
import coldplay.setting.BooleanSetting;
import coldplay.broker.ActionGuard;
import coldplay.broker.BedTracker;
import coldplay.util.InvUtil;
import coldplay.util.PlacementUtil;
import coldplay.util.PlacementUtil.Placement;
import coldplay.util.RenderUtil;
import coldplay.util.ResourcePriority;
import coldplay.broker.RotationManager;
import coldplay.broker.SlotGuard;

import net.minecraft.block.BlockBed;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

public final class BedProtection extends Module {

    private final BedGridSetting grid = add(new BedGridSetting("Cells")
            .describe("Top-down map of your bed: click a cell to wall that column (never below the bed)."));
    private final BooleanSetting showCells = add(new BooleanSetting("Show Cells", true)
            .describe("Draw the armed open cells in-world: green = about to wall, red = occluded from here."));

    private static final int PRIORITY = ResourcePriority.BACKGROUND;
    private static final double TURN_RATE = 180.0D; // degrees per tick

    private final Random rand = new Random();
    private Placement pending;
    private boolean forcedSneak;

    public BedProtection() {
        super("BedProtection", Category.UTILITY,
                "Automatically walls a shell of blocks around your own bed, re-filling any a raider breaks.");
        addAutoOff();
    }

    @EventTarget(priority = EventPriority.BACKGROUND)
    public void onRender(EventRender event) {
        if (pending == null) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP player = mc.thePlayer;
        if (player == null || mc.theWorld == null) {
            return;
        }
        Vec3 eyes = player.getPositionEyes(1.0F);
        float[] a = RotationManager.angleTo(eyes.xCoord, eyes.yCoord, eyes.zCoord,
                pending.hitVec.xCoord, pending.hitVec.yCoord, pending.hitVec.zCoord);
        RotationManager.getInstance().request(this, a[0], a[1], PRIORITY, TURN_RATE);
    }

    @EventTarget(priority = EventPriority.BACKGROUND)
    public void onUpdate(EventUpdate event) {
        if (!event.isPre()) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP player = mc.thePlayer;
        WorldClient world = mc.theWorld;
        if (player == null || world == null
                || mc.currentScreen != null || RotationManager.getInstance().isBusyAbove(PRIORITY)) {
            standDown();
            return;
        }
        BlockPos foot = BedTracker.getInstance().ownBedFoot();
        EnumFacing facing = foot == null ? null : ownBedFacing(world, foot);
        if (facing == null) {
            standDown();
            return;
        }
        BlockPos head = foot.offset(facing);

        Placement next = selectPlacement(player, world, mc, foot, head, facing);
        if (next == null) {
            standDown();
            return;
        }
        // Keep the old hit point while the pick is unchanged so the aim stays stable.
        if (pending == null || !next.target.equals(pending.target)
                || !next.support.equals(pending.support) || next.face != pending.face) {
            pending = next;
        }

        RotationManager rm = RotationManager.getInstance();
        if (!rm.owns(this) || ActionGuard.getInstance().playerInputDown()) {
            release(mc, player);
            return;
        }

        boolean needBed = isBed(pending.support, foot, head);
        if (!needBed) {
            releaseSneak(mc, player);
        }

        ItemStack held = player.getHeldItem();
        if (held == null || !(held.getItem() instanceof ItemBlock)) {
            int slot = InvUtil.bestHotbarBlockSlot(player);
            if (slot == -1 || !SlotGuard.getInstance().request(this, slot, PRIORITY)) {
                release(mc, player);
                return;
            }
            held = player.getHeldItem();
            if (held == null || !(held.getItem() instanceof ItemBlock)) {
                release(mc, player);
                return;
            }
        }
        MovingObjectPosition mop = PlacementUtil.rayTraceGate(pending, player, world, mc, rm.getServerLookVec());
        if (mop == null) {
            return;
        }

        // Right-clicking a bed sleeps unless sneaking, and the server only sees the sneak
        // after the next C0B, so sneak now and place on a later tick.
        if (needBed && !player.isSneaking()) {
            player.setSneaking(true);
            forcedSneak = true;
            return;
        }

        if (!ActionGuard.getInstance().tryReserveAfterCleanTick(this)) {
            release(mc, player);
            return;
        }
        ItemStack stack = held;
        coldplay.broker.PacketLog.getInstance().tagged("BedProtection", () -> {
            mc.playerController.onPlayerRightClick(player, world, stack, pending.support, pending.face, mop.hitVec);
            player.swingItem();
        });
    }

    @Override
    protected void onDisable() {
        standDown();
    }

    private static EnumFacing ownBedFacing(WorldClient world, BlockPos foot) {
        IBlockState footState = world.getBlockState(foot);
        if (footState.getBlock() != Blocks.bed) {
            return null;
        }
        EnumFacing facing = (EnumFacing) footState.getValue(BlockBed.FACING); // points foot -> head
        return world.getBlockState(foot.offset(facing)).getBlock() == Blocks.bed ? facing : null;
    }

    /** Releases the slot claim and forced sneak but keeps aiming. */
    private void release(Minecraft mc, EntityPlayerSP player) {
        SlotGuard.getInstance().release(this);
        releaseSneak(mc, player);
    }

    private void standDown() {
        pending = null;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer != null) {
            release(mc, mc.thePlayer);
        } else {
            SlotGuard.getInstance().release(this);
            forcedSneak = false;
        }
    }

    private void releaseSneak(Minecraft mc, EntityPlayerSP player) {
        if (!forcedSneak) {
            return;
        }
        forcedSneak = false;
        if (!mc.gameSettings.keyBindSneak.isKeyDown()) {
            player.setSneaking(false);
        }
    }

    /** Prefers non-bed supports; clicking a bed needs the sneak round trip first. */
    private Placement selectPlacement(EntityPlayerSP player, WorldClient world, Minecraft mc,
                                      BlockPos foot, BlockPos head, EnumFacing facing) {
        Vec3 eyes = player.getPositionEyes(1.0F);
        double reach = mc.playerController.getBlockReachDistance();
        for (BlockPos target : targetCells(foot, head, facing, eyes)) {
            if (!world.getBlockState(target).getBlock().isReplaceable(world, target)) {
                continue;
            }
            Placement p = PlacementUtil.find(world, target, eyes, reach,
                    (support, face) -> PlacementUtil.randomHitVec(rand, support, face),
                    support -> isBed(support, foot, head));
            if (p != null) {
                return p;
            }
        }
        return null;
    }

    /** Cells ordered by Chebyshev shell, then by eye distance within a shell. */
    private List<BlockPos> targetCells(BlockPos foot, BlockPos head, EnumFacing facing, Vec3 eyes) {
        int minX = Math.min(foot.getX(), head.getX());
        int maxX = Math.max(foot.getX(), head.getX());
        int minZ = Math.min(foot.getZ(), head.getZ());
        int maxZ = Math.max(foot.getZ(), head.getZ());
        int bedY = foot.getY();
        EnumFacing right = facing.rotateY(); // bed-local +lx axis
        List<BlockPos> cells = new ArrayList<>();
        for (int layer = 1; layer <= BedGridSetting.MAX_RING; layer++) {
            List<BlockPos> inLayer = new ArrayList<>();
            for (int x = minX - layer; x <= maxX + layer; x++) {
                for (int z = minZ - layer; z <= maxZ + layer; z++) {
                    for (int y = bedY; y <= bedY + layer; y++) {
                        int outX = Math.max(0, Math.max(minX - x, x - maxX));
                        int outZ = Math.max(0, Math.max(minZ - z, z - maxZ));
                        int outY = y - bedY;
                        if (Math.max(outX, Math.max(outY, outZ)) != layer) {
                            continue;
                        }
                        int horiz = Math.max(outX, outZ);
                        if (horiz == 0) {
                            if (outY != 1) {
                                continue; // only the lid directly above the bed
                            }
                        } else if (horiz != layer) {
                            continue; // no roofs over inner columns
                        } else {
                            int dx = x - foot.getX();
                            int dz = z - foot.getZ();
                            int lz = dx * facing.getFrontOffsetX() + dz * facing.getFrontOffsetZ();
                            int lx = dx * right.getFrontOffsetX() + dz * right.getFrontOffsetZ();
                            if (!grid.isEnabled(lx, lz)) {
                                continue;
                            }
                        }
                        inLayer.add(new BlockPos(x, y, z));
                    }
                }
            }
            inLayer.sort(Comparator.comparingDouble(cell -> eyes.squareDistanceTo(Vec3.atBlockCenter(cell))));
            cells.addAll(inLayer);
        }
        return cells;
    }

    private static boolean isBed(BlockPos pos, BlockPos foot, BlockPos head) {
        return pos.equals(foot) || pos.equals(head);
    }

    @EventTarget
    public void onRender3D(EventRender3D event) {
        if (!showCells.get()) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP player = mc.thePlayer;
        WorldClient world = mc.theWorld;
        if (player == null || world == null) {
            return;
        }
        BlockPos foot = BedTracker.getInstance().ownBedFoot();
        EnumFacing facing = foot == null ? null : ownBedFacing(world, foot);
        if (facing == null) {
            return;
        }
        Vec3 eyes = player.getPositionEyes(1.0F);
        double reach = mc.playerController.getBlockReachDistance();
        RenderUtil.beginWorldOverlay(2.0F);
        for (BlockPos cell : targetCells(foot, foot.offset(facing), facing, eyes)) {
            if (!world.getBlockState(cell).getBlock().isReplaceable(world, cell)) {
                continue;
            }
            boolean reachable = reachableNow(world, cell, eyes, reach);
            int r = reachable ? 80 : 210;
            int g = reachable ? 210 : 60;
            AxisAlignedBB box = new AxisAlignedBB(cell.getX(), cell.getY(), cell.getZ(),
                    cell.getX() + 1.0D, cell.getY() + 1.0D, cell.getZ() + 1.0D);
            RenderUtil.drawFilledBox(box, r, g, 70, 60);
            RenderGlobal.drawOutlinedBoundingBox(box, r, g, 70, 200);
        }
        RenderUtil.endWorldOverlay();
    }

    private static boolean reachableNow(WorldClient world, BlockPos target, Vec3 eyes, double reach) {
        return PlacementUtil.find(world, target, eyes, reach,
                Vec3::atFaceCenter, support -> false) != null;
    }
}
