package coldplay.setting;

import com.google.gson.JsonElement;

/**
 * Visual section divider with no persisted value.
 */
public class HeaderSetting extends Setting<Void> {

    public HeaderSetting(String name) {
        super(name, null);
    }

    @Override
    public JsonElement toJson() {
        return null;
    }

    @Override
    public void fromJson(JsonElement json) {
    }
}
