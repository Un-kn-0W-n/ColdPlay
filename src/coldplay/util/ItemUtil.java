package coldplay.util;

import net.minecraft.block.Block;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.EnumCreatureAttribute;
import net.minecraft.item.ItemStack;

public final class ItemUtil {

    private ItemUtil() {
    }

    /** Base attack damage plus enchantment bonus; {@code null} scores 0. */
    public static double meleeDamage(ItemStack stack) {
        if (stack == null) {
            return 0.0;
        }
        // Generic (UNDEFINED) target so Sharpness counts; Smite/Bane only add against their creature type.
        return stack.getAttackDamageContribution(EnumCreatureAttribute.UNDEFINED);
    }

    /** Vanilla's Efficiency dig-speed term, {@code level^2 + 1}, or 0 when unenchanted. */
    public static float efficiencyBonus(ItemStack stack) {
        return EnchantmentHelper.getEfficiencyDigBonus(stack);
    }

    /** Harvest capability outranks speed; bare hands score the baseline 1.0. */
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
