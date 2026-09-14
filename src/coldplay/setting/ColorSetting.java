package coldplay.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

/** Opaque colour stored as 0xFFRRGGBB. */
public class ColorSetting extends Setting<Integer> {

    public ColorSetting(String name, int argb) {
        super(name, 0xFF000000 | (argb & 0xFFFFFF));
    }

    public int red() {
        return (get() >> 16) & 0xFF;
    }

    public int green() {
        return (get() >> 8) & 0xFF;
    }

    public int blue() {
        return get() & 0xFF;
    }

    public void setRgb(int rgb) {
        set(0xFF000000 | (rgb & 0xFFFFFF));
    }

    @Override
    public ColorSetting describe(String description) {
        super.describe(description);
        return this;
    }

    @Override
    public JsonElement toJson() {
        return new JsonPrimitive(get());
    }

    @Override
    public void fromJson(JsonElement json) {
        // force opaque alpha on load; the picker is RGB-only
        setRgb(json.getAsInt());
    }
}
