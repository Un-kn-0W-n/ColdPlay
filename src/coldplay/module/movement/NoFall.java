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
import coldplay.setting.NumberSetting;
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
    private static final int TIMEOUT_TICKS = 40;
    private static final int REQUIRED_AIM_TICKS = 2;
    private static final double ARM_DISTANCE = 12.0D;
    private static final double BUCKET_REACH = 5.0D;

    private final NumberSetting minFall = add(new NumberSetting("MinFallDistance", 3.0, 0.0, 20.0, 0.5)
            .describe("Start an MLG after falling this many blocks."));
    private final NumberSetting placeHeight = add(new NumberSetting("PlaceHeight", 3.0, 1.0, 3.4, 0.5)
            .describe("Place when the floor is this close to your feet."));

    private Stage stage = Stage.IDLE;
    private int bucketSlot = -1;
    private int stageTicks;
    private int aimTicks;
    private BlockPos waterPos;
    private WorldClient activeWorld;

    public NoFall() {
        super("NoFall", Category.MOVEMENT, "Places and retrieves water to prevent fall damage.");
        addAutoOff();
    }

    @Override
    protected void onEnable() {
        reset();
    }

    @Override
    protected void onDisable() {
        reset();
    }

    @EventTarget(priority = EventPriority.AIM)
    public void onAim(EventUpdate event) {
        if (!event.isPre() || stage == Stage.IDLE) {
            return;
        }

        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP player = mc.thePlayer;
        if (player == null || mc.theWorld != activeWorld || mc.currentScreen != null || !mc.inGameHasFocus) {
            return;
        }

        float yaw = player.rotationYaw;
        float pitch = 90.0F;
        if (stage == Stage.RETRIEVING) {
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
        if (stage != Stage.IDLE && world != activeWorld) {
            SlotGuard.getInstance().relinquish(this);
            clear();
            return;
        }
        if (mc.currentScreen != null || !mc.inGameHasFocus || world.provider.doesWaterVaporize()) {
            reset();
            return;
        }
        if (stage == Stage.IDLE) {
            tryArm(player, world);
            return;
        }

        SlotGuard slots = SlotGuard.getInstance();
        if (!slots.isHeldBy(this) || !slots.request(this, bucketSlot, PRIORITY)
                || ++stageTicks > TIMEOUT_TICKS) {
            reset();
            return;
        }

        aimTicks = RotationManager.getInstance().owns(this) ? aimTicks + 1 : 0;
        if (stage == Stage.PLACING) {
            place(player, world);
        } else {
            retrieve(player, world);
        }
    }

    private void tryArm(EntityPlayerSP player, WorldClient world) {
        if (!isFalling(player) || player.isOnLadder() || player.isRiding()
                || player.capabilities.allowFlying || player.fallDistance < minFall.get()
                || !hasGroundBelow(player, world)) {
            return;
        }

        int slot = InvUtil.findHotbarSlot(player,
                stack -> stack != null && stack.getItem() == Items.water_bucket);
        if (slot == -1 || !SlotGuard.getInstance().request(this, slot, PRIORITY)) {
            return;
        }

        bucketSlot = slot;
        activeWorld = world;
        stage = Stage.PLACING;
    }

    private void place(EntityPlayerSP player, WorldClient world) {
        ItemStack held = player.getHeldItem();
        if (!isFalling(player) || held == null || held.getItem() != Items.water_bucket) {
            reset();
            return;
        }
        if (aimTicks < REQUIRED_AIM_TICKS) {
            return;
        }

        BlockPos target = findPlacementTarget(player, world);
        if (target != null && useBucket(player, world, held)) {
            waterPos = target;
            stage = Stage.RETRIEVING;
            stageTicks = 0;
            aimTicks = 0;
        }
    }

    private void retrieve(EntityPlayerSP player, WorldClient world) {
        ItemStack held = player.getHeldItem();
        if (held != null && held.getItem() == Items.water_bucket) {
            return;
        }
        if (held == null || held.getItem() != Items.bucket) {
            reset();
            return;
        }

        if ((player.onGround || player.isInWater()) && aimTicks >= REQUIRED_AIM_TICKS
                && isWaterSource(world, waterPos)
                && RayTraceUtil.matchesBlock(traceBucket(player, world, true), waterPos)
                && useBucket(player, world, held)) {
            reset();
        }
    }

    private boolean useBucket(EntityPlayerSP player, WorldClient world, ItemStack held) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.playerController == null || !ActionGuard.getInstance().tryReserveAfterCleanTick(this)) {
            return false;
        }

        float yaw = player.rotationYaw;
        float pitch = player.rotationPitch;
        RotationManager rotations = RotationManager.getInstance();
        try {
            player.rotationYaw = rotations.getSentYaw();
            player.rotationPitch = rotations.getSentPitch();
            PacketLog.getInstance().setOrigin("NoFall");
            return mc.playerController.sendUseItem(player, world, held);
        } finally {
            PacketLog.getInstance().setOrigin(null);
            player.rotationYaw = yaw;
            player.rotationPitch = pitch;
        }
    }

    private BlockPos findPlacementTarget(EntityPlayerSP player, WorldClient world) {
        MovingObjectPosition hit = traceBucket(player, world, false);
        if (hit == null || hit.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK
                || hit.sideHit != EnumFacing.UP
                || player.getEntityBoundingBox().minY - hit.hitVec.yCoord > placeHeight.get()) {
            return null;
        }

        BlockPos target = hit.getBlockPos().up();
        if (target.getX() != MathHelper.floor_double(player.posX)
                || target.getZ() != MathHelper.floor_double(player.posZ)
                || target.getY() > MathHelper.floor_double(player.getEntityBoundingBox().minY)) {
            return null;
        }

        Material material = world.getBlockState(target).getBlock().getMaterial();
        return material.isSolid() || material.isLiquid() ? null : target;
    }

    private static boolean isFalling(EntityPlayerSP player) {
        return !player.onGround && player.motionY < 0.0D && !player.isInWater() && !player.isInLava();
    }

    private static boolean hasGroundBelow(EntityPlayerSP player, WorldClient world) {
        Vec3 feet = new Vec3(player.posX, player.getEntityBoundingBox().minY, player.posZ);
        MovingObjectPosition hit = RayTraceUtil.trace(world, feet,
                feet.addVector(0.0D, -ARM_DISTANCE, 0.0D), true, true, false);
        return RayTraceUtil.isBlockHit(hit)
                && !world.getBlockState(hit.getBlockPos()).getBlock().getMaterial().isLiquid();
    }

    private static MovingObjectPosition traceBucket(EntityPlayerSP player, WorldClient world, boolean empty) {
        return RayTraceUtil.traceToLook(world, player.getPositionEyes(1.0F),
                RotationManager.getInstance().getSentLookVec(), BUCKET_REACH, empty, !empty, false);
    }

    private static boolean isWaterSource(WorldClient world, BlockPos pos) {
        IBlockState state = world.getBlockState(pos);
        return state.getBlock() instanceof BlockLiquid
                && state.getBlock().getMaterial() == Material.water
                && ((Integer) state.getValue(BlockLiquid.LEVEL)).intValue() == 0;
    }

    private void reset() {
        SlotGuard.getInstance().release(this);
        clear();
    }

    private void clear() {
        stage = Stage.IDLE;
        bucketSlot = -1;
        stageTicks = 0;
        aimTicks = 0;
        waterPos = null;
        activeWorld = null;
    }

    private enum Stage {
        IDLE,
        PLACING,
        RETRIEVING
    }
}
