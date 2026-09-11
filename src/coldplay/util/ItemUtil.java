package coldplay.util;

import net.minecraft.block.Block;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.EnumCreatureAttribute;
import net.minecraft.item.ItemStack;

public final class ItemUtil {

    private ItemUtil() {
    }

    /**
     * Base attack damage plus enchantment contribution against a generic target. Null stacks score 0.
     */
    public static double meleeDamage(ItemStack stack) {
        if (stack == null) {
            return 0.0;
        }
        // Generic (UNDEFINED) target so Sharpness counts; Smite/Bane only add against their creature type.
        return stack.getAttackDamageContribution(EnumCreatureAttribute.UNDEFINED);
    }

    /** Vanilla's Efficiency dig-speed bonus term ({@code level² + 1}; EntityPlayer.getToolDigEfficiency), 0 when unenchanted. */
    public static float efficiencyBonus(ItemStack stack) {
        return EnchantmentHelper.getEfficiencyDigBonus(stack);
    }

    /**
     * Harvest capability outranks speed. Bare hands score the non-tool baseline 1.0 so junk items
     * tie instead of triggering a pointless slot switch.
     */
    public static double miningScore(ItemStack stack, Block block) {
        if (stack == null) {
            return 1.0;
        }
        float strength = stack.getStrVsBlock(block); // 1.0 when the item is not an effective tool
        if (strength > 1.0F) {
            strength += efficiencyBonus(stack);
        }
        return (stack.canHarvestBlock(block) ? 1000.0 : 0.0) + strength;
    }
}
