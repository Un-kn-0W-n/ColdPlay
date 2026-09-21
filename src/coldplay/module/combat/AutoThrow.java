package coldplay.module.combat;

import coldplay.broker.ActionGuard;
import coldplay.broker.BotTracker;
import coldplay.broker.PacketLog;
import coldplay.broker.PlayerPacketState;
import coldplay.broker.SlotGuard;
import coldplay.event.EventMotion;
import coldplay.event.EventPriority;
import coldplay.event.EventTarget;
import coldplay.event.EventUpdate;
import coldplay.friend.FriendManager;
import coldplay.module.Category;
import coldplay.module.Module;
import coldplay.setting.ItemGridSetting;
import coldplay.setting.NumberSetting;
import coldplay.setting.RangeSetting;
import coldplay.util.EntityTargets;
import coldplay.util.InvUtil;
import coldplay.util.ResourcePriority;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.renderer.RayPicker;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Items;
import net.minecraft.item.ItemEgg;
import net.minecraft.item.ItemFishingRod;
import net.minecraft.item.ItemSnowball;
import net.minecraft.item.ItemStack;
import net.minecraft.util.MathHelper;
import net.minecraft.util.Vec3;
import net.minecraft.world.World;

import java.util.concurrent.ThreadLocalRandom;

public class AutoThrow extends Module {

    private static final int SLOT_PRIORITY = ResourcePriority.NORMAL + 1;
    private static final float MAX_YAW_CHANGE = 12.0F;
    private static final double HESITATION_CHANCE = 0.15D;

    private final NumberSetting range = add(new NumberSetting("Range", 5.0, 1.0, 6.0, 0.1)
            .describe("Maximum target distance."));
    private final RangeSetting delay = add(new RangeSetting("Delay", 500.0, 900.0, 250.0, 2000.0, 50.0)
            .describe("Delay between throws in milliseconds."));

    private final ItemGridSetting.Entry rodEntry =
            new ItemGridSetting.Entry("FishingRod", new ItemStack(Items.fishing_rod), true);
    private final ItemGridSetting.Entry snowballEntry =
            new ItemGridSetting.Entry("SnowBall", new ItemStack(Items.snowball), true);
    private final ItemGridSetting.Entry eggEntry =
            new ItemGridSetting.Entry("Egg", new ItemStack(Items.egg), true);

    private Phase phase = Phase.IDLE;
    private int throwSlot = -1;
    private int throwDeadline;
    private long nextThrowAt;
    private World trackedWorld;
    private float lastYaw;
    private int lastLookTick = Integer.MIN_VALUE;

    public AutoThrow() {
        super("AutoThrow", Category.COMBAT, "Uses a projectile at the player you're aiming at.");
        add(new ItemGridSetting("Throwables", rodEntry, snowballEntry, eggEntry)
                .describe("Projectiles to use."));
        addAutoOff();
    }

    @Override
    protected void onEnable() {
        reset();
        trackedWorld = Minecraft.getMinecraft().theWorld;
        lastLookTick = Integer.MIN_VALUE;
        scheduleNextThrow();
    }

    @Override
    protected void onDisable() {
        reset();
        trackedWorld = null;
    }

    @EventTarget(priority = EventPriority.NORMAL + 3)
    public void onUpdate(EventUpdate event) {
        if (!event.isPre()) {
            return;
        }

        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP player = mc.thePlayer;
        if (player == null || mc.theWorld == null) {
            reset();
            trackedWorld = null;
            lastLookTick = Integer.MIN_VALUE;
            return;
        }
        if (mc.theWorld != trackedWorld) {
            reset();
            trackedWorld = mc.theWorld;
            lastLookTick = Integer.MIN_VALUE;
            scheduleNextThrow();
        }

        if ((phase == Phase.SWITCH || phase == Phase.THROW)
                && (mc.currentScreen != null || player.isRiding() || player.isUsingItem()
                || (phase == Phase.THROW && player.ticksExisted > throwDeadline))) {
            queueRestore();
        }

        ActionGuard guard = ActionGuard.getInstance();
        if (phase == Phase.IDLE || phase == Phase.THROW || guard.playerActedLastTick()) {
            return;
        }

        SlotGuard slots = SlotGuard.getInstance();
        if (phase == Phase.SWITCH && !slots.isFree()) {
            reset();
            scheduleNextThrow();
            return;
        }
        if (!guard.tryReserve(this)) {
            return;
        }
        if (phase == Phase.RESTORE) {
            reset();
        } else if (slots.request(this, throwSlot, SLOT_PRIORITY)) {
            phase = Phase.THROW;
            throwDeadline = player.ticksExisted + 20;
        } else {
            reset();
            scheduleNextThrow();
        }
    }

    @EventTarget
    public void onMotion(EventMotion event) {
        if (!event.isPre()) {
            return;
        }

        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP player = mc.thePlayer;
        if (player == null || mc.theWorld == null) {
            return;
        }

        boolean steady = sampleLook(player.rotationYaw, player.ticksExisted);
        ActionGuard guard = ActionGuard.getInstance();
        if (guard.playerActedThisTick() || mc.currentScreen != null || player.isUsingItem()) {
            return;
        }

        if (phase == Phase.IDLE) {
            if (!steady || player.isRiding() || System.currentTimeMillis() < nextThrowAt) {
                return;
            }
            int slot = InvUtil.findHotbarSlot(player, this::isEnabledThrowable);
            if (slot == -1 || !hasAimedTarget(mc, player)) {
                return;
            }
            if (ThreadLocalRandom.current().nextDouble() < HESITATION_CHANCE) {
                scheduleNextThrow();
                return;
            }
            throwSlot = slot;
            phase = Phase.SWITCH;
            return;
        }
        if (phase != Phase.THROW || guard.isReserved()) {
            return;
        }

        ItemStack held = player.getHeldItem();
        if (!steady || !SlotGuard.getInstance().isHeldBy(this)
                || player.inventory.currentItem != throwSlot || !isEnabledThrowable(held)
                || !hasAimedTarget(mc, player)) {
            queueRestore();
            return;
        }
        if (guard.tryReserve(this)) {
            PacketLog.getInstance().tagged("AutoThrow",
                    () -> mc.playerController.sendUseItem(player, mc.theWorld, held));
            queueRestore();
        }
    }

    private boolean sampleLook(float yaw, int tick) {
        boolean steady = lastLookTick == tick - 1
                && Math.abs(MathHelper.wrapAngleTo180_float(yaw - lastYaw)) <= MAX_YAW_CHANGE;
        lastYaw = yaw;
        lastLookTick = tick;
        return steady;
    }

    private boolean isEnabledThrowable(ItemStack stack) {
        if (stack == null) {
            return false;
        }
        return (stack.getItem() instanceof ItemEgg && eggEntry.isEnabled())
                || (stack.getItem() instanceof ItemSnowball && snowballEntry.isEnabled())
                || (stack.getItem() instanceof ItemFishingRod && rodEntry.isEnabled());
    }

    private boolean hasAimedTarget(Minecraft mc, EntityPlayerSP player) {
        PlayerPacketState.Pose sent = player.sendQueue.getNetworkManager().getPlayerPackets().getPose();
        if (!sent.isKnown()) {
            return false;
        }
        double reach = range.get();
        Entity target = aimedPlayer(mc, player, sent.eyes(player.getEyeHeight()), sent.look(), reach);
        return target != null
                && aimedPlayer(mc, player, player.getPositionEyes(1.0F), player.getLook(1.0F), reach) == target;
    }

    private Entity aimedPlayer(Minecraft mc, EntityPlayerSP player, Vec3 eyes, Vec3 look, double reach) {
        RayPicker.Result result = RayPicker.pick(mc.theWorld, player, eyes, look, reach, reach, 1.0D,
                entity -> entity instanceof EntityPlayer
                        && EntityTargets.isLivingTarget(player, entity, true)
                        && !BotTracker.getInstance().isBot(entity),
                false, false, true);
        Entity hit = result.getEntity();
        return hit instanceof EntityPlayer && !FriendManager.getInstance().isFriend(hit.getName()) ? hit : null;
    }

    private void scheduleNextThrow() {
        nextThrowAt = System.currentTimeMillis() + (long) delay.random();
    }

    private void queueRestore() {
        phase = Phase.RESTORE;
        scheduleNextThrow();
    }

    private void reset() {
        SlotGuard.getInstance().release(this);
        phase = Phase.IDLE;
        throwSlot = -1;
    }

    private enum Phase {
        IDLE, SWITCH, THROW, RESTORE
    }
}
