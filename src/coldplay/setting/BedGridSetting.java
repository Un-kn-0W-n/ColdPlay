package coldplay.setting;

import com.google.gson.JsonElement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Wall-column selection in bed-local coordinates: foot (0,0), head (0,1), and three Chebyshev rings.
 * BedProtection rotates these coordinates to the bed's facing. Persists {@code "lx,lz" -> boolean}
 * per cell; there is no scalar value.
 */
public class BedGridSetting extends Setting<Void> {

    /** Deepest ring — Chebyshev distance from the bed's 1x2 footprint. Mirrors BedProtection.MAX_LAYER. */
    public static final int MAX_RING = 3;

    public static final class Cell implements BooleanGridEntry {
        /** Bed-local, along the bed's short axis (right = facing.rotateY()). */
        public final int lx;
        /** Bed-local, along the bed's long axis (foot = 0, head = 1, = facing). */
        public final int lz;
        /** Chebyshev distance from the footprint, 1..MAX_RING — also the walled column's height. */
        public final int ring;
        private boolean enabled;

        Cell(int lx, int lz, int ring, boolean enabled) {
            this.lx = lx;
            this.lz = lz;
            this.ring = ring;
            this.enabled = enabled;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public void toggle() {
            enabled = !enabled;
        }

        /** Stable config key (also unique within the grid). */
        public String key() {
            return lx + "," + lz;
        }

        @Override
        public String persistenceKey() {
            return key();
        }
    }

    private final List<Cell> cells = new ArrayList<>();
    private final Map<Long, Cell> byPos = new HashMap<>();

    public BedGridSetting(String name) {
        super(name, null);
        for (int lz = -MAX_RING; lz <= 1 + MAX_RING; lz++) {
            for (int lx = -MAX_RING; lx <= MAX_RING; lx++) {
                int ring = ringOf(lx, lz);
                if (ring < 1 || ring > MAX_RING) {
                    continue; // the bed itself (ring 0) — shown but never clickable
                }
                Cell cell = new Cell(lx, lz, ring, ring == 1);
                cells.add(cell);
                byPos.put(pack(lx, lz), cell);
            }
        }
    }

    @Override
    public BedGridSetting describe(String description) {
        super.describe(description);
        return this;
    }

    /** Chebyshev distance of a bed-local column from the 1x2 footprint (lx in {0}, lz in {0,1}). */
    public static int ringOf(int lx, int lz) {
        int outLx = Math.abs(lx);
        int outLz = Math.max(0, Math.max(-lz, lz - 1));
        return Math.max(outLx, outLz);
    }

    public List<Cell> getCells() {
        return cells;
    }

    /** Whether the column at bed-local {@code (lx, lz)} is armed (false for the bed / off-grid keys). */
    public boolean isEnabled(int lx, int lz) {
        Cell cell = byPos.get(pack(lx, lz));
        return cell != null && cell.isEnabled();
    }

    private static long pack(int lx, int lz) {
        return ((long) lx << 32) ^ (lz & 0xFFFFFFFFL);
    }

    @Override
    public JsonElement toJson() {
        return BooleanGridCodec.encode(cells);
    }

    @Override
    public void fromJson(JsonElement json) {
        BooleanGridCodec.decode(json, cells);
    }
}
