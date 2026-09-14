package coldplay.setting;

import com.google.gson.JsonElement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** On/off grid of wall columns around a bed, in bed-local coordinates (foot (0,0), head (0,1)). */
public class BedGridSetting extends Setting<Void> {

    public static final int MAX_RING = 3; // keep equal to BedProtection.MAX_LAYER

    public static final class Cell implements BooleanGridEntry {
        public final int lx; // across the bed
        public final int lz; // along the bed, foot 0, head 1
        public final int ring; // 1..MAX_RING, also the column height
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
                    continue; // ring 0 is the bed itself
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

    /** Chebyshev distance from the bed's 1x2 footprint. */
    public static int ringOf(int lx, int lz) {
        int outLx = Math.abs(lx);
        int outLz = Math.max(0, Math.max(-lz, lz - 1));
        return Math.max(outLx, outLz);
    }

    public List<Cell> getCells() {
        return cells;
    }

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
