package coldplay.module.combat;

import com.google.common.base.Predicate;

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
import coldplay.broker.ActionGuard;
import coldplay.broker.BotTracker;
import coldplay.broker.PlayerPacketState;
import coldplay.broker.RotationManager;
import coldplay.util.EntityTargets;
import coldplay.util.InvUtil;
import coldplay.util.ResourcePriority;
import coldplay.broker.SlotGuard;
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

/** Throws an enabled projectile when the player's real view is already on a visible player. */
public class AutoThrow extends Module {

    private static final double HESITATION_CHANCE = 0.15D;
    private static final float MAX_FLICK_DEG_TICK = 12.0F;
    // Above KillAura's NORMAL gate so it leaves the throwable alone; AutoHeal and AutoPearl still evict.
    private static final int SLOT_PRIORITY = ResourcePriority.NORMAL + 1;

    private final NumberSetting range = add(new NumberSetting("Range", 5.0, 1.0, 6.0, 0.1)
            .describe("Max distance (blocks) along your aim to a target."));
    private final RangeSetting delay = add(new RangeSetting("Delay", 500.0, 900.0, 250.0, 2000.0, 50.0)
            .describe("Random delay between throws (ms), sampled between the two thumbs."));

    private final ItemGridSetting.Entry rodEntry =
            new ItemGridSetting.Entry("FishingRod", new ItemStack(Items.fishing_rod), true);
    private final ItemGridSetting.Entry snowballEntry =
            new ItemGridSetting.Entry("SnowBall", new ItemStack(Items.snowball), true);
    private final ItemGridSetting.Entry eggEntry =
            new ItemGridSetting.Entry("Egg", new ItemStack(Items.egg), true);

    // SWITCH, THROW and RESTORE each get their own movement window.
    private enum Phase { IDLE, SWITCH, THROW, RESTORE }

    private long nextThrowAllowedAt;
    private Phase phase = Phase.IDLE;
    private int throwSlot = -1;
    private int throwDeadline; // in ticksExisted
    private World trackedWorld;
    private float lastLookYaw;
    private int lastLookTick = Integer.MIN_VALUE; // ticksExisted at the last movement update

    public AutoThrow() {
        super("AutoThrow", Category.COMBAT,
                "Throws eggs, snowballs or the fishing rod at the player you're already aiming at.");
        add(new ItemGridSetting("Throwables", rodEntry, snowballEntry, eggEntry)
                .describe("Which projectiles AutoThrow may use."));
        addAutoOff();
    }

    @Override
    protected void onEnable() {
        nextThrowAllowedAt = System.currentTimeMillis() + (long) delay.random();
        phase = Phase.IDLE;
        throwSlot = -1;
        trackedWorld = Minecraft.getMinecraft().theWorld;
        lastLookTick = Integer.MIN_VALUE;
    }

    @Override
    protected void onDisable() {
        abortSequence();
        trackedWorld = null;
    }

    // Update PRE lets vanilla flush slot changes as the next C09. Running ahead of KillAura (NORMAL + 2)
    // lets the switch and restore claim their windows before it clicks; its slot gate covers the throw.
    @EventTarget(priority = EventPriority.NORMAL + 3)
    public void onUpdate(EventUpdate event) {
        if (!event.isPre()) {
            return;
        }

        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP player = mc.thePlayer;
        if (player == null || mc.theWorld == null) {
            abortSequence();
            trackedWorld = null;
            return;
        }
        if (mc.theWorld != trackedWorld) {
            abortSequence();
            trackedWorld = mc.theWorld;
            nextThrowAllowedAt = System.currentTimeMillis() + (long) delay.random();
        }

        ActionGuard guard = ActionGuard.getInstance();
        if (phase == Phase.THROW
                && (player.isRiding() || mc.currentScreen != null || player.ticksExisted > throwDeadline)) {
            abortSequence();
            guard.tryReserve(this);
            nextThrowAllowedAt = System.currentTimeMillis() + (long) delay.random();
            return;
        }

        if (phase == Phase.SWITCH && !guard.playerActedLastTick() && mc.currentScreen == null) {
            SlotGuard slots = SlotGuard.getInstance();
            // Free slot only, so the raised priority never evicts AutoTool or Scaffold.
            if (slots.isFree() && slots.request(this, throwSlot, SLOT_PRIORITY) && guard.tryReserve(this)) {
                phase = Phase.THROW;
                throwDeadline = player.ticksExisted + 20;
            } else {
                abortSequence();
                nextThrowAllowedAt = System.currentTimeMillis() + (long) delay.random();
            }
        } else if (phase == Phase.RESTORE && !guard.playerActedLastTick()) {
            abortSequence();
            guard.tryReserve(this);
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
        // Under a silent rotation (KillAura) the projectile follows the sent look, not the camera.
        boolean silent = RotationManager.getInstance().isActive();
        PlayerPacketState.Pose sent = player.sendQueue.getNetworkManager().getPlayerPackets().getPose();
        boolean lookSteady = sampleLook(silent ? sent.yaw : player.rotationYaw, player.ticksExisted);
        ActionGuard guard = ActionGuard.getInstance();
        if (guard.playerActedThisTick() || mc.currentScreen != null || player.isUsingItem()) {
            return;
        }

        switch (phase) {
            case IDLE:
                // Arming sends nothing, so an attack already in this window does not hold it back.
                if (player.isRiding() || !lookSteady) {
                    return;
                }
                long now = System.currentTimeMillis();
                if (now < nextThrowAllowedAt) {
                    return;
                }
                int slot = findThrowableSlot(player);
                if (slot == -1 || !hasAimedTarget(mc, player, sent, silent, range.get())) {
                    return;
                }
                if (ThreadLocalRandom.current().nextDouble() < HESITATION_CHANCE) {
                    nextThrowAllowedAt = now + (long) delay.random();
                    return;
                }
                throwSlot = slot;
                nextThrowAllowedAt = now + (long) delay.random();
                phase = Phase.SWITCH;
                break;

            case THROW:
                if (guard.isReserved()) {
                    return; // the switch's own window, or another action already in this one
                }
                ItemStack held = player.inventory.getCurrentItem();
                if (!SlotGuard.getInstance().isHeldBy(this)
                        || player.inventory.currentItem != throwSlot
                        || !isEnabledThrowable(held)
                        || !hasAimedTarget(mc, player, sent, silent, range.get())) {
                    phase = Phase.RESTORE;
                    return;
                }
                if (!guard.tryReserve(this)) {
                    return;
                }
                coldplay.broker.PacketLog.getInstance().tagged("AutoThrow", () -> mc.playerController.sendUseItem(player, mc.theWorld, held));
                phase = Phase.RESTORE;
                break;

            default:
                break;
        }
    }

    private void abortSequence() {
        SlotGuard.getInstance().release(this);
        phase = Phase.IDLE;
        throwSlot = -1;
    }

    // Measured tick to tick: mouse turns move prevRotationYaw along with rotationYaw.
    private boolean sampleLook(float yaw, int tick) {
        boolean steady = lastLookTick == tick - 1
                && Math.abs(MathHelper.wrapAngleTo180_float(yaw - lastLookYaw)) <= MAX_FLICK_DEG_TICK;
        lastLookYaw = yaw;
        lastLookTick = tick;
        return steady;
    }

    private int findThrowableSlot(EntityPlayerSP player) {
        return InvUtil.findHotbarSlot(player, new Predicate<ItemStack>() {
            @Override
            public boolean apply(ItemStack stack) {
                return isEnabledThrowable(stack);
            }
        });
    }

    private boolean isEnabledThrowable(ItemStack stack) {
        if (stack == null) {
            return false;
        }
        return (stack.getItem() instanceof ItemEgg && eggEntry.isEnabled())
                || (stack.getItem() instanceof ItemSnowball && snowballEntry.isEnabled())
                || (stack.getItem() instanceof ItemFishingRod && rodEntry.isEnabled());
    }

    // The server handles the C08 before this tick's C03, so the projectile leaves along the last sent look.
    private boolean hasAimedTarget(Minecraft mc, EntityPlayerSP player, PlayerPacketState.Pose sent,
                                   boolean silent, double range) {
        if (!sent.isKnown()) {
            return false;
        }
        Entity aimed = aimedPlayer(mc, player, sent.eyes(player.getEyeHeight()), sent.look(), range);
        if (aimed == null) {
            return false;
        }
        // Without a silent rotation the sent look trails the camera by a tick, so both must agree.
        return silent || aimedPlayer(mc, player, player.getPositionEyes(1.0F), player.getLook(1.0F), range) == aimed;
    }

    private Entity aimedPlayer(Minecraft mc, final EntityPlayerSP player, Vec3 eyes, Vec3 look, double range) {
        RayPicker.Result result = RayPicker.pick(
                mc.theWorld,
                player,
                eyes,
                look,
                range,
                range,
                1.0D,
                new Predicate<Entity>() {
                    public boolean apply(Entity entity) {
                        return entity instanceof EntityPlayer
                                && EntityTargets.isLivingTarget(player, entity, true)
                                && !BotTracker.getInstance().isBot(entity);
                    }
                },
                false,
                false,
                true);
        Entity hit = result.getEntity();
        // Friends stay pickable so they still block a throw at whoever stands behind them.
        return hit instanceof EntityPlayer && !FriendManager.getInstance().isFriend(hit.getName()) ? hit : null;
    }
}
