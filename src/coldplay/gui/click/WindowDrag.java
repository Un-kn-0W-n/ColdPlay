package coldplay.gui.click;

import net.minecraft.util.MathHelper;

/** Shared drag offset and screen clamping; callers own window positions. */
class WindowDrag {
    private boolean dragging;
    private int offsetX;
    private int offsetY;

    void begin(int mouseX, int mouseY, int x, int y) {
        dragging = true;
        offsetX = mouseX - x;
        offsetY = mouseY - y;
    }

    boolean isDragging() {
        return dragging;
    }

    /**
     * Returns null when idle. Clamp extents describe the visible window portion, so a collapsed
     * panel supplies only its header height.
     */
    int[] update(int mouseX, int mouseY, int clampWidth, int clampHeight, int screenWidth, int screenHeight) {
        if (!dragging) {
            return null;
        }
        int newX = MathHelper.clamp_int(mouseX - offsetX, 0, Math.max(0, screenWidth - clampWidth));
        int newY = MathHelper.clamp_int(mouseY - offsetY, 0, Math.max(0, screenHeight - clampHeight));
        return new int[]{newX, newY};
    }

    void end() {
        dragging = false;
    }
}
