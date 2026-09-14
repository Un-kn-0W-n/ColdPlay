package coldplay.broker;

import coldplay.util.EntityRenderSelectionSnapshot;
import net.minecraft.entity.Entity;

import java.util.Map;

/**
 * Per-entity outline colours for vanilla's spectator outline pass. The writer must clear on disable;
 * the renderer keeps reading after the module leaves the event bus.
 */
public final class OutlineRegistry {

    private static final OutlineRegistry INSTANCE = new OutlineRegistry();

    public static OutlineRegistry getInstance() {
        return INSTANCE;
    }

    /** Packed 0xRRGGBB per entity; 0 is never stored. */
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

    public boolean isActive() {
        return selection.isActive();
    }

    public boolean shouldOutline(Entity entity) {
        return selection.contains(entity);
    }

    /** Packed 0xRRGGBB, or 0 when not selected. */
    public int colorOf(Entity entity) {
        Integer color = selection.get(entity);
        return color == null ? 0 : color.intValue();
    }
}
