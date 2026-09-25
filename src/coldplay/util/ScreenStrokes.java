package coldplay.util;

import coldplay.gui.GlassShader;
import coldplay.gui.Theme;

/** HUD-pass strokes over framebuffer px from ScreenProjector; divide by the scale factor to get GUI px. */
public final class ScreenStrokes {
    // stacked strokes that stand in for the designs' blurred glow, GUI px
    private static final float[] GLOW_W = {14.0F, 10.0F, 6.0F, 3.0F};
    private static final float[] GLOW_A = {0.08F, 0.13F, 0.2F, 0.29F};

    private ScreenStrokes() {
    }

    /** Segments stored as ax, ay, bx, by. */
    public static void lines(float[] s, int n, float scale, float width, int color) {
        for (int i = 0; i < n; i += 4) {
            GlassShader.line(s[i] / scale, s[i + 1] / scale, s[i + 2] / scale, s[i + 3] / scale, width, color);
        }
    }

    /** A closed outline of x,y pairs, joined in threes so a translucent stroke only doubles at every other point. */
    public static void loop(float[] p, int n, float scale, float width, int color) {
        for (int i = 0; i < n; i += 2) {
            int b = (i + 1) % n;
            if (i + 1 == n) {
                GlassShader.line(p[i * 2] / scale, p[i * 2 + 1] / scale, p[0] / scale, p[1] / scale, width, color);
            } else {
                int c = (i + 2) % n;
                GlassShader.polyline(p[i * 2] / scale, p[i * 2 + 1] / scale, p[b * 2] / scale, p[b * 2 + 1] / scale,
                        p[c * 2] / scale, p[c * 2 + 1] / scale, width, color);
            }
        }
    }

    /** Glow under lines; spread scales the stroke widths. */
    public static void glowLines(float[] s, int n, float scale, float spread, int rgb, float alpha) {
        for (int i = 0; i < GLOW_W.length; i++) {
            lines(s, n, scale, GLOW_W[i] * spread, Theme.withAlpha(rgb, Math.round(255 * GLOW_A[i] * alpha)));
        }
    }

    public static void glowLoop(float[] p, int n, float scale, float spread, int rgb, float alpha) {
        for (int i = 0; i < GLOW_W.length; i++) {
            loop(p, n, scale, GLOW_W[i] * spread, Theme.withAlpha(rgb, Math.round(255 * GLOW_A[i] * alpha)));
        }
    }
}
