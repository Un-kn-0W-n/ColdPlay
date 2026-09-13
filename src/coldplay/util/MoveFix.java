package coldplay.util;

import net.minecraft.util.MathHelper;
import net.minecraft.util.MovementInput;

/**
 * Remaps camera-relative WASD into the server-yaw frame, snapping to the nearest of eight
 * discrete impulses.
 */
public final class MoveFix {
    private MoveFix() {}

    private static final float HYSTERESIS = 0.06F; // cosine margin, stops bucket flapping

    private static int prevStrafeSign, prevForwardSign;

    /** Call on rotation-owner changes so the new owner does not inherit the old impulse. */
    public static void resetHysteresis() {
        prevStrafeSign = 0;
        prevForwardSign = 0;
    }

    /**
     * Called each tick from {@code EntityPlayerSP.onLivingUpdate}. Remaps {@code input} in place when
     * the move yaw differs from the camera yaw, otherwise resets the hysteresis.
     */
    public static void apply(final MovementInput input, final float realYaw, final float moveYaw,
                             final boolean active) {
        if (!active || moveYaw == realYaw) {
            prevStrafeSign = 0;
            prevForwardSign = 0;
            return;
        }
        final float[] move = remap(input.moveStrafe, input.moveForward, realYaw, moveYaw,
                prevStrafeSign, prevForwardSign);
        input.moveStrafe = move[0];
        input.moveForward = move[1];
        prevStrafeSign = (int) Math.signum(move[0]);
        prevForwardSign = (int) Math.signum(move[1]);
    }

    /**
     * Snaps (strafe, forward) to the discrete impulse whose world direction under {@code serverYaw}
     * best matches the intended direction under {@code realYaw}, keeping the magnitude. The previous
     * impulse is kept unless a challenger wins by more than {@link #HYSTERESIS}.
     */
    private static float[] remap(final float strafe, final float forward,
                                 final float realYaw, final float serverYaw,
                                 final int prevS, final int prevF) {
        final float mag = Math.max(Math.abs(strafe), Math.abs(forward));
        if (strafe == 0.0F && forward == 0.0F) {
            return new float[] { strafe, forward };
        }

        // Intended world direction = WASD vector rotated by the real (camera) yaw.
        final float rr = realYaw * (float) Math.PI / 180.0F;
        final float ts = MathHelper.sin(rr);
        final float tc = MathHelper.cos(rr);
        float tdx = strafe * tc - forward * ts;
        float tdz = forward * tc + strafe * ts;
        final float tl = MathHelper.sqrt_float(tdx * tdx + tdz * tdz);
        tdx /= tl;
        tdz /= tl;

        // Score every discrete (strafe, forward) combo by its world direction under the server yaw.
        final float sr = serverYaw * (float) Math.PI / 180.0F;
        final float ss = MathHelper.sin(sr);
        final float sc = MathHelper.cos(sr);

        float bestS = strafe;
        float bestF = forward;
        float bestDot = -Float.MAX_VALUE;
        for (int s = -1; s <= 1; s++) {
            for (int f = -1; f <= 1; f++) {
                if (s == 0 && f == 0) {
                    continue;
                }
                final float cdx = s * sc - f * ss;
                final float cdz = f * sc + s * ss;
                final float cl = MathHelper.sqrt_float(cdx * cdx + cdz * cdz);
                final float dot = (tdx * cdx + tdz * cdz) / cl;
                if (dot > bestDot) {
                    bestDot = dot;
                    bestS = s;
                    bestF = f;
                }
            }
        }
        // Retain a near-equal previous impulse to avoid toggling vanilla's sprint gate at boundaries.
        if (prevS != 0 || prevF != 0) {
            final float pdx = prevS * sc - prevF * ss;
            final float pdz = prevF * sc + prevS * ss;
            final float pl = MathHelper.sqrt_float(pdx * pdx + pdz * pdz);
            final float prevDot = (tdx * pdx + tdz * pdz) / pl;
            if (bestDot - prevDot < HYSTERESIS) {
                bestS = prevS;
                bestF = prevF;
            }
        }
        return new float[] { bestS * mag, bestF * mag };
    }
}
