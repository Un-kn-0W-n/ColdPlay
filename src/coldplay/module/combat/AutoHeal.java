package coldplay.module.combat;

import com.google.common.base.Predicate;

import coldplay.event.EventPriority;
import coldplay.event.EventTarget;
import coldplay.event.EventUpdate;
import coldplay.module.Category;
import coldplay.module.Module;
import coldplay.setting.ItemGridSetting;
import coldplay.setting.RangeSetting;
import coldplay.broker.ActionGuard;
import coldplay.util.InvUtil;
import coldplay.util.ResourcePriority;
import coldplay.broker.RotationManager;
import coldplay.broker.SlotGuard;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.init.Items;
import net.minecraft.item.ItemPotion;
import net.minecraft.item.ItemStack;
import net.minecraft.potion.Potion;
import net.minecraft.potion.PotionEffect;

import java.util.List;

/** Uses soup first, then a splash heal when health drops below the rolled threshold. */
public class AutoHeal extends Module {

    private static final int PRIORITY = ResourcePriority.EMERGENCY;
    private static final double TURN_RATE = 180.0D;
    private static final float THROW_PITCH = 90.0F;
    private static final float PITCH_READY_DEG = 5.0F;
    private static final int BOWL_TIMEOUT_TICKS = 10;

    private final ItemGridSetting.Entry soupEntry =
            new ItemGridSetting.Entry("Mushroom Stew", new ItemStack(Items.mushroom_stew), true);
    private final ItemGridSetting.Entry healOneEntry =
            new ItemGridSetting.Entry("Splash Heal I", new ItemStack(Items.potionitem, 1, 16373), true);
    private final ItemGridSetting.Entry healTwoEntry =
            new ItemGridSetting.Entry("Splash Heal II", new ItemStack(Items.potionitem, 1, 16421), true);
    private final RangeSetting health;
    private final RangeSetting delay;

    private double rolledThreshold;
    private long nextHealAt;
    private int pendingPotionSlot = -1;
    private int awaitBowlTicks;

    public AutoHeal() {
        super("AutoHeal", Category.COMBAT, "Eats soup or throws a health potion when your health drops low.");
        add(new ItemGridSetting("Soups", soupEntry).describe("Soups AutoHeal may eat."));
        add(new ItemGridSetting("Potions", healOneEntry, healTwoEntry)
                .describe("Splash health potions AutoHeal may throw at your feet."));
        health = add(new RangeSetting("Health", 10.0, 14.0, 1.0, 19.0, 0.5)
                .describe("Heal when health falls to a random threshold in this range (20 = full)."));
        delay = add(new RangeSetting("Delay", 400.0, 700.0, 0.0, 2000.0, 50.0)
                .describe("Random wait between heals (ms), sampled between the two thumbs."));
        addAutoOff();
    }

    @Override
    protected void onEnable() {
        afterHeal();
        pendingPotionSlot = -1;
        awaitBowlTicks = 0;
    }

    @Override
    protected void onDisable() {
        reset();
    }

    @EventTarget(priority = EventPriority.EMERGENCY)
    public void onUpdate(EventUpdate event) {
        if (!event.isPre()) {
            return;
        }

        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP player = mc.thePlayer;
        if (player == null || mc.theWorld == null) {
            reset();
            return;
        }
        if (awaitBowlTicks > 0) {
            tickBowl(mc, player);
            return;
        }
        if (pendingPotionSlot != -1) {
            tickPotion(mc, player);
            return;
        }
        // an AutoBlock hold gives way once the heal claims the slot
        if (mc.currentScreen != null || player.isUsingItem() && !coldplay.broker.UseHold.getInstance().isHeld()
                || player.getHealth() > rolledThreshold
                || System.currentTimeMillis() < nextHealAt) {
            return;
        }

        int slot = findHealSlot(player);
        if (slot == -1 || !SlotGuard.getInstance().request(this, slot, PRIORITY)) {
            return;
        }
        if (isEnabledSoup(player.getHeldItem())) {
            if (!fireUse(mc, player)) {
                return;
            }
            player.clearItemInUse();
            awaitBowlTicks = BOWL_TIMEOUT_TICKS;
            afterHeal();
            return;
        }

        pendingPotionSlot = slot;
        tickPotion(mc, player);
    }

    private void tickPotion(Minecraft mc, EntityPlayerSP player) {
        SlotGuard slots = SlotGuard.getInstance();
        if (mc.currentScreen != null || !slots.isHeldBy(this)
                || player.inventory.currentItem != pendingPotionSlot
                || !isEnabledSplashHeal(player.getHeldItem())) {
            reset();
            return;
        }

        RotationManager rotations = RotationManager.getInstance();
        rotations.request(this, player.rotationYaw, THROW_PITCH, PRIORITY, TURN_RATE);
        if (!rotations.owns(this)
                || Math.abs(rotations.getSentPitch() - THROW_PITCH) > PITCH_READY_DEG
                || !fireUse(mc, player)) {
            return;
        }
        reset();
        afterHeal();
    }

    private void tickBowl(Minecraft mc, EntityPlayerSP player) {
        if (mc.currentScreen != null) {
            return;
        }
        if (!SlotGuard.getInstance().isHeldBy(this)) {
            reset();
            return;
        }

        awaitBowlTicks--;
        ItemStack held = player.getHeldItem();
        boolean bowl = held != null && held.getItem() == Items.bowl;
        if (bowl && ActionGuard.getInstance().tryReserveAfterCleanTick(this)) {
            player.dropOneItem(false);
            reset();
        } else if (awaitBowlTicks <= 0) {
            reset();
        }
    }

    private boolean fireUse(Minecraft mc, EntityPlayerSP player) {
        if (!ActionGuard.getInstance().tryReserveAfterCleanTick(this)) {
            return false;
        }
        coldplay.broker.PacketLog.getInstance().tagged("AutoHeal", () -> mc.playerController.sendUseItem(player, mc.theWorld, player.getHeldItem()));
        return true;
    }

    private void afterHeal() {
        rolledThreshold = health.random();
        nextHealAt = System.currentTimeMillis() + (long) delay.random();
    }

    private void reset() {
        pendingPotionSlot = -1;
        awaitBowlTicks = 0;
        SlotGuard.getInstance().release(this);
    }

    private int findHealSlot(EntityPlayerSP player) {
        int soup = InvUtil.findHotbarSlot(player, new Predicate<ItemStack>() {
            @Override
            public boolean apply(ItemStack stack) {
                return isEnabledSoup(stack);
            }
        });
        return soup != -1 ? soup : InvUtil.findHotbarSlot(player, new Predicate<ItemStack>() {
            @Override
            public boolean apply(ItemStack stack) {
                return isEnabledSplashHeal(stack);
            }
        });
    }

    private boolean isEnabledSoup(ItemStack stack) {
        return stack != null && stack.getItem() == Items.mushroom_stew && soupEntry.isEnabled();
    }

    private boolean isEnabledSplashHeal(ItemStack stack) {
        if (stack == null || !(stack.getItem() instanceof ItemPotion)
                || !ItemPotion.isSplash(stack.getMetadata())) {
            return false;
        }
        List<PotionEffect> effects = ((ItemPotion) stack.getItem()).getEffects(stack);
        if (effects == null) {
            return false;
        }
        for (PotionEffect effect : effects) {
            if (effect.getPotionID() == Potion.heal.id) {
                return effect.getAmplifier() >= 1 ? healTwoEntry.isEnabled() : healOneEntry.isEnabled();
            }
        }
        return false;
    }
}
