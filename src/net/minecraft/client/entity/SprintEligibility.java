package net.minecraft.client.entity;

import net.minecraft.potion.Potion;

/** Vanilla's sprint start and stop checks, shared with EntityPlayerSP.onLivingUpdate. */
public final class SprintEligibility {
    public static final float FORWARD_THRESHOLD = 0.8F;

    private SprintEligibility() {
    }

    public static boolean hasEnergy(EntityPlayerSP player) {
        return player.getFoodStats().getFoodLevel() > 6.0F || player.capabilities.allowFlying;
    }

    public static boolean canStart(EntityPlayerSP player, float forward) {
        return forward >= FORWARD_THRESHOLD
                && hasEnergy(player)
                && !player.isUsingItem()
                && !coldplay.broker.SwordBlock.getInstance().slowedThisTick()
                && !player.isPotionActive(Potion.blindness);
    }

    public static boolean canStartByDoubleTap(EntityPlayerSP player, boolean wasSneaking,
                                               boolean wasMovingForward, float forward) {
        return player.onGround
                && !wasSneaking
                && !wasMovingForward
                && !player.isSprinting()
                && canStart(player, forward);
    }

    public static boolean canStartByKey(EntityPlayerSP player, float forward, boolean sprintKeyDown) {
        return !player.isSprinting() && sprintKeyDown && canStart(player, forward);
    }

    public static boolean shouldStop(EntityPlayerSP player, float forward) {
        return forward < FORWARD_THRESHOLD || player.isCollidedHorizontally || !hasEnergy(player);
    }
}
