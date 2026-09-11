package coldplay.module.movement;

import com.google.common.base.Predicate;

import coldplay.event.EventRender;
import coldplay.event.EventPriority;
import coldplay.event.EventTarget;
import coldplay.event.EventUpdate;
import coldplay.module.Category;
import coldplay.module.Module;
import coldplay.setting.NumberSetting;
import coldplay.broker.ActionGuard;
import coldplay.util.InvUtil;
import coldplay.util.RayTraceUtil;
import coldplay.broker.RotationManager;
import coldplay.util.ResourcePriority;
import coldplay.broker.SlotGuard;
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

    private final NumberSetting minFall = add(new NumberSetting("MinFallDistance", 3.0, 0.0, 20.0, 0.5)
            .describe("Start an MLG after falling this many blocks."));
    // Max 3.4: the bucket ray reaches 5.0 from the eyes, i.e. ~3.38 below the feet — beyond that the
    // ray can't hit the floor anyway, and near-terminal falls (~3.9 blocks/tick) tick-skip a narrower window.
    private final NumberSetting placeHeight = add(new NumberSetting("PlaceHeight", 3.0, 1.0, 3.4, 0.5)
            .describe("Place when the floor is this close to your feet."));

    private static final int SLOT_PRIORITY = ResourcePriority.FALL_SAFETY;
    private static final int ROTATION_PRIORITY = ResourcePriority.FALL_SAFETY;
    private static final int TIMEOUT_TICKS = 40;
    private static final int LOOK_TICKS = 2;
    private static final double ARM_DISTANCE = 12.0D;
    private static final double REACH = 5.0D;

    private boolean active;
    private boolean placed;
    private int slot = -1;
    private int ticks;
    private int lookTicks;
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

    @EventTarget
    public void onRender(EventRender event) {
        if (!active) {
            return;
        }

        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP player = mc.thePlayer;
        if (player == null || mc.theWorld != activeWorld
                || mc.currentScreen != null || !mc.inGameHasFocus) {
            return;
        }

        float yaw = player.rotationYaw;
        float pitch = 90.0F;
        if (placed && waterPos != null) {
            Vec3 eyes = player.getPositionEyes(event.getPartialTicks());
            Vec3 center = Vec3.atBlockCenter(waterPos);
            float[] look = RotationManager.angleTo(eyes.xCoord, eyes.yCoord, eyes.zCoord,
                    center.xCoord, center.yCoord, center.zCoord);
            yaw = look[0];
            pitch = look[1];
        }
        RotationManager.getInstance().request(this, yaw, pitch, ROTATION_PRIORITY, 180.0D);
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
        if (!active) {
            tryArm(mc, player, world);
            return;
        }
        if (world != activeWorld) {
            SlotGuard.getInstance().relinquish(this);
            clear();
            return;
        }

        SlotGuard slots = SlotGuard.getInstance();
        if (!slots.isHeldBy(this) || !slots.request(this, slot, SLOT_PRIORITY)
                || mc.currentScreen != null || !mc.inGameHasFocus
                || world.provider.doesWaterVaporize() || ++ticks > TIMEOUT_TICKS) {
            reset();
            return;
        }

        if (RotationManager.getInstance().owns(this)) {
            lookTicks++;
        } else {
            lookTicks = 0;
        }

        if (placed) {
            retrieve(player, world);
        } else {
            place(player, world);
        }
    }

    private void tryArm(Minecraft mc, EntityPlayerSP player, WorldClient world) {
        if (mc.currentScreen != null || !mc.inGameHasFocus || world.provider.doesWaterVaporize()
                || player.onGround || player.motionY >= 0.0D || player.isInWater() || player.isInLava()
                || player.isOnLadder() || player.isRiding() || player.capabilities.allowFlying
                || player.fallDistance < minFall.get() || !groundAhead(player, world)) {
            return;
        }

        int found = findWaterBucket(player);
        if (found != -1 && SlotGuard.getInstance().request(this, found, SLOT_PRIORITY)) {
            active = true;
            slot = found;
            activeWorld = world;
        }
    }

    private void place(EntityPlayerSP player, WorldClient world) {
        ItemStack held = player.getHeldItem();
        if (player.onGround || player.motionY >= 0.0D || player.isInWater() || player.isInLava()
                || held == null || held.getItem() != Items.water_bucket) {
            reset();
            return;
        }
        if (lookTicks < LOOK_TICKS) {
            return;
        }

        BlockPos target = placementTarget(player, world);
        if (target != null && useBucket(player)) {
            placed = true;
            waterPos = target;
            ticks = 0;
            lookTicks = 0;
        }
    }

    private void retrieve(EntityPlayerSP player, WorldClient world) {
        ItemStack held = player.getHeldItem();
        if (held == null) {
            reset();
            return;
        }
        if (held.getItem() == Items.water_bucket) {
            return; // the server has not acknowledged the placement yet
        }
        if (held.getItem() != Items.bucket) {
            reset();
            return;
        }

        if ((player.onGround || player.isInWater()) && lookTicks >= LOOK_TICKS
                && isWaterSource(world, waterPos) && rayHitsWater(player, world, waterPos)
                && useBucket(player)) {
            reset(); // C08 is already queued; the restored slot's C09 follows it.
        }
    }

    private boolean useBucket(EntityPlayerSP player) {
        Minecraft mc = Minecraft.getMinecraft();
        ActionGuard guard = ActionGuard.getInstance();
        ItemStack held = player.getHeldItem();
        if (mc.playerController == null || mc.theWorld == null || held == null
                || !guard.tryReserveAfterCleanTick(this)) {
            return false;
        }
        float realYaw = player.rotationYaw;
        float realPitch = player.rotationPitch;
        RotationManager rotations = RotationManager.getInstance();
        try {
            player.rotationYaw = rotations.getServerYaw();
            player.rotationPitch = rotations.getServerPitch();
            coldplay.broker.PacketLog.getInstance().setOrigin("NoFall");
            return mc.playerController.sendUseItem(player, mc.theWorld, held);
        } finally {
            coldplay.broker.PacketLog.getInstance().setOrigin(null);
            player.rotationYaw = realYaw;
            player.rotationPitch = realPitch;
        }
    }

    private boolean groundAhead(EntityPlayerSP player, WorldClient world) {
        double feetY = player.getEntityBoundingBox().minY;
        Vec3 start = new Vec3(player.posX, feetY, player.posZ);
        MovingObjectPosition hit = RayTraceUtil.trace(world, start,
                start.addVector(0.0D, -ARM_DISTANCE, 0.0D), true, true, false);
        return RayTraceUtil.isBlockHit(hit)
                && !world.getBlockState(hit.getBlockPos()).getBlock().getMaterial().isLiquid();
    }

    private BlockPos placementTarget(EntityPlayerSP player, WorldClient world) {
        MovingObjectPosition hit = bucketRay(player, world, false);
        if (hit == null || hit.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK
                || hit.sideHit != EnumFacing.UP
                || player.getEntityBoundingBox().minY - hit.hitVec.yCoord > placeHeight.get()) {
            return null;
        }

        BlockPos water = hit.getBlockPos().up();
        if (water.getX() != MathHelper.floor_double(player.posX)
                || water.getZ() != MathHelper.floor_double(player.posZ)
                || water.getY() > MathHelper.floor_double(player.getEntityBoundingBox().minY)) {
            return null;
        }

        Material target = world.getBlockState(water).getBlock().getMaterial();
        return target.isSolid() || target.isLiquid() ? null : water;
    }

    private boolean rayHitsWater(EntityPlayerSP player, WorldClient world, BlockPos expected) {
        return RayTraceUtil.matchesBlock(bucketRay(player, world, true), expected);
    }

    private MovingObjectPosition bucketRay(EntityPlayerSP player, WorldClient world, boolean empty) {
        Vec3 start = player.getPositionEyes(1.0F);
        Vec3 look = RotationManager.getInstance().getServerLookVec();
        return RayTraceUtil.traceToLook(world, start, look, REACH, empty, !empty, false);
    }

    private boolean isWaterSource(WorldClient world, BlockPos pos) {
        if (pos == null) {
            return false;
        }
        IBlockState state = world.getBlockState(pos);
        return state.getBlock() instanceof BlockLiquid
                && state.getBlock().getMaterial() == Material.water
                && ((Integer) state.getValue(BlockLiquid.LEVEL)).intValue() == 0;
    }

    private int findWaterBucket(EntityPlayerSP player) {
        return InvUtil.findHotbarSlot(player, new Predicate<ItemStack>() {
            @Override
            public boolean apply(ItemStack stack) {
                return stack != null && stack.getItem() == Items.water_bucket;
            }
        });
    }

    private void reset() {
        SlotGuard.getInstance().release(this);
        clear();
    }

    private void clear() {
        active = false;
        placed = false;
        slot = -1;
        ticks = 0;
        lookTicks = 0;
        waterPos = null;
        activeWorld = null;
    }
}
