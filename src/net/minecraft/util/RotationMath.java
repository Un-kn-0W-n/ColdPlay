package net.minecraft.util;

/**
 * Stateless rotation helpers shared by entities and callers that need to preview an arbitrary look.
 */
public final class RotationMath
{
    private RotationMath()
    {
    }

    /**
     * Creates the same normalized direction vector vanilla entities use for the supplied pitch/yaw.
     */
    public static Vec3 lookVector(float pitch, float yaw)
    {
        float cosYaw = MathHelper.cos(-yaw * 0.017453292F - (float)Math.PI);
        float sinYaw = MathHelper.sin(-yaw * 0.017453292F - (float)Math.PI);
        float negCosPitch = -MathHelper.cos(-pitch * 0.017453292F);
        float sinPitch = MathHelper.sin(-pitch * 0.017453292F);
        return new Vec3((double)(sinYaw * negCosPitch), (double)sinPitch, (double)(cosYaw * negCosPitch));
    }

    /** Vanilla yaw/pitch from an eye position to a target position. */
    public static float[] anglesTo(double eyeX, double eyeY, double eyeZ,
                                   double targetX, double targetY, double targetZ)
    {
        return new float[] {
                yawTo(eyeX, eyeZ, targetX, targetZ),
                pitchTo(eyeX, eyeY, eyeZ, targetX, targetY, targetZ)
        };
    }

    public static float yawTo(double eyeX, double eyeZ, double targetX, double targetZ)
    {
        return (float)(MathHelper.atan2(targetZ - eyeZ, targetX - eyeX) * 180.0D / Math.PI) - 90.0F;
    }

    public static float pitchTo(double eyeX, double eyeY, double eyeZ,
                                double targetX, double targetY, double targetZ)
    {
        double dx = targetX - eyeX;
        double dz = targetZ - eyeZ;
        double horizontal = MathHelper.sqrt_double(dx * dx + dz * dz);
        return (float)(-(MathHelper.atan2(targetY - eyeY, horizontal) * 180.0D / Math.PI));
    }

    /** Allocation-free Euclidean wrapped yaw/pitch error to a target. */
    public static float angularError(double eyeX, double eyeY, double eyeZ,
                                     double targetX, double targetY, double targetZ,
                                     float currentYaw, float currentPitch)
    {
        float yawDiff = MathHelper.wrapAngleTo180_float(
                yawTo(eyeX, eyeZ, targetX, targetZ) - currentYaw);
        float pitchDiff = pitchTo(eyeX, eyeY, eyeZ, targetX, targetY, targetZ) - currentPitch;
        return MathHelper.sqrt_double(yawDiff * yawDiff + pitchDiff * pitchDiff);
    }
}
