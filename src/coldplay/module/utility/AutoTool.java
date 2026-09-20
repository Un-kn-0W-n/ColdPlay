package coldplay.module.utility;

import coldplay.broker.SlotGuard;
import coldplay.event.EventDig;
import coldplay.event.EventPreAttack;
import coldplay.event.EventTarget;
import coldplay.event.EventUpdate;
import coldplay.module.Category;
import coldplay.module.Module;
import coldplay.setting.BooleanSetting;
import coldplay.util.ItemUtil;
import coldplay.util.ResourcePriority;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.item.ItemStack;
import net.minecraft.world.WorldSettings;

import java.util.function.ToDoubleFunction;

public class AutoTool extends Module {

    private static final int DIG_HOLD_TICKS = 5;
    private static final int ATTACK_HOLD_TICKS = 20;
    private static final int PRIORITY = ResourcePriority.BACKGROUND;

    private final BooleanSetting tools = add(new BooleanSetting("Tools", true)
            .describe("Swap to the fastest tool when breaking blocks outside creative mode."));
    private final BooleanSetting weapons = add(new BooleanSetting("Weapons", true)
            .describe("Swap to the strongest weapon when attacking entities."));
    private final BooleanSetting switchBack = add(new BooleanSetting("SwitchBack", true)
            .describe("Return to your previous slot once you stop mining/attacking."));

    private int holdTicks;

    public AutoTool() {
        super("AutoTool", Category.UTILITY,
                "Auto-switches to the best tool for mining outside creative mode and the best weapon for attacking in any mode.");
    }

    @Override
    protected void onDisable() {
        release();
    }

    @EventTarget
    public void onDig(EventDig event) {
        Minecraft mc = Minecraft.getMinecraft();
        if (!tools.get() || !canAct(mc) || mc.playerController.getCurrentGameType() != WorldSettings.GameType.SURVIVAL) {
            return;
        }
        // The dig funnel can outlive the chunk, and an unloaded block reads as air.
        Block block = mc.theWorld.getBlockState(event.getPos()).getBlock();
        if (block.getMaterial() != Material.air) {
            hold(mc.thePlayer, stack -> ItemUtil.miningScore(stack, block), DIG_HOLD_TICKS);
        }
    }

    @EventTarget
    public void onAttack(EventPreAttack event) {
        Minecraft mc = Minecraft.getMinecraft();
        if (weapons.get() && canAct(mc) && !mc.playerController.isSpectator()
                && event.getTarget() instanceof EntityLivingBase) {
            hold(mc.thePlayer, ItemUtil::meleeDamage, ATTACK_HOLD_TICKS);
        }
    }

    @EventTarget
    public void onUpdate(EventUpdate event) {
        // Session and world changes are SlotGuard's to clean up; it has already run at BROKER_RESET.
        if (!event.isPre()) {
            return;
        }
        if (!SlotGuard.getInstance().isHeldBy(this)) {
            holdTicks = 0;
        } else if (holdTicks > 0 && --holdTicks == 0) {
            release();
        }
    }

    private static boolean canAct(Minecraft mc) {
        return mc.thePlayer != null && mc.theWorld != null && mc.playerController != null;
    }

    private void hold(EntityPlayerSP player, ToDoubleFunction<ItemStack> scorer, int holdFor) {
        int current = player.inventory.currentItem;
        int best = bestSlot(player, scorer);
        SlotGuard guard = SlotGuard.getInstance();
        // Never claim the slot the player chose themselves: releasing it would drag them off it.
        boolean held = best == current ? guard.isHeldBy(this) : guard.request(this, best, PRIORITY);
        if (held) {
            holdTicks = holdFor;
        }
    }

    private static int bestSlot(EntityPlayerSP player, ToDoubleFunction<ItemStack> scorer) {
        ItemStack[] hotbar = player.inventory.mainInventory;
        int best = player.inventory.currentItem;
        double bestScore = scorer.applyAsDouble(hotbar[best]);
        for (int slot = 0; slot < 9; slot++) {
            double score = scorer.applyAsDouble(hotbar[slot]);
            if (score > bestScore) { // strict, so a tie leaves the player where they are
                bestScore = score;
                best = slot;
            }
        }
        return best;
    }

    private void release() {
        holdTicks = 0;
        SlotGuard guard = SlotGuard.getInstance();
        if (switchBack.get()) {
            guard.releaseWhenSafe(this);
        } else {
            guard.relinquish(this);
        }
    }
}