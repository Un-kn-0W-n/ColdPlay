package coldplay.hud;

import coldplay.setting.NumberSetting;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** HUD element anchors and edit-mode state. */
public final class HudState {
    public static final class Position {
        public int x;
        public int y;

        public Position(int x, int y) {
            this.x = x;
            this.y = y;
        }
    }

    private final Map<String, Position> positions = new LinkedHashMap<String, Position>();
    private final Map<String, int[]> boxes = new LinkedHashMap<String, int[]>();
    private final Map<String, NumberSetting> scales = new LinkedHashMap<String, NumberSetting>();
    private boolean editing;
    private int layoutWidth; // 0 until the first render or config load
    private int layoutHeight;

    public Position getOrCreate(String name, int defaultX, int defaultY) {
        Position position = positions.get(name);
        if (position == null) {
            position = new Position(defaultX, defaultY);
            positions.put(name, position);
        }
        return position;
    }

    /** Projects the stored anchor into the current screen size without overwriting it. */
    public Position getOrCreate(String name, int defaultX, int defaultY,
                                int screenWidth, int screenHeight) {
        if (layoutWidth <= 0 || layoutHeight <= 0) {
            layoutWidth = screenWidth;
            layoutHeight = screenHeight;
        }
        // Defaults arrive in screen space; store them in layout space so they project back correctly.
        Position stored = getOrCreate(name, reanchor(defaultX, screenWidth, layoutWidth),
                reanchor(defaultY, screenHeight, layoutHeight));
        if (screenWidth == layoutWidth && screenHeight == layoutHeight) {
            stored.x = clamp(stored.x, screenWidth); // keep legacy anchors on-screen
            stored.y = clamp(stored.y, screenHeight);
            return stored;
        }
        return new Position(clamp(reanchor(stored.x, layoutWidth, screenWidth), screenWidth),
                clamp(reanchor(stored.y, layoutHeight, screenHeight), screenHeight));
    }

    /** Re-expresses every stored anchor in a new layout size. */
    public void rebase(int screenWidth, int screenHeight) {
        if (screenWidth <= 0 || screenHeight <= 0) {
            return;
        }
        if (layoutWidth > 0 && layoutHeight > 0) {
            for (Position stored : positions.values()) {
                stored.x = reanchor(stored.x, layoutWidth, screenWidth);
                stored.y = reanchor(stored.y, layoutHeight, screenHeight);
            }
        }
        layoutWidth = screenWidth;
        layoutHeight = screenHeight;
    }

    private static int clamp(int value, int size) {
        return Math.max(0, Math.min(value, size - 1));
    }

    /** Preserves distance to the nearest edge or center when projecting a scaled-GUI coordinate. */
    public static int reanchor(int value, int oldSize, int newSize) {
        int low = Math.abs(value);
        int centre = Math.abs(value - oldSize / 2);
        int high = Math.abs(value - oldSize);
        if (low <= centre && low <= high) {
            return value;
        }
        if (centre <= high) {
            return newSize / 2 + (value - oldSize / 2);
        }
        return newSize + (value - oldSize);
    }

    public void put(String name, int x, int y) {
        positions.put(name, new Position(x, y));
    }

    public static NumberSetting scaleSetting(String name) {
        return new NumberSetting(name, 1.0, 0.5, 2.0, 0.05)
                .describe("Size multiplier. Drag any corner in Edit GUI.");
    }

    /** Lets the editor resize the element by dragging its corners. */
    public void registerScale(String name, NumberSetting scale) {
        scales.put(name, scale);
    }

    /** Null when the element has no scale to drag. */
    public NumberSetting getScale(String name) {
        return scales.get(name);
    }

    public int getLayoutWidth() {
        return layoutWidth;
    }

    public int getLayoutHeight() {
        return layoutHeight;
    }

    public void setLayoutSize(int width, int height) {
        layoutWidth = width;
        layoutHeight = height;
    }

    public Map<String, Position> getPositions() {
        return Collections.unmodifiableMap(positions);
    }

    public boolean isEditing() {
        return editing;
    }

    public void beginEditing() {
        editing = true;
        boxes.clear();
    }

    public void endEditing() {
        editing = false;
        boxes.clear();
    }

    public void report(String name, int left, int top, int right, int bottom) {
        if (editing) {
            boxes.put(name, new int[]{left, top, right, bottom});
        }
    }

    /** Reports a box drawn under a scale matrix pinned at the pivot. */
    public void report(String name, int left, int top, int right, int bottom,
                       int pivotX, int pivotY, float scale) {
        report(name, scaled(left, pivotX, scale), scaled(top, pivotY, scale),
                scaled(right, pivotX, scale), scaled(bottom, pivotY, scale));
    }

    public static int scaled(int value, int pivot, float scale) {
        return Math.round(pivot + (value - pivot) * scale);
    }

    public Map<String, int[]> getBoxes() {
        return Collections.unmodifiableMap(boxes);
    }

    public int[] getBox(String name) {
        return boxes.get(name);
    }

    public void translateBox(String name, int dx, int dy) {
        int[] box = boxes.get(name);
        if (box != null) {
            box[0] += dx;
            box[1] += dy;
            box[2] += dx;
            box[3] += dy;
        }
    }
}
