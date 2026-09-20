package coldplay.module.utility;

import coldplay.broker.ActionGuard;
import coldplay.broker.BedTracker;
import coldplay.broker.PacketLog;
import coldplay.broker.RotationManager;
import coldplay.broker.SlotGuard;
import coldplay.event.EventPriority;
import coldplay.event.EventRender3D;
import coldplay.event.EventTarget;
import coldplay.event.EventUpdate;
import coldplay.module.Category;
import coldplay.module.Module;
import coldplay.setting.BedGridSetting;
import coldplay.setting.BooleanSetting;
import coldplay.util.InvUtil;
import coldplay.util.PlacementUtil;
import coldplay.util.PlacementUtil.Placement;
import coldplay.util.RenderUtil;
import coldplay.util.ResourcePriority;

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

    private static final int PRIORITY = ResourcePriority.BACKGROUND;
    private static final double TURN_RATE = 180.0D;

    private final BedGridSetting grid = add(new BedGridSetting("Cells")
            .describe("Top-down map of your bed: click a cell to wall that column (never below the bed)."));
    private final BooleanSetting showCells = add(new BooleanSetting("Show Cells", true)
            .describe("Draw the armed open cells in-world: green = about to wall, red = occluded from here."));

    private final Random random = new Random();
    private Placement pending;
    private boolean forcedSneak;

    public BedProtection() {
        super("BedProtection", Category.UTILITY,
                "Automatically walls a shell of blocks around your own bed, re-filling any a raider breaks.");
        addAutoOff();
    }

    @Override
    protected void onDisable() {
        standDown();
    }

    @EventTarget(priority = EventPriority.AIM)
    public void onAim(EventUpdate event) {
        if (!event.isPre() || pending == null) {
            return;
        }

        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP player = mc.thePlayer;
        if (player == null || mc.theWorld == null) {
            return;
        }

        Vec3 eyes = player.getPositionEyes(1.0F);
        float[] angles = RotationManager.angleTo(eyes.xCoord, eyes.yCoord, eyes.zCoord,
                pending.hitVec.xCoord, pending.hitVec.yCoord, pending.hitVec.zCoord);
        RotationManager.getInstance().request(this, angles[0], angles[1], PRIORITY, TURN_RATE);
    }

    @EventTarget(priority = EventPriority.BACKGROUND)
    public void onUpdate(EventUpdate event) {
        if (!event.isPre()) {
            return;
        }

        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP player = mc.thePlayer;
        WorldClient world = mc.theWorld;
        RotationManager rotations = RotationManager.getInstance();
        if (player == null || world == null || mc.currentScreen != null || rotations.isBusyAbove(PRIORITY)) {
            standDown();
            return;
        }

        BlockPos foot = BedTracker.getInstance().ownBedFoot();
        EnumFacing facing = findBedFacing(world, foot);
        if (facing == null) {
            standDown();
            return;
        }

        BlockPos head = foot.offset(facing);
        Placement next = selectPlacement(mc, player, world, foot, head, facing);
        if (next == null) {
            standDown();
            return;
        }
        if (pending == null || !next.target.equals(pending.target)
                || !next.support.equals(pending.support) || next.face != pending.face) {
            pending = next;
        }

        if (!rotations.owns(this) || ActionGuard.getInstance().playerInputDown()) {
            releaseControls(mc);
            return;
        }

        boolean needsSneak = isBed(pending.support, foot, head);
        if (!needsSneak) {
            releaseSneak(mc);
        }

        ItemStack stack = holdBlock(player);
        if (stack == null) {
            releaseControls(mc);
            return;
        }

        MovingObjectPosition hit = PlacementUtil.rayTraceGate(pending, player, world, mc,
                rotations.getSentLookVec());
        if (hit == null) {
            return;
        }

        if (needsSneak && !player.isSneaking()) {
            player.setSneaking(true);
            forcedSneak = true;
            return;
        }

        if (!ActionGuard.getInstance().tryReserveAfterCleanTick(this)) {
            releaseControls(mc);
            return;
        }

        PacketLog.getInstance().tagged("BedProtection", () -> {
            mc.playerController.onPlayerRightClick(player, world, stack, pending.support, pending.face, hit.hitVec);
            player.swingItem();
        });
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
        EnumFacing facing = findBedFacing(world, foot);
        if (facing == null) {
            return;
        }

        Vec3 eyes = player.getPositionEyes(1.0F);
        double reach = mc.playerController.getBlockReachDistance();
        RenderUtil.beginWorldOverlay(2.0F);
        try {
            for (BlockPos cell : targetCells(foot, foot.offset(facing), facing, eyes)) {
                if (!world.getBlockState(cell).getBlock().isReplaceable(world, cell)) {
                    continue;
                }

                boolean reachable = PlacementUtil.find(world, cell, eyes, reach,
                        Vec3::atFaceCenter, support -> false) != null;
                int red = reachable ? 80 : 210;
                int green = reachable ? 210 : 60;
                AxisAlignedBB box = new AxisAlignedBB(cell.getX(), cell.getY(), cell.getZ(),
                        cell.getX() + 1.0D, cell.getY() + 1.0D, cell.getZ() + 1.0D);
                RenderUtil.drawFilledBox(box, red, green, 70, 60);
                RenderGlobal.drawOutlinedBoundingBox(box, red, green, 70, 200);
            }
        } finally {
            RenderUtil.endWorldOverlay();
        }
    }

    private ItemStack holdBlock(EntityPlayerSP player) {
        ItemStack held = player.getHeldItem();
        if (held != null && held.getItem() instanceof ItemBlock) {
            return held;
        }

        int slot = InvUtil.bestHotbarBlockSlot(player);
        if (slot == -1 || !SlotGuard.getInstance().request(this, slot, PRIORITY)) {
            return null;
        }

        held = player.getHeldItem();
        return held != null && held.getItem() instanceof ItemBlock ? held : null;
    }

    private Placement selectPlacement(Minecraft mc, EntityPlayerSP player, WorldClient world,
                                      BlockPos foot, BlockPos head, EnumFacing facing) {
        Vec3 eyes = player.getPositionEyes(1.0F);
        double reach = mc.playerController.getBlockReachDistance();
        for (BlockPos target : targetCells(foot, head, facing, eyes)) {
            if (!world.getBlockState(target).getBlock().isReplaceable(world, target)) {
                continue;
            }

            Placement placement = PlacementUtil.find(world, target, eyes, reach,
                    (support, face) -> PlacementUtil.randomHitVec(random, support, face),
                    support -> isBed(support, foot, head));
            if (placement != null) {
                return placement;
            }
        }
        return null;
    }

    private List<BlockPos> targetCells(BlockPos foot, BlockPos head, EnumFacing facing, Vec3 eyes) {
        int minX = Math.min(foot.getX(), head.getX());
        int maxX = Math.max(foot.getX(), head.getX());
        int minZ = Math.min(foot.getZ(), head.getZ());
        int maxZ = Math.max(foot.getZ(), head.getZ());
        int bedY = foot.getY();
        EnumFacing right = facing.rotateY();
        List<BlockPos> cells = new ArrayList<>();

        for (int ring = 1; ring <= BedGridSetting.MAX_RING; ring++) {
            List<BlockPos> ringCells = new ArrayList<>();
            for (int x = minX - ring; x <= maxX + ring; x++) {
                for (int z = minZ - ring; z <= maxZ + ring; z++) {
                    int distanceX = Math.max(0, Math.max(minX - x, x - maxX));
                    int distanceZ = Math.max(0, Math.max(minZ - z, z - maxZ));
                    int columnRing = Math.max(distanceX, distanceZ);

                    if (columnRing == 0 && ring == 1) {
                        ringCells.add(new BlockPos(x, bedY + 1, z));
                    }
                    if (columnRing != ring) {
                        continue;
                    }

                    int dx = x - foot.getX();
                    int dz = z - foot.getZ();
                    int localX = dx * right.getFrontOffsetX() + dz * right.getFrontOffsetZ();
                    int localZ = dx * facing.getFrontOffsetX() + dz * facing.getFrontOffsetZ();
                    if (!grid.isEnabled(localX, localZ)) {
                        continue;
                    }

                    for (int y = bedY; y <= bedY + ring; y++) {
                        ringCells.add(new BlockPos(x, y, z));
                    }
                }
            }
            ringCells.sort(Comparator.comparingDouble(cell -> eyes.squareDistanceTo(Vec3.atBlockCenter(cell))));
            cells.addAll(ringCells);
        }
        return cells;
    }

    private void standDown() {
        pending = null;
        releaseControls(Minecraft.getMinecraft());
    }

    private void releaseControls(Minecraft mc) {
        SlotGuard.getInstance().release(this);
        releaseSneak(mc);
    }

    private void releaseSneak(Minecraft mc) {
        if (!forcedSneak) {
            return;
        }

        forcedSneak = false;
        if (mc.thePlayer != null && !mc.gameSettings.keyBindSneak.isKeyDown()) {
            mc.thePlayer.setSneaking(false);
        }
    }

    private static EnumFacing findBedFacing(WorldClient world, BlockPos foot) {
        if (foot == null) {
            return null;
        }

        IBlockState state = world.getBlockState(foot);
        if (state.getBlock() != Blocks.bed) {
            return null;
        }

        EnumFacing facing = (EnumFacing) state.getValue(BlockBed.FACING);
        return world.getBlockState(foot.offset(facing)).getBlock() == Blocks.bed ? facing : null;
    }

    private static boolean isBed(BlockPos pos, BlockPos foot, BlockPos head) {
        return pos.equals(foot) || pos.equals(head);
    }
}
