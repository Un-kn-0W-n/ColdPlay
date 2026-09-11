package coldplay.module.utility;

import coldplay.event.EventAttack;
import coldplay.event.EventDig;
import coldplay.event.EventTarget;
import coldplay.event.EventUpdate;
import coldplay.module.Category;
import coldplay.module.Module;
import coldplay.setting.BooleanSetting;
import coldplay.util.ItemUtil;
import coldplay.broker.SlotGuard;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.item.ItemStack;
import net.minecraft.util.MovingObjectPosition;

import java.util.function.ToDoubleFunction;

public class AutoTool extends Module {
    // 5 outlasts vanilla's post-break blockHitDelay and a jitter click's press/release gap
    private static final int HOLD_TICKS = 5;

    private final BooleanSetting tools = add(new BooleanSetting("Tools", true).describe("Swap to the fastest tool when breaking blocks."));
    private final BooleanSetting weapons = add(new BooleanSetting("Weapons", true).describe("Swap to the strongest weapon when attacking entities."));
    private final BooleanSetting switchBack = add(new BooleanSetting("SwitchBack", true).describe("Return to your previous slot once you stop mining/attacking."));

    private int lastActionTick = Integer.MIN_VALUE;

    public AutoTool() {
        super("AutoTool", Category.UTILITY, "Auto-switches to the best tool for mining and the best weapon for attacking.");
    }

    @Override
    protected void onDisable() {
        stop();
    }

    @EventTarget
    public void onDig(EventDig event) {
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP player = validPlayer(mc);
        if (player == null || mc.theWorld == null || !tools.get()) {
            return;
        }
        Block block = mc.theWorld.getBlockState(event.getPos()).getBlock();
        if (block.getMaterial() != Material.air) {
            lastActionTick = player.ticksExisted;
            switchToBest(player, stack -> ItemUtil.miningScore(stack, block));
        }
    }

    @EventTarget
    public void onAttack(EventAttack event) {
        EntityPlayerSP player = validPlayer(Minecraft.getMinecraft());
        if (player != null && weapons.get() && isEntityHit(event.getTarget())) {
            lastActionTick = player.ticksExisted;
            switchToBest(player, ItemUtil::meleeDamage);
        }
    }

    @EventTarget
    public void onUpdate(EventUpdate event) {
        if (!event.isPre()) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP player = validPlayer(mc);
        if (player == null) {
            stop();
            return;
        }
        if ((mc.playerController != null && mc.playerController.getIsHittingBlock())
                || player.isUsingItem() // any C09 kills a sword block server-side
                || mc.currentScreen != null // vanilla cannot change the slot under a GUI
                || player.ticksExisted <= lastActionTick + HOLD_TICKS) {
            return;
        }
        stop();
    }

    private static EntityPlayerSP validPlayer(Minecraft mc) {
        EntityPlayerSP player = mc.thePlayer;
        return (player == null || player.capabilities.isCreativeMode) ? null : player;
    }

    private static boolean isEntityHit(MovingObjectPosition mov) {
        return mov != null && mov.typeOfHit == MovingObjectPosition.MovingObjectType.ENTITY;
    }

    private void switchToBest(EntityPlayerSP player, ToDoubleFunction<ItemStack> scorer) {
        int currentSlot = player.inventory.currentItem;
        double currentScore = scorer.applyAsDouble(player.inventory.mainInventory[currentSlot]);
        int bestSlot = currentSlot;
        double bestScore = currentScore;
        for (int slot = 0; slot < 9; slot++) {
            double score = scorer.applyAsDouble(player.inventory.mainInventory[slot]);
            if (score > bestScore) {
                bestScore = score;
                bestSlot = slot;
            }
        }
        if (bestSlot != currentSlot) { // strictly better only, avoids churning the held item
            SlotGuard.getInstance().request(this, bestSlot);
        }
    }

    private void stop() {
        SlotGuard guard = SlotGuard.getInstance();
        if (!guard.isHeldBy(this)) {
            return;
        }
        EntityPlayerSP player = Minecraft.getMinecraft().thePlayer;
        if (switchBack.get() && (player == null || !player.isUsingItem())) {
            guard.release(this);
        } else {
            guard.relinquish(this); // restoring mid sword-block would cancel the block
        }
    }
}
