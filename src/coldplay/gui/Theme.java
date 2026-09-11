package coldplay.gui;

import coldplay.util.Animation;
import coldplay.util.RenderUtil;
import net.minecraft.util.MathHelper;

/**
 * Shared GUI colors and metrics. FROST marks state; BODY must be filled once per pixel to avoid
 * translucent bands. Hover uses HOVER_LIFT; small cells may layer their own fill.
 */
public final class Theme {

    // Colors are ARGB.
    public static final int BODY       = 0xF0101014;
    public static final int WELL       = 0xFF16181D; // input wells: checkbox, tracks, cells, fields
    public static final int CONTOUR    = 0xFFB9B9C2;
    public static final int FROST      = 0xFF84D2E3;
    public static final int TEXT       = 0xFFFFFFFF;
    public static final int TEXT_DIM   = 0xFFB4B4BE; // values, suffixes, idle glyphs
    public static final int TEXT_MUTE  = 0xFF77777F; // section headers
    public static final int SEP        = 0xFF232329; // hairlines: row dividers, header rule, idle frames
    public static final int DANGER     = 0xFFB05050; // destructive actions / error feedback
    public static final int DIM_SCREEN = 0x40000000; // whole-screen dim behind the GUI
    public static final int TOOLTIP_BG = 0xF8101014; // BODY at higher alpha so text stays readable
    public static final int HOVER_LIFT = 0x14FFFFFF; // translucent white overlay = row hover

    public static final int CONTOUR_PX = 1;
    public static final int TICK_PX = 2;    // enabled-row and dock-seam ticks

    /** Exponential-approach speed for the two GUI wipes (~190ms to 95%). */
    public static final double WIPE_SPEED = 16.0;
    /** Compile-time kill switch for GUI motion; {@link #step} snaps instead of easing when false. */
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

    /** Lightens an opaque ARGB color by {@code add} per channel — hover states on opaque fills. */
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

    /** Scales the alpha channel of {@code argb} by {@code mult}; a 0 alpha is treated as opaque. */
    public static int applyAlpha(int argb, float mult) {
        int alpha = argb >>> 24;
        if (alpha == 0) {
            alpha = 255;
        }
        return withAlpha(argb, Math.round(alpha * mult));
    }
}
