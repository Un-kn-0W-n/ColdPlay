package coldplay.broker;

import coldplay.util.EntityRenderSelectionSnapshot;
import net.minecraft.entity.Entity;

/**
 * Entities NameTags draws a badge for. Vanilla skips their name plates, and the plates of anything riding them,
 * which is how servers stack name holograms. The writer must clear on disable.
 */
public final class NameTagRegistry {

    private static final NameTagRegistry INSTANCE = new NameTagRegistry();

    public static NameTagRegistry getInstance() {
        return INSTANCE;
    }

    private final EntityRenderSelectionSnapshot<Boolean> selection =
            new EntityRenderSelectionSnapshot<Boolean>();

    private NameTagRegistry() {
    }

    public void update(Iterable<? extends Entity> entities) {
        selection.updateMembers(entities, Boolean.TRUE);
    }

    public void clear() {
        selection.clear();
    }

    public boolean hidesName(Entity entity) {
        return selection.contains(entity) || entity.ridingEntity != null && selection.contains(entity.ridingEntity);
    }
}
