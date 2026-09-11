package coldplay.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

/**
 * Packed opaque {@code 0xFFRRGGBB}; channel accessors return 0-255 for rendering.
 */
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

    /** Stores an opaque colour from a packed RGB int (any alpha bits are forced to {@code 0xFF}). */
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
        // Force opaque alpha so loaded colours remain visible outside the RGB-only picker.
        setRgb(json.getAsInt());
    }
}
