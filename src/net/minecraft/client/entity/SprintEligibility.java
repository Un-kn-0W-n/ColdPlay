package net.minecraft.client.entity;

import net.minecraft.potion.Potion;

/**
 * Vanilla sprint-start and sprint-stop predicates, extracted so client features can ask the exact
 * same questions as {@link EntityPlayerSP#onLivingUpdate()} without maintaining a parallel copy.
 */
public final class SprintEligibility
{
    public static final float FORWARD_THRESHOLD = 0.8F;

    private SprintEligibility()
    {
    }

    /** Food/flying resource gate shared by sprint start and sustain. */
    public static boolean hasEnergy(EntityPlayerSP player)
    {
        return player.getFoodStats().getFoodLevel() > 6.0F || player.capabilities.allowFlying;
    }

    /** Common current-input gate shared by the double-tap and sprint-key start branches. */
    public static boolean canStart(EntityPlayerSP player, float forward)
    {
        return forward >= FORWARD_THRESHOLD
                && hasEnergy(player)
                && !player.isUsingItem()
                && !coldplay.broker.SwordBlock.getInstance().slowedThisTick()
                && !player.isPotionActive(Potion.blindness);
    }

    /** Exact vanilla double-tap branch, including the previous-tick sneak/forward edge checks. */
    public static boolean canStartByDoubleTap(EntityPlayerSP player, boolean wasSneaking,
                                               boolean wasMovingForward, float forward)
    {
        return player.onGround
                && !wasSneaking
                && !wasMovingForward
                && !player.isSprinting()
                && canStart(player, forward);
    }

    /** Exact vanilla sprint-key branch. */
    public static boolean canStartByKey(EntityPlayerSP player, float forward, boolean sprintKeyDown)
    {
        return !player.isSprinting() && sprintKeyDown && canStart(player, forward);
    }

    /** Exact vanilla sustain cancellation gate. */
    public static boolean shouldStop(EntityPlayerSP player, float forward)
    {
        return forward < FORWARD_THRESHOLD || player.isCollidedHorizontally || !hasEnergy(player);
    }
}
