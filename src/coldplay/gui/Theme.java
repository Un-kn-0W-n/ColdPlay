package coldplay.gui;

import coldplay.util.Animation;
import coldplay.util.RenderUtil;
import net.minecraft.util.ColorMath;
import net.minecraft.util.MathHelper;

/** Shared GUI colors and metrics. */
public final class Theme {

    // Colors are ARGB.
    public static final int BODY       = 0xF0101014; // translucent, fill once per pixel
    public static final int WELL       = 0xFF16181D;
    public static final int CONTOUR    = 0xFFB9B9C2;
    public static final int FROST      = 0xFF84D2E3;
    public static final int TEXT       = 0xFFFFFFFF;
    public static final int TEXT_DIM   = 0xFFB4B4BE;
    public static final int TEXT_MUTE  = 0xFF77777F;
    public static final int SEP        = 0xFF232329;
    public static final int DANGER     = 0xFFB05050;
    public static final int DIM_SCREEN = 0x40000000;
    public static final int TOOLTIP_BG = 0xF8101014;
    public static final int HOVER_LIFT = 0x14FFFFFF;

    public static final int CONTOUR_PX = 1;
    public static final int TICK_PX = 2;

    public static final double WIPE_SPEED = 16.0; // ~190ms to 95%
    public static final boolean ANIMATIONS = true;

    private Theme() {
    }

    public static void contour(int x, int y, int width, int height) {
        RenderUtil.outline(x, y, x + width, y + height, CONTOUR_PX, CONTOUR);
    }

    public static double step(Animation anim, double target) {
        if (!ANIMATIONS) {
            anim.set(target);
            return target;
        }
        return anim.update(target);
    }

    public static int lighten(int argb, int add) {
        int r = MathHelper.clamp_int((argb >> 16 & 0xFF) + add, 0, 255);
        int g = MathHelper.clamp_int((argb >> 8 & 0xFF) + add, 0, 255);
        int b = MathHelper.clamp_int((argb & 0xFF) + add, 0, 255);
        return (argb & 0xFF000000) | r << 16 | g << 8 | b;
    }

    public static int withAlpha(int argb, int alpha) {
        int clamped = MathHelper.clamp_int(alpha, 0, 255);
        return (clamped << 24) | (argb & 0x00FFFFFF);
    }

    /** Green at 1, through yellow and orange, to red at 0. */
    public static int healthColor(float fraction) {
        if (fraction > 0.55F) {
            return ColorMath.lerpArgb(0xFFEFD25A, 0xFF7EE08E, (fraction - 0.55F) / 0.45F);
        }
        if (fraction > 0.3F) {
            return ColorMath.lerpArgb(0xFFF59A4C, 0xFFEFD25A, (fraction - 0.3F) / 0.25F);
        }
        return ColorMath.lerpArgb(0xFFF0505A, 0xFFF59A4C, fraction / 0.3F);
    }

    /** Scales alpha; a 0 alpha counts as opaque. */
    public static int applyAlpha(int argb, float mult) {
        int alpha = argb >>> 24;
        if (alpha == 0) {
            alpha = 255;
        }
        return withAlpha(argb, Math.round(alpha * mult));
    }
}
