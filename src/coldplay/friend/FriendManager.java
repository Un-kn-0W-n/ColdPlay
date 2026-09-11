package coldplay.friend;

import java.awt.Color;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Shared friend list, preserved across world changes and persisted by
 * {@link coldplay.config.ConfigManager}. Names match case-insensitively while retaining display case.
 * Unique opaque colours are persisted; removing and re-adding a friend may assign a new colour.
 *
 * <p>Synchronized access permits readers on different threads. Callers save once per user action
 * so batch operations write the config only once.
 */
public final class FriendManager {

    private static final FriendManager INSTANCE = new FriendManager();

    private final Map<String, Integer> friends = new TreeMap<String, Integer>(String.CASE_INSENSITIVE_ORDER);

    private FriendManager() {
    }

    public static FriendManager getInstance() {
        return INSTANCE;
    }

    public synchronized boolean add(String name) {
        if (name == null) {
            return false;
        }
        name = name.trim();
        if (name.isEmpty() || friends.containsKey(name)) {
            return false;
        }
        friends.put(name, nextColor());
        return true;
    }

    public synchronized boolean remove(String name) {
        if (name == null) {
            return false;
        }
        return friends.remove(name.trim()) != null;
    }

    /** Flips friend status. Returns {@code true} if {@code name} is now a friend, {@code false} if removed. */
    public synchronized boolean toggle(String name) {
        if (isFriend(name)) {
            remove(name);
            return false;
        }
        add(name);
        return true;
    }

    public synchronized boolean isFriend(String name) {
        return name != null && friends.containsKey(name.trim());
    }

    /**
     * Packed opaque {@code 0xFFRRGGBB}, or {@code 0} for a non-friend to signal no colour to renderers.
     */
    public synchronized int getColor(String name) {
        if (name == null) {
            return 0;
        }
        Integer color = friends.get(name.trim());
        return color != null ? color : 0;
    }

    /** Overrides a friend's colour (alpha forced opaque). Returns {@code false} if not a friend. */
    public synchronized boolean setColor(String name, int rgb) {
        if (name == null) {
            return false;
        }
        name = name.trim();
        if (!friends.containsKey(name)) {
            return false;
        }
        friends.put(name, 0xFF000000 | (rgb & 0xFFFFFF));
        return true;
    }

    public synchronized int clear() {
        int size = friends.size();
        friends.clear();
        return size;
    }

    /** A sorted, unmodifiable snapshot — never the live set, so callers can iterate it safely. */
    public synchronized Set<String> getFriends() {
        return Collections.unmodifiableSet(new TreeSet<String>(friends.keySet()));
    }

    /**
     * Replaces the whole list (used when restoring from disk). Null/blank names are skipped. Entries
     * with colour {@code 0} (legacy plain-string configs) are assigned fresh colours in a second pass,
     * after all persisted colours are in place, so an upgrade can never collide with a saved colour.
     */
    public synchronized void setFriends(Map<String, Integer> entries) {
        friends.clear();
        if (entries == null) {
            return;
        }
        for (Map.Entry<String, Integer> entry : entries.entrySet()) {
            String name = entry.getKey();
            Integer color = entry.getValue();
            if (name != null && !name.trim().isEmpty() && color != null && color != 0) {
                friends.put(name.trim(), color);
            }
        }
        for (Map.Entry<String, Integer> entry : entries.entrySet()) {
            String name = entry.getKey();
            Integer color = entry.getValue();
            if (name != null && !name.trim().isEmpty() && (color == null || color == 0)
                    && !friends.containsKey(name.trim())) {
                friends.put(name.trim(), nextColor());
            }
        }
    }

    /**
     * Lowest golden-ratio palette colour not already assigned: consecutive hues land maximally far
     * apart, so members are visually distinct. Caller holds the monitor.
     */
    // O(n^2) linear probe across the list — fine at friend-list scale, only runs on add/load
    private int nextColor() {
        for (int i = 0; ; i++) {
            int c = 0xFF000000 | Color.HSBtoRGB((float) (i * 0.61803398875 % 1.0), 0.85f, 1.0f);
            if (!friends.containsValue(c)) {
                return c;
            }
        }
    }
}
