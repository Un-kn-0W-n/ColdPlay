package coldplay.broker;

import coldplay.util.EntityRenderSelectionSnapshot;
import net.minecraft.entity.Entity;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Unions per-owner entity selections for the through-wall model pass in RenderGlobal.renderEntities.
 * Owners must clear their selection on disable; the renderer keeps reading after they leave the bus.
 */
public final class ChamsRegistry {

    private static final ChamsRegistry INSTANCE = new ChamsRegistry();

    public static ChamsRegistry getInstance() {
        return INSTANCE;
    }

    private final Map<Object, Set<Entity>> contributions = new IdentityHashMap<Object, Set<Entity>>();

    private final EntityRenderSelectionSnapshot<Boolean> selection =
            new EntityRenderSelectionSnapshot<Boolean>();

    private ChamsRegistry() {
    }

    public void update(Object owner, Set<Entity> next) {
        if (next == null || next.isEmpty()) {
            clear(owner);
            return;
        }
        contributions.put(owner, identitySet(next));
        publish();
    }

    public void clear(Object owner) {
        if (contributions.remove(owner) != null) {
            publish();
        }
    }

    private void publish() {
        Set<Entity> union = identitySet(null);
        for (Set<Entity> contribution : contributions.values()) {
            union.addAll(contribution);
        }
        selection.updateMembers(union, Boolean.TRUE);
    }

    private static Set<Entity> identitySet(Set<Entity> from) {
        Set<Entity> set = Collections.newSetFromMap(new IdentityHashMap<Entity, Boolean>());
        if (from != null) {
            set.addAll(from);
        }
        return set;
    }

    public boolean isActive() {
        return selection.isActive();
    }

    public boolean shouldRender(Entity entity) {
        return selection.contains(entity);
    }
}
