package coldplay.hud;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Shared HUD layout/edit state, independent of modules, configuration, and GUI classes. */
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
    private boolean editing;
    /** Scaled screen size the stored anchors are expressed in; 0 until the first render or config load. */
    private int layoutWidth;
    private int layoutHeight;

    public Position getOrCreate(String name, int defaultX, int defaultY) {
        Position position = positions.get(name);
        if (position == null) {
            position = new Position(defaultX, defaultY);
            positions.put(name, position);
        }
        return position;
    }

    /**
     * Projects anchors from their saved scaled-GUI pixel dimensions without overwriting them.
     * Resizing can clamp the projection; persisting it would permanently lose the original layout.
     */
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
            stored.x = clamp(stored.x, screenWidth); // same space: keep a legacy anchor reachable
            stored.y = clamp(stored.y, screenHeight);
            return stored;
        }
        return new Position(clamp(reanchor(stored.x, layoutWidth, screenWidth), screenWidth),
                clamp(reanchor(stored.y, layoutHeight, screenHeight), screenHeight));
    }

    /**
     * Adopts the edit screen's dimensions once; render-time resizing must remain a read-only projection.
     */
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
