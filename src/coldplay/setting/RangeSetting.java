package coldplay.setting;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

import java.util.concurrent.ThreadLocalRandom;

/** A [lo, hi] pair within [min, max], usually a random delay range sampled with random(). */
public class RangeSetting extends BoundedSetting<double[]> {

    private String unit = "";

    public RangeSetting(String name, double lo, double hi, double min, double max, double increment) {
        super(name, new double[]{lo, hi}, min, max, increment);
        set(new double[]{lo, hi});
    }

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

    /** Uniform in [lo, hi); ThreadLocalRandom rejects an empty range, hence the lo == hi check. */
    public double random() {
        double lo = getLo();
        double hi = getHi();
        if (lo == hi) {
            return lo;
        }
        return ThreadLocalRandom.current().nextDouble(lo, hi);
    }

    /** Display suffix such as "%"; not persisted. */
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
            // configs written before this became a range hold a scalar
            double v = json.getAsDouble();
            set(new double[]{v, v});
        }
    }
}
