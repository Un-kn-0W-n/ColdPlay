package coldplay.broker;

import coldplay.util.EntityRenderSelectionSnapshot;
import net.minecraft.entity.Entity;

import java.util.Map;

/**
 * Supplies per-entity colours to vanilla's spectator outline pass. The writer must clear the
 * selection on disable because the renderer keeps reading after the module leaves the event bus.
 */
public final class OutlineRegistry {

    private static final OutlineRegistry INSTANCE = new OutlineRegistry();

    public static OutlineRegistry getInstance() {
        return INSTANCE;
    }

    /** entity -> packed 0xRRGGBB; 0 is never stored (it is the "not selected" sentinel, matching colorFor). */
    private final EntityRenderSelectionSnapshot<Integer> selection =
            new EntityRenderSelectionSnapshot<Integer>();

    private OutlineRegistry() {
    }

    public void update(Map<Entity, Integer> next) {
        selection.update(next);
    }

    public void clear() {
        selection.clear();
    }

    /** True while anything is queued — ORed into {@code RenderGlobal.isRenderEntityOutlines}. */
    public boolean isActive() {
        return selection.isActive();
    }

    /** True if {@code entity} is selected — ORed into the outline sub-pass's players-only filter. */
    public boolean shouldOutline(Entity entity) {
        return selection.contains(entity);
    }

    /** Packed 0xRRGGBB for {@code entity}, or 0 when not selected (team colour then applies as vanilla). */
    public int colorOf(Entity entity) {
        Integer color = selection.get(entity);
        return color == null ? 0 : color.intValue();
    }
}
