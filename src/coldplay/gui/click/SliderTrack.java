package coldplay.gui.click;

import coldplay.gui.Theme;
import coldplay.util.RenderUtil;
import net.minecraft.util.MathHelper;

/** Slider track shared by number and range settings. */
final class SliderTrack {
    private final int x;
    private final int y;
    private final int width;

    SliderTrack(int x, int y, int width) {
        this.x = x;
        this.y = y;
        this.width = Math.max(0, width);
    }

    void drawSingle(double value, double min, double max) {
        drawBase();
        int thumb = position(value, min, max);
        RenderUtil.rect(x, y, Math.max(0, thumb - x), 3, Theme.FROST);
        RenderUtil.rect(thumb - 1, y - 2, 3, 7, Theme.FROST);
    }

    void drawRange(double lo, double hi, double min, double max) {
        drawBase();
        if (max <= min) {
            return;
        }
        int loX = position(lo, min, max);
        int hiX = position(hi, min, max);
        RenderUtil.rect(loX, y, Math.max(1, hiX - loX), 3, Theme.FROST);
        RenderUtil.rect(loX - 1, y - 1, 3, 5, Theme.FROST);
        RenderUtil.rect(hiX - 1, y - 1, 3, 5, Theme.FROST);
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
        RenderUtil.rect(x, y, width, 3, Theme.WELL);
    }

    private int position(double value, double min, double max) {
        double span = max - min;
        double ratio = span > 0.0D
                ? MathHelper.clamp_double((value - min) / span, 0.0D, 1.0D)
                : 0.0D;
        return x + (int) (width * ratio);
    }
}
