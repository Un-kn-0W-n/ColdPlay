package coldplay.module.movement;

import com.google.common.base.Predicate;

import coldplay.broker.ActionGuard;
import coldplay.broker.GameStateTracker;
import coldplay.broker.PlayerPacketState;
import coldplay.broker.RotationManager;
import coldplay.broker.SlotGuard;
import coldplay.event.EventPriority;
import coldplay.event.EventTarget;
import coldplay.event.EventUpdate;
import coldplay.module.Category;
import coldplay.module.Module;
import coldplay.setting.NumberSetting;
import coldplay.util.InvUtil;
import coldplay.util.ResourcePriority;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.entity.projectile.ProjectilePhysics;
import net.minecraft.item.ItemEnderPearl;
import net.minecraft.item.ItemStack;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.MathHelper;
import net.minecraft.util.Vec3;

public class AutoPearl extends Module {

    private final NumberSetting voidDepth = add(new NumberSetting("VoidDepth", 4.0, 3.0, 60.0, 1.0)
            .describe("Arm when there is no ground within this many blocks along your fall."));

    private static final Predicate<ItemStack> IS_PEARL = new Predicate<ItemStack>() {
        @Override
        public boolean apply(ItemStack stack) {
            return stack != null && stack.getItem() instanceof ItemEnderPearl;
        }
    };

    private static final int PRIORITY = ResourcePriority.FALL_SAFETY;
    private static final double TURN_RATE = 180.0D;

    private static final double MAX_RANGE = 32.0D;
    private static final float ALIGN_DEG = 3.0F;

    private static final int SETTLED_TICKS = 2;
    private static final int TIMEOUT_TICKS = 40;
    static final int RETRY_TICKS = 15; // floor; longer flights extend it
    private static final double DRAG_MARGIN = 1.25D; // drag costs ~1% horizontal speed per tick
    private static final double TARGET_LIFT = 1.0D; // blocks above the landing spot

    private Vec3 lastSafe; // feet at the last grounded tick
    private WorldClient safeWorld;
    private WorldClient activeWorld;
    private boolean armed;
    private int pearlCooldown; // ticks
    private int slot = -1;
    private int ticks;
    private int settledTicks;
    private long poseEpoch = Long.MIN_VALUE;

    public AutoPearl() {
        super("AutoPearl", Category.MOVEMENT,
                "Throws an ender pearl back to safety when you fall into the void.");
        addAutoOff();
    }

    @Override
    protected void onEnable() {
        lastSafe = null;
        safeWorld = null;
        pearlCooldown = 0;
        poseEpoch = Long.MIN_VALUE;
        reset();
    }

    @Override
    protected void onDisable() {
        reset();
    }

    @EventTarget(priority = EventPriority.AIM)
    public void onAim(EventUpdate event) {
        if (!event.isPre() || !armed) {
            return;
        }

        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP player = mc.thePlayer;
        if (player == null || mc.theWorld != activeWorld
                || mc.currentScreen != null || !mc.inGameHasFocus) {
            return;
        }

        // Aim from next tick's position, which the sent look rides on.
        float[] look = aim(player.getPositionEyes(1.0F).addVector(player.motionX, player.motionY, player.motionZ));
        if (look != null) {
            RotationManager.getInstance().request(this, look[0], look[1], PRIORITY, TURN_RATE);
        }
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
            poseEpoch = Long.MIN_VALUE;
            return;
        }
        GameStateTracker tracker = GameStateTracker.getInstance();
        long epoch = player.sendQueue.getNetworkManager().getPlayerPackets().getPose().epoch;
        boolean corrected = poseEpoch != Long.MIN_VALUE && poseEpoch != epoch;
        poseEpoch = epoch;
        // transitionThisTick's 40-block threshold clears our own pearl (32 across, ~24 up).
        if (!player.isEntityAlive() || tracker.transitionThisTick()) {
            reset();
            lastSafe = null;
            safeWorld = null;
            pearlCooldown = 0;
            return;
        }
        if (corrected) {
            reset(); // re-align after a server correction, keeping the last ground
        }
        if (player.onGround) {
            lastSafe = new Vec3(player.posX, player.getEntityBoundingBox().minY, player.posZ);
            safeWorld = world;
            pearlCooldown = 0;
            reset();
            return;
        }
        if (!armed) {
            // No impact means no teleport, so retry after the cooldown.
            if (pearlCooldown > 0) {
                pearlCooldown--;
            }
            tryArm(mc, player, world);
            return;
        }
        if (world != activeWorld) {
            reset();
            return;
        }

        SlotGuard slots = SlotGuard.getInstance();
        ItemStack held = player.getHeldItem();
        // The tick after a throw only releases the slot, so the restoring C09 gets its own window.
        if (pearlCooldown > 0 || !slots.isHeldBy(this) || !slots.request(this, slot, PRIORITY)
                || mc.currentScreen != null || !mc.inGameHasFocus || mc.playerController == null
                || player.motionY >= 0.0D || !IS_PEARL.apply(held) || ++ticks > TIMEOUT_TICKS) {
            reset();
            return;
        }

        Vec3 eyes = player.getPositionEyes(1.0F);
        float[] look = aim(eyes);
        if (look == null) {
            reset();
            return;
        }
        settledTicks = settled(look) ? settledTicks + 1 : 0;
        if (settledTicks < SETTLED_TICKS || !ActionGuard.getInstance().tryReserveAfterCleanTick(this)) {
            return;
        }
        coldplay.broker.PacketLog.getInstance().tagged("AutoPearl", () -> mc.playerController.sendUseItem(player, world, held));
        pearlCooldown = retryTicks(horizontalTo(eyes), look[1]);
    }

    private void tryArm(Minecraft mc, EntityPlayerSP player, WorldClient world) {
        if (pearlCooldown > 0 || lastSafe == null || safeWorld != world
                || mc.currentScreen != null || !mc.inGameHasFocus
                || player.motionY >= 0.0D // descending only
                || player.isInWater() || player.isInLava() || player.isOnLadder()
                || player.isRiding() || player.capabilities.allowFlying
                || !overVoid(player, world) || aim(player.getPositionEyes(1.0F)) == null) {
            return;
        }

        int found = InvUtil.findHotbarSlot(player, IS_PEARL);
        if (found != -1 && SlotGuard.getInstance().request(this, found, PRIORITY)) {
            armed = true;
            slot = found;
            activeWorld = world;
        }
    }

    /** Nothing solid or liquid within the configured depth along the hitbox's fall arc. */
    private boolean overVoid(EntityPlayerSP player, WorldClient world) {
        AxisAlignedBB box = player.getEntityBoundingBox();
        double floor = box.minY - voidDepth.get();
        // Horizontal speed held flat; sprint air accel roughly cancels drag.
        double vy = player.motionY;
        while (box.minY > floor) {
            AxisAlignedBB next = box.offset(player.motionX, vy, player.motionZ);
            if (!world.getCollidingBoundingBoxes(player, box.addCoord(player.motionX, vy, player.motionZ)).isEmpty()
                    || world.isAnyLiquid(next)) {
                return false;
            }
            box = next;
            vy = (vy - 0.08D) * 0.98D; // gravity then drag, as in moveEntityWithHeading
        }
        return true;
    }

    private double horizontalTo(Vec3 eyes) {
        double dx = lastSafe.xCoord - eyes.xCoord;
        double dz = lastSafe.zCoord - eyes.zCoord;
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** Ticks to outlast the estimated flight, drag included. */
    static int retryTicks(double distance, float pitch) {
        double horizontal = ProjectilePhysics.THROWABLE_SPEED * Math.cos(Math.toRadians(-pitch));
        if (horizontal <= 0.0D) {
            return RETRY_TICKS;
        }
        return Math.max(RETRY_TICKS, (int) Math.ceil(distance / horizontal * DRAG_MARGIN) + 1);
    }

    /** {@code {yaw, pitch}} landing a pearl on lastSafe, or null when out of range. */
    private float[] aim(Vec3 eyes) {
        double targetY = lastSafe.yCoord + TARGET_LIFT;
        double distance = horizontalTo(eyes);
        if (distance > MAX_RANGE) {
            return null;
        }
        float[] look = RotationManager.angleTo(eyes.xCoord, eyes.yCoord, eyes.zCoord,
                lastSafe.xCoord, targetY, lastSafe.zCoord);
        look[1] = throwPitch(distance, targetY - eyes.yCoord);
        return Float.isNaN(look[1]) ? null : look;
    }

    /** Launch pitch whose low arc passes {@code distance} out and {@code dy} up, or NaN when out of range. */
    static float throwPitch(double distance, double dy) {
        double speedSquared = ProjectilePhysics.THROWABLE_SPEED * ProjectilePhysics.THROWABLE_SPEED;
        double gravity = ProjectilePhysics.THROWABLE_GRAVITY;
        double discriminant = speedSquared * speedSquared
                - gravity * (gravity * distance * distance + 2.0D * dy * speedSquared);
        if (discriminant < 0.0D) {
            return Float.NaN;
        }
        return (float) -Math.toDegrees(Math.atan2(
                speedSquared - Math.sqrt(discriminant), gravity * distance));
    }

    /** True once the sent look has reached the requested aim. */
    private boolean settled(float[] look) {
        RotationManager rotations = RotationManager.getInstance();
        PlayerPacketState.Pose pose = Minecraft.getMinecraft().thePlayer.sendQueue
                .getNetworkManager().getPlayerPackets().getPose();
        return rotations.owns(this)
                && pose.isKnown()
                && Math.abs(MathHelper.wrapAngleTo180_float(pose.yaw - look[0])) <= ALIGN_DEG
                && Math.abs(pose.pitch - look[1]) <= ALIGN_DEG;
    }

    private void reset() {
        SlotGuard.getInstance().release(this);
        clear();
    }

    private void clear() {
        armed = false;
        slot = -1;
        ticks = 0;
        settledTicks = 0;
        activeWorld = null;
    }
}
