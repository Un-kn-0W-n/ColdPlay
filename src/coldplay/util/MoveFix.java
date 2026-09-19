package coldplay.util;

import net.minecraft.util.MathHelper;
import net.minecraft.util.MovementInput;

public final class MoveFix {
    private MoveFix() {}

    private static final byte[][] BUCKETS = {
            { 0,  1}, { 1,  1}, { 1,  0}, { 1, -1},
            { 0, -1}, {-1, -1}, {-1,  0}, {-1,  1},
    };

    private static final int[] INDEX_BY_SIGNS = {
            //  f=-1  f=0  f=+1
            5, 6, 7, // strafe = -1
            4, -1, 0, // strafe =  0
            3, 2, 1, // strafe = +1
    };

    private static final float BUCKET_DEG = 45.0F;

    private static final float HYSTERESIS_DEG = 4.49F;

    private static boolean hasPrevious;
    private static int previousShift;
    private static float previousDelta;

    public static void apply(final MovementInput input, final float realYaw, final float moveYaw,
                             final boolean active) {
        if (!active || moveYaw == realYaw) {
            hasPrevious = false;
            return;
        }
        final float strafe = input.moveStrafe;
        final float forward = input.moveForward;
        if (strafe == 0.0F && forward == 0.0F) {
            hasPrevious = false;
            return;
        }

        final float delta = MathHelper.wrapAngleTo180_float(moveYaw - realYaw);
        int shift = Math.round(delta / BUCKET_DEG);
        if (hasPrevious
                && Math.abs(MathHelper.wrapAngleTo180_float(delta - previousDelta)) < BUCKET_DEG
                && Math.abs(MathHelper.wrapAngleTo180_float(delta - previousShift * BUCKET_DEG))
                        <= BUCKET_DEG * 0.5F + HYSTERESIS_DEG) {
            shift = previousShift;
        }

        final byte[] out = BUCKETS[(indexOf(strafe, forward) + shift) & 7];

        final float mag = Math.max(Math.abs(strafe), Math.abs(forward));
        input.moveStrafe = out[0] * mag;
        input.moveForward = out[1] * mag;

        previousDelta = delta;
        previousShift = shift;
        hasPrevious = true;
    }

    private static int indexOf(final float strafe, final float forward) {
        final int s = (int) Math.signum(strafe);
        final int f = (int) Math.signum(forward);
        return INDEX_BY_SIGNS[(s + 1) * 3 + (f + 1)];
    }
}
