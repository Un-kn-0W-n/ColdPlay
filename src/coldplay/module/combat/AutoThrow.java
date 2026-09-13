package coldplay.module.combat;

import com.google.common.base.Predicate;

import coldplay.event.EventMotion;
import coldplay.event.EventTarget;
import coldplay.event.EventUpdate;
import coldplay.module.Category;
import coldplay.module.Module;
import coldplay.setting.ItemGridSetting;
import coldplay.setting.NumberSetting;
import coldplay.setting.RangeSetting;
import coldplay.broker.ActionGuard;
import coldplay.util.EntityTargets;
import coldplay.util.InvUtil;
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
import net.minecraft.world.World;

import java.util.concurrent.ThreadLocalRandom;

/** Throws an enabled projectile when the player's real view is already on a visible player. */
public class AutoThrow extends Module {

    private static final double HESITATION_CHANCE = 0.15D;
    private static final float MAX_FLICK_DEG_TICK = 12.0F;

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
    }

    @Override
    protected void onDisable() {
        abortSequence();
        trackedWorld = null;
    }

    // Update PRE lets vanilla flush slot changes as the next C09.
    @EventTarget
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
            if (SlotGuard.getInstance().request(this, throwSlot) && guard.tryReserve(this)) {
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
        ActionGuard guard = ActionGuard.getInstance();
        if (guard.isReserved() || guard.playerActedThisTick()) {
            return;
        }

        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP player = mc.thePlayer;
        if (player == null || mc.theWorld == null || mc.currentScreen != null || player.isUsingItem()) {
            return;
        }

        switch (phase) {
            case IDLE:
                if (player.isRiding()) {
                    return;
                }
                float yawDelta = Math.abs(MathHelper.wrapAngleTo180_float(
                        player.rotationYaw - player.prevRotationYaw));
                if (yawDelta > MAX_FLICK_DEG_TICK) {
                    return;
                }
                long now = System.currentTimeMillis();
                if (now < nextThrowAllowedAt) {
                    return;
                }
                int slot = findThrowableSlot(player);
                if (slot == -1 || !hasAimedTarget(mc, player, range.get())) {
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
                ItemStack held = player.inventory.getCurrentItem();
                if (!SlotGuard.getInstance().isHeldBy(this)
                        || player.inventory.currentItem != throwSlot
                        || !isEnabledThrowable(held)
                        || !hasAimedTarget(mc, player, range.get())) {
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

    private boolean hasAimedTarget(Minecraft mc, final EntityPlayerSP player, double range) {
        RayPicker.Result result = RayPicker.pick(
                mc.theWorld,
                player,
                player.getPositionEyes(1.0F),
                player.getLook(1.0F),
                range,
                range,
                1.0D,
                new Predicate<Entity>() {
                    public boolean apply(Entity entity) {
                        return entity instanceof EntityPlayer
                                && EntityTargets.isLivingTarget(player, entity, true);
                    }
                },
                false,
                false,
                true);
        return result.getEntity() instanceof EntityPlayer;
    }
}
