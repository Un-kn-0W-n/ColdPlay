package coldplay.util;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.entity.SprintEligibility;
import net.minecraft.client.settings.GameSettings;

public final class PlayerUtil {
    private PlayerUtil() {}

    public static boolean canVanillaSprint(EntityPlayerSP player, float forward) {
        return SprintEligibility.canStart(player, forward);
    }

    /**
     * World yaw of the walking direction, or the camera yaw when no movement key is held. Reads live
     * key state because {@code movementInput} is stale inside a per-frame handler.
     */
    public static float movementYaw(Minecraft mc, EntityPlayerSP player) {
        GameSettings s = mc.gameSettings;
        float forward = (s.keyBindForward.isKeyDown() ? 1.0F : 0.0F) - (s.keyBindBack.isKeyDown() ? 1.0F : 0.0F);
        float strafe = (s.keyBindLeft.isKeyDown() ? 1.0F : 0.0F) - (s.keyBindRight.isKeyDown() ? 1.0F : 0.0F);
        if (forward == 0.0F && strafe == 0.0F) {
            return player.rotationYaw;
        }
        return player.rotationYaw + (float) Math.toDegrees(Math.atan2(-strafe, forward));
    }

    public static boolean anyMoveKeyDown(Minecraft mc) {
        GameSettings s = mc.gameSettings;
        return s.keyBindForward.isKeyDown() || s.keyBindBack.isKeyDown()
                || s.keyBindLeft.isKeyDown() || s.keyBindRight.isKeyDown();
    }

    /** Unit {@code {x, z}} heading for a yaw in degrees. */
    public static double[] headingVec(float yawDeg) {
        double rad = Math.toRadians(yawDeg);
        return new double[] {-Math.sin(rad), Math.cos(rad)};
    }
}
