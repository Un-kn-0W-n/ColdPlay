package coldplay.broker;

import coldplay.util.EntityRenderSelectionSnapshot;
import net.minecraft.entity.Entity;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Unions per-owner entity selections for the through-wall model pass in
 * {@code RenderGlobal.renderEntities}. Writes run on the client thread and publish a volatile snapshot.
 * Owners must clear their contribution on disable because the renderer keeps reading after they
 * leave the event bus. Input sets are copied so callers may retain live views of their state.
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

    /** Replaces {@code owner}'s selection; an empty set is equivalent to {@link #clear(Object)}. */
    public void update(Object owner, Set<Entity> next) {
        if (next == null || next.isEmpty()) {
            clear(owner);
            return;
        }
        contributions.put(owner, identitySet(next));
        publish();
    }

    /** Drops {@code owner}'s selection — module disable, mode switch away from Chams, null world. */
    public void clear(Object owner) {
        if (contributions.remove(owner) != null) { // no-op clears stay free for per-frame syncs
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

    /** True while anything is queued — gates the whole Chams pass in {@code RenderGlobal}. */
    public boolean isActive() {
        return selection.isActive();
    }

    /** True if {@code entity} should be re-rendered through walls this frame. */
    public boolean shouldRender(Entity entity) {
        return selection.contains(entity);
    }
}
