package coldplay.setting;

import net.minecraft.util.MathHelper;

/** A setting clamped to [min, max] and snapped to increment. */
public abstract class BoundedSetting<T> extends Setting<T> {
    private final double min;
    private final double max;
    private final double increment;

    protected BoundedSetting(String name, T value, double min, double max, double increment) {
        super(name, value);
        this.min = min;
        this.max = max;
        this.increment = increment;
    }

    public double getMin() {
        return min;
    }

    public double getMax() {
        return max;
    }

    public double getIncrement() {
        return increment;
    }

    protected double clampAndSnap(double raw) {
        double v = MathHelper.clamp_double(raw, min, max);
        if (increment > 0) {
            v = Math.round(v / increment) * increment;
            v = MathHelper.clamp_double(v, min, max);
        }
        // strip floating-point noise from the snap multiply/divide
        return Math.round(v * 1000.0) / 1000.0;
    }
}
