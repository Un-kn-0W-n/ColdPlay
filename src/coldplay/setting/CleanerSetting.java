package coldplay.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.minecraft.item.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Cleaning mode per item key or category key; an item entry overrides its category's. */
public class CleanerSetting extends Setting<Void> {

    // declaration order is the click cycle, with unset after IGNORE
    public enum CleanerMode { DROP, KEEP_ONE, IGNORE }

    private final Map<String, CleanerMode> modes = new LinkedHashMap<>();

    public CleanerSetting(final String name) {
        super(name, null);
    }

    public Map<String, CleanerMode> getModes() { return modes; }

    public CleanerMode getMode(final String token) { return modes.get(token); }

    public void cycle(final String token) {
        cycle(token, 1);
    }

    public void cycleBack(final String token) {
        cycle(token, -1);
    }

    private void cycle(final String token, final int direction) {
        if (token == null) {
            return;
        }
        final CleanerMode current = modes.get(token);
        final int state = current == null ? -1 : current.ordinal();
        final int stateCount = CleanerMode.values().length + 1; // extra state = unset
        final int next = (state + direction + stateCount) % stateCount;
        if (next == CleanerMode.values().length) {
            modes.remove(token);
        } else {
            modes.put(token, CleanerMode.values()[next]);
        }
    }

    public CleanerMode effectiveMode(final ItemStack stack) {
        final String key = HotbarSetting.keyOf(stack);
        if (key == null) {
            return null;
        }
        final CleanerMode item = modes.get(key);
        if (item != null) {
            return item;
        }
        return modes.get(HotbarSetting.categoryRef(HotbarSetting.bucket(stack)));
    }

    @Override
    public CleanerSetting describe(final String d) {
        super.describe(d);
        return this;
    }

    @Override
    public JsonElement toJson() {
        // sorted so the file is deterministic
        final List<String> tokens = new ArrayList<>(modes.keySet());
        Collections.sort(tokens);
        final JsonObject obj = new JsonObject();
        for (final String token : tokens) {
            obj.addProperty(token, modes.get(token).name());
        }
        return obj;
    }

    @Override
    public void fromJson(final JsonElement json) {
        if (!json.isJsonObject()) {
            return;
        }
        modes.clear();
        for (final Map.Entry<String, JsonElement> e : json.getAsJsonObject().entrySet()) {
            try {
                final CleanerMode mode = CleanerMode.valueOf(e.getValue().getAsString());
                modes.put(e.getKey(), mode);
            } catch (final Exception ignored) {
            }
        }
    }
}
