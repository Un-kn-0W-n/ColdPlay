package coldplay.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

import java.util.Arrays;
import java.util.List;

/**
 * A value chosen from a fixed list of named modes. Left-click cycles forward, right-click backward
 * in the settings popup.
 */
public class ModeSetting extends Setting<String> {
    private final List<String> modes;

    public ModeSetting(String name, String value, String... modes) {
        super(name, value);
        this.modes = Arrays.asList(modes);
    }

    public List<String> getModes() {
        return modes;
    }

    public void cycle(int direction) {
        if (modes.isEmpty()) {
            return;
        }
        int index = modes.indexOf(get());
        if (index == -1) {
            index = 0;
        }
        index = (index + direction) % modes.size();
        if (index < 0) {
            index += modes.size();
        }
        set(modes.get(index));
    }

    @Override
    public ModeSetting describe(String description) {
        super.describe(description);
        return this;
    }

    @Override
    public JsonElement toJson() {
        return new JsonPrimitive(get());
    }

    @Override
    public void fromJson(JsonElement json) {
        String str = json.getAsString();
        if (modes.contains(str)) {
            set(str);
        }
    }
}
