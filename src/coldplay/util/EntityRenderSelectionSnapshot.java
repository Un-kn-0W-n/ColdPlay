package coldplay.util;

import net.minecraft.entity.Entity;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;

/** Identity-keyed entity selection published as one immutable snapshot at a time. */
public final class EntityRenderSelectionSnapshot<V> {

    private volatile Map<Entity, V> values = Collections.emptyMap();

    public void update(Map<? extends Entity, ? extends V> next) {
        if (next == null || next.isEmpty()) {
            clear();
            return;
        }
        IdentityHashMap<Entity, V> copy = new IdentityHashMap<Entity, V>(next.size());
        for (Map.Entry<? extends Entity, ? extends V> entry : next.entrySet()) {
            if (entry.getKey() != null) {
                copy.put(entry.getKey(), entry.getValue());
            }
        }
        values = copy.isEmpty() ? Collections.<Entity, V>emptyMap()
                : Collections.unmodifiableMap(copy);
    }

    public void updateMembers(Iterable<? extends Entity> entities, V marker) {
        if (entities == null) {
            clear();
            return;
        }
        IdentityHashMap<Entity, V> copy = new IdentityHashMap<Entity, V>();
        for (Entity entity : entities) {
            if (entity != null) {
                copy.put(entity, marker);
            }
        }
        values = copy.isEmpty() ? Collections.<Entity, V>emptyMap()
                : Collections.unmodifiableMap(copy);
    }

    public void clear() {
        values = Collections.emptyMap();
    }

    public boolean isActive() {
        return !values.isEmpty();
    }

    public boolean contains(Entity entity) {
        return values.containsKey(entity);
    }

    public V get(Entity entity) {
        return values.get(entity);
    }
}
