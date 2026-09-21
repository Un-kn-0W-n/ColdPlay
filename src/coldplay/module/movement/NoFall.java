package coldplay.module.movement;

import coldplay.broker.ActionGuard;
import coldplay.broker.RotationManager;
import coldplay.broker.SlotGuard;
import coldplay.event.EventPriority;
import coldplay.event.EventTarget;
import coldplay.event.EventUpdate;
import coldplay.module.Category;
import coldplay.module.Module;
import coldplay.util.InvUtil;
import coldplay.util.RayTraceUtil;
import coldplay.util.ResourcePriority;

import net.minecraft.block.BlockLiquid;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;

public class NoFall extends Module {

    private static final int PRIORITY = ResourcePriority.FALL_SAFETY;

    private int bucketSlot = -1;
    private int aimTicks;
    private BlockPos waterPos;

    public NoFall() {
        super("NoFall", Category.MOVEMENT, "Places water to break a fall, then picks it up.");
    }

    @Override
    protected void onDisable() {
        reset();
    }

    @EventTarget(priority = EventPriority.AIM)
    public void onAim(EventUpdate event) {
        EntityPlayerSP player = Minecraft.getMinecraft().thePlayer;
        if (!event.isPre() || bucketSlot == -1 || player == null) {
            return;
        }

        float yaw = player.rotationYaw;
        float pitch = 90.0F;
        if (waterPos != null) {
            Vec3 eyes = player.getPositionEyes(1.0F);
            Vec3 target = Vec3.atBlockCenter(waterPos);
            float[] angles = RotationManager.angleTo(eyes.xCoord, eyes.yCoord, eyes.zCoord,
                    target.xCoord, target.yCoord, target.zCoord);
            yaw = angles[0];
            pitch = angles[1];
        }
        RotationManager.getInstance().request(this, yaw, pitch, PRIORITY, 180.0D);
    }

    @EventTarget(priority = EventPriority.FALL_SAFETY)
    public void onUpdate(EventUpdate event) {
        if (!event.isPre()) {
            return;
        }

        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP player = mc.thePlayer;
        WorldClient world = mc.theWorld;
        if (player == null || world == null) {
            reset();
            return;
        }
        if (bucketSlot == -1) {
            arm(player);
            return;
        }
        if ((waterPos == null && player.onGround)
                || !SlotGuard.getInstance().request(this, bucketSlot, PRIORITY)) {
            reset();
            return;
        }

        aimTicks = RotationManager.getInstance().owns(this) ? aimTicks + 1 : 0;
        ItemStack held = player.getHeldItem();
        if (held == null || held.getItem() != (waterPos == null ? Items.water_bucket : Items.bucket)) {
            reset();
            return;
        }
        if (waterPos == null) {
            place(player, world, held);
        } else {
            retrieve(player, world, held);
        }
    }

    private void arm(EntityPlayerSP player) {
        if (player.onGround || player.motionY >= 0.0D || player.fallDistance < 3.0F) {
            return;
        }
        int slot = InvUtil.findHotbarSlot(player,
                stack -> stack != null && stack.getItem() == Items.water_bucket);
        if (slot != -1 && SlotGuard.getInstance().request(this, slot, PRIORITY)) {
            bucketSlot = slot;
        }
    }

    private void place(EntityPlayerSP player, WorldClient world, ItemStack held) {
        if (aimTicks < 2) {
            return;
        }
        MovingObjectPosition hit = trace(player, world, false);
        if (!RayTraceUtil.isBlockHit(hit) || hit.sideHit != EnumFacing.UP
                || player.getEntityBoundingBox().minY - hit.hitVec.yCoord > 3.0D) {
            return;
        }
        BlockPos target = hit.getBlockPos().up();
        if (target.getX() != MathHelper.floor_double(player.posX)
                || target.getZ() != MathHelper.floor_double(player.posZ)) {
            return;
        }
        Material material = world.getBlockState(target).getBlock().getMaterial();
        if (!material.isSolid() && !material.isLiquid() && useBucket(player, world, held)) {
            waterPos = target;
            aimTicks = 0;
        }
    }

    private void retrieve(EntityPlayerSP player, WorldClient world, ItemStack held) {
        if (!player.onGround && !player.isInWater()) {
            return;
        }
        IBlockState state = world.getBlockState(waterPos);
        if (state.getBlock().getMaterial() != Material.water
                || !(state.getBlock() instanceof BlockLiquid)
                || ((Integer) state.getValue(BlockLiquid.LEVEL)).intValue() != 0) {
            reset();
            return;
        }
        if (aimTicks >= 2 && RayTraceUtil.matchesBlock(trace(player, world, true), waterPos)
                && useBucket(player, world, held)) {
            reset();
        }
    }

    private static MovingObjectPosition trace(EntityPlayerSP player, WorldClient world, boolean collectWater) {
        return RayTraceUtil.traceToLook(world, player.getPositionEyes(1.0F),
                RotationManager.getInstance().getSentLookVec(), 5.0D, collectWater, !collectWater, false);
    }

    private boolean useBucket(EntityPlayerSP player, WorldClient world, ItemStack held) {
        if (!ActionGuard.getInstance().tryReserveAfterCleanTick(this)) {
            return false;
        }
        RotationManager rotations = RotationManager.getInstance();
        float yaw = player.rotationYaw;
        float pitch = player.rotationPitch;
        try {
            player.rotationYaw = rotations.getSentYaw();
            player.rotationPitch = rotations.getSentPitch();
            return Minecraft.getMinecraft().playerController.sendUseItem(player, world, held);
        } finally {
            player.rotationYaw = yaw;
            player.rotationPitch = pitch;
        }
    }

    private void reset() {
        SlotGuard.getInstance().release(this);
        bucketSlot = -1;
        aimTicks = 0;
        waterPos = null;
    }
}
