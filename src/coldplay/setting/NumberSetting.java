package coldplay.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

/**
 * A numeric value bounded by {@code [min, max]} and snapped to {@code increment}.
 * Rendered as a draggable slider in the settings popup.
 */
public class NumberSetting extends BoundedSetting<Double> {

    public NumberSetting(String name, double value, double min, double max, double increment) {
        super(name, value, min, max, increment);
        set(value);
    }

    @Override
    public void set(Double value) {
        super.set(clampAndSnap(value));
    }

    @Override
    public NumberSetting describe(String description) {
        super.describe(description);
        return this;
    }

    @Override
    public JsonElement toJson() {
        return new JsonPrimitive(get());
    }

    @Override
    public void fromJson(JsonElement json) {
        set(json.getAsDouble());
    }
}
