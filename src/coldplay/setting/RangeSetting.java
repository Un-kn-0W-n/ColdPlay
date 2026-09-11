package coldplay.setting;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

import java.util.concurrent.ThreadLocalRandom;

/**
 * A pair of numeric values {@code [lo, hi]} bounded by {@code [min, max]} and snapped to
 * {@code increment}. Rendered as a single two-thumb slider in the settings popup; typically used
 * as a randomized min/max delay a module samples via {@link #random()}.
 */
public class RangeSetting extends BoundedSetting<double[]> {

    private String unit = "";

    public RangeSetting(String name, double lo, double hi, double min, double max, double increment) {
        super(name, new double[]{lo, hi}, min, max, increment);
        set(new double[]{lo, hi});
    }

    /** Copy the input so later caller mutations cannot reorder the stored bounds. */
    @Override
    public void set(double[] value) {
        double a = clampAndSnap(value[0]);
        double b = clampAndSnap(value[1]);
        super.set(new double[]{Math.min(a, b), Math.max(a, b)});
    }

    public double getLo() {
        return get()[0];
    }

    public double getHi() {
        return get()[1];
    }

    public void setLo(double lo) {
        set(new double[]{lo, getHi()});
    }

    public void setHi(double hi) {
        set(new double[]{getLo(), hi});
    }

    /** Uniform random in [lo, hi); lo when the range is collapsed (ThreadLocalRandom rejects an empty range). */
    public double random() {
        double lo = getLo();
        double hi = getHi();
        if (lo == hi) {
            return lo;
        }
        return ThreadLocalRandom.current().nextDouble(lo, hi);
    }

    /** Suffix appended to both ends of the rendered value, e.g. {@code "%"}. Display only, never persisted. */
    public RangeSetting unit(String unit) {
        this.unit = unit;
        return this;
    }

    public String getUnit() {
        return unit;
    }

    @Override
    public RangeSetting describe(String description) {
        super.describe(description);
        return this;
    }

    @Override
    public JsonElement toJson() {
        JsonArray pair = new JsonArray();
        pair.add(new JsonPrimitive(getLo()));
        pair.add(new JsonPrimitive(getHi()));
        return pair;
    }

    @Override
    public void fromJson(JsonElement json) {
        if (json.isJsonArray() && json.getAsJsonArray().size() >= 2) {
            JsonArray pair = json.getAsJsonArray();
            set(new double[]{pair.get(0).getAsDouble(), pair.get(1).getAsDouble()});
        } else if (json.isJsonPrimitive() && json.getAsJsonPrimitive().isNumber()) {
            // Preserve scalar values from configs written before a setting became a range.
            double v = json.getAsDouble();
            set(new double[]{v, v});
        }
    }
}
