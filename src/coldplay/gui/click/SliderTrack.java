package coldplay.gui.click;

import coldplay.gui.GlassShader;
import net.minecraft.util.MathHelper;

/** Slider track shared by number and range settings. */
final class SliderTrack {
    private final int x;
    private final float y;
    private final int width;
    private final Skin skin;
    private final float height;

    SliderTrack(int x, float y, int width, Skin skin) {
        this.x = x;
        this.y = y;
        this.width = Math.max(0, width);
        this.skin = skin;
        this.height = skin.milk ? 3.0F : 2.25F;
    }

    void drawSingle(double value, double min, double max) {
        drawBase();
        int thumb = position(value, min, max);
        GlassShader.rect(x, y, thumb - x, height, height / 2.0F, skin.accent, skin.accent);
        knob(thumb);
    }

    void drawRange(double lo, double hi, double min, double max) {
        drawBase();
        if (max <= min) {
            return;
        }
        int loX = position(lo, min, max);
        int hiX = position(hi, min, max);
        GlassShader.rect(loX, y, Math.max(1, hiX - loX), height, height / 2.0F, skin.accent, skin.accent);
        knob(loX);
        knob(hiX);
    }

    double valueAt(int mouseX, double min, double max) {
        if (width <= 0 || max <= min) {
            return min;
        }
        double ratio = MathHelper.clamp_double((mouseX - x) / (double) width, 0.0D, 1.0D);
        return min + ratio * (max - min);
    }

    int nearestHandle(double lo, double hi, double min, double max, int mouseX) {
        int loX = position(lo, min, max);
        int hiX = position(hi, min, max);
        if (hiX - loX < 0.5D) {
            return mouseX >= hiX ? 1 : 0;
        }
        return Math.abs(mouseX - loX) <= Math.abs(mouseX - hiX) ? 0 : 1;
    }

    private void drawBase() {
        GlassShader.rect(x, y, width, height, height / 2.0F, skin.well, skin.well);
    }

    /** Smoke: white dot in a faint accent ring. Milk: a larger white knob with a drop shadow. */
    private void knob(int cx) {
        float cy = y + height / 2.0F;
        if (skin.milk) {
            GlassShader.fill(cx - 5.25F, cy - 5.25F, 10.5F, 10.5F, 5.25F, 0xFFFFFFFF, 2.5F, 0.75F, 0.3F);
            return;
        }
        int ring = (skin.accent & 0x00FFFFFF) | 0x59000000;
        GlassShader.rect(cx - 4.875F, cy - 4.875F, 9.75F, 9.75F, 4.875F, ring, ring);
        GlassShader.rect(cx - 3.375F, cy - 3.375F, 6.75F, 6.75F, 3.375F, 0xFFFFFFFF, 0xFFFFFFFF);
    }

    private int position(double value, double min, double max) {
        double span = max - min;
        double ratio = span > 0.0D
                ? MathHelper.clamp_double((value - min) / span, 0.0D, 1.0D)
                : 0.0D;
        return x + (int) (width * ratio);
    }
}
