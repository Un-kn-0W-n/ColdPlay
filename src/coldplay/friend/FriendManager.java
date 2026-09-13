package coldplay.friend;

import java.awt.Color;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Friend list keyed case-insensitively, each name with a persisted opaque colour. */
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

    /** Returns {@code true} if {@code name} is now a friend. */
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

    /** Packed {@code 0xFFRRGGBB}, or {@code 0} for a non-friend. */
    public synchronized int getColor(String name) {
        if (name == null) {
            return 0;
        }
        Integer color = friends.get(name.trim());
        return color != null ? color : 0;
    }

    /** Returns {@code false} if {@code name} is not a friend. */
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

    /** Sorted, unmodifiable snapshot. */
    public synchronized Set<String> getFriends() {
        return Collections.unmodifiableSet(new TreeSet<String>(friends.keySet()));
    }

    /**
     * Replaces the whole list. Entries with colour {@code 0} get fresh colours once the persisted
     * ones are in place.
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

    // Golden-ratio hue walk; the linear probe is fine at friend-list size.
    private int nextColor() {
        for (int i = 0; ; i++) {
            int c = 0xFF000000 | Color.HSBtoRGB((float) (i * 0.61803398875 % 1.0), 0.85f, 1.0f);
            if (!friends.containsValue(c)) {
                return c;
            }
        }
    }
}
