package coldplay.util;

import net.minecraft.util.MathHelper;
import net.minecraft.util.MovementInput;

/**
 * Maps camera-relative WASD to the nearest of eight impulses under server yaw. Discrete inputs
 * keep movement reproducible from outgoing rotation, at the cost of directional precision.
 */
public final class MoveFix {
    private MoveFix() {}

    /** Cosine margin (~a few degrees) a challenger impulse must beat the previous one by to switch
     *  buckets. Kills per-tick flapping at the ±67.5° discretization boundary (which would otherwise
     *  toggle sprint every tick). */
    private static final float HYSTERESIS = 0.06F;

    /**
     * Sign of the previous tick's chosen impulse (strafe/forward), fed back into {@link #remap} for
     * hysteresis so the movement bucket doesn't flap tick-to-tick near a boundary. 0 = no override.
     * Local-player state, owned here so the vanilla hook site stays a single call.
     */
    private static int prevS, prevF;

    /**
     * Reset on rotation-owner changes: a live handoff does not pass through the inactive reset in
     * {@link #apply}, and the new owner must not inherit the old impulse.
     */
    public static void resetHysteresis() {
        prevS = 0;
        prevF = 0;
    }

    /**
     * Per-tick entry point, called from {@code EntityPlayerSP.onLivingUpdate} after the EventStrafe
     * hook and before vanilla's sprint gate. When a silent-rotation module published a move-yaw
     * different from the camera yaw, remaps {@code input}'s WASD in place into the server-yaw frame;
     * otherwise (or when {@code active} is false, e.g. riding) just resets the hysteresis state.
     */
    public static void apply(final MovementInput input, final float realYaw, final float moveYaw,
                             final boolean active) {
        if (!active || moveYaw == realYaw) {
            prevS = 0;
            prevF = 0;
            return;
        }
        final float[] move = remap(input.moveStrafe, input.moveForward, realYaw, moveYaw, prevS, prevF);
        input.moveStrafe = move[0];
        input.moveForward = move[1];
        prevS = (int) Math.signum(move[0]);
        prevF = (int) Math.signum(move[1]);
    }

    /**
     * @param strafe    raw strafe input (already past sneak/item-use scaling)
     * @param forward   raw forward input
     * @param realYaw   the camera yaw the player actually intends to move relative to
     * @param serverYaw the spoofed yaw the look packet carries (the physics basis)
     * @param prevS     sign of last tick's chosen strafe impulse ({@code -1,0,1}); 0 = none, for hysteresis
     * @param prevF     sign of last tick's chosen forward impulse ({@code -1,0,1}); 0 = none, for hysteresis
     * @return {@code {strafe, forward}} snapped to the discrete impulse whose world direction under
     *         {@code serverYaw} best matches the intended direction under {@code realYaw}; magnitude
     *         preserved so sub-speed inputs (sneak ±0.3, item-use ±0.2) carry through. The previous
     *         impulse is kept unless a challenger wins by more than {@link #HYSTERESIS} (anti-flap).
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
                if (s == 0 && f == 0) continue;
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
