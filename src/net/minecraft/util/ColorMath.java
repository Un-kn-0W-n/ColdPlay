package net.minecraft.util;

/** Shared packed-ARGB color arithmetic. */
public final class ColorMath
{
    private ColorMath()
    {
    }

    /**
     * Linearly interpolates every packed ARGB channel. The amount is clamped to [0,1] and channels
     * use vanilla-style truncation so existing GUI color output is preserved exactly.
     */
    public static int lerpArgb(int from, int to, float amount)
    {
        float t = MathHelper.clamp_float(amount, 0.0F, 1.0F);
        int fromA = from >>> 24 & 255;
        int fromR = from >>> 16 & 255;
        int fromG = from >>> 8 & 255;
        int fromB = from & 255;
        int a = MathHelper.clamp_int((int) (fromA + ((to >>> 24 & 255) - fromA) * t), 0, 255);
        int r = MathHelper.clamp_int((int) (fromR + ((to >>> 16 & 255) - fromR) * t), 0, 255);
        int g = MathHelper.clamp_int((int) (fromG + ((to >>> 8 & 255) - fromG) * t), 0, 255);
        int b = MathHelper.clamp_int((int) (fromB + ((to & 255) - fromB) * t), 0, 255);
        return a << 24 | r << 16 | g << 8 | b;
    }
}
