package net.minecraft.entity.ai.attributes;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** Vanilla attribute modifier evaluation (operations 0, 1, 2), shared by live attributes and item previews. */
public final class AttributeModifierMath
{
    private AttributeModifierMath()
    {
    }

    public static double evaluate(IAttribute attribute, double baseValue,
                                  Collection<? extends AttributeModifier> modifiers)
    {
        List<AttributeModifier> additions = new ArrayList<AttributeModifier>();
        List<AttributeModifier> baseMultipliers = new ArrayList<AttributeModifier>();
        List<AttributeModifier> totalMultipliers = new ArrayList<AttributeModifier>();

        for (AttributeModifier modifier : modifiers)
        {
            switch (modifier.getOperation())
            {
                case 0:
                    additions.add(modifier);
                    break;
                case 1:
                    baseMultipliers.add(modifier);
                    break;
                case 2:
                    totalMultipliers.add(modifier);
                    break;
                default:
                    break;
            }
        }

        return evaluate(attribute, baseValue, additions, baseMultipliers, totalMultipliers);
    }

    /** Same as above for modifiers already grouped by operation; allocation free. */
    public static double evaluate(IAttribute attribute, double baseValue,
                                  Iterable<? extends AttributeModifier> additions,
                                  Iterable<? extends AttributeModifier> baseMultipliers,
                                  Iterable<? extends AttributeModifier> totalMultipliers)
    {
        double afterAdd = baseValue;
        for (AttributeModifier modifier : additions)
        {
            afterAdd += modifier.getAmount();
        }

        double value = afterAdd;
        for (AttributeModifier modifier : baseMultipliers)
        {
            value += afterAdd * modifier.getAmount();
        }

        for (AttributeModifier modifier : totalMultipliers)
        {
            value *= 1.0D + modifier.getAmount();
        }

        return attribute.clampValue(value);
    }
}
