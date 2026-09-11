package coldplay.util;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.entity.SprintEligibility;
import net.minecraft.client.settings.GameSettings;

public final class PlayerUtil {
    private PlayerUtil() {}

    /**
     * The common current-input gate used by both vanilla sprint-start branches. {@code forward} is
     * passed in because callers read the freshly polled EventStrafe input.
     */
    public static boolean canVanillaSprint(EntityPlayerSP player, float forward) {
        return SprintEligibility.canStart(player, forward);
    }

    /**
     * Absolute world yaw of the direction the player is walking (camera yaw plus the WASD angle), or
     * the camera yaw itself when no movement key is held. Read from live key state rather than
     * {@code movementInput}, which is tick-scoped and would be stale inside a per-frame handler.
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

    /** Any WASD key held, from live key state (see {@link #movementYaw}). */
    public static boolean anyMoveKeyDown(Minecraft mc) {
        GameSettings s = mc.gameSettings;
        return s.keyBindForward.isKeyDown() || s.keyBindBack.isKeyDown()
                || s.keyBindLeft.isKeyDown() || s.keyBindRight.isKeyDown();
    }

    /** Unit world-space heading {@code {x, z}} for a yaw in degrees (Minecraft's −sin/cos convention). */
    public static double[] headingVec(float yawDeg) {
        double rad = Math.toRadians(yawDeg);
        return new double[] {-Math.sin(rad), Math.cos(rad)};
    }
}
