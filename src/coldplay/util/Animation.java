package coldplay.util;

/** Frame-rate independent exponential easing toward a target. Not thread-safe. */
public final class Animation {

    private static final double MAX_STEP = 0.1; // seconds; caps the catch-up after a stall

    private final double speed; // 1/seconds
    private double value;
    private double target;
    private long lastNanos; // 0 until the first update

    public Animation(double initial, double speed) {
        this.value = initial;
        this.target = initial;
        this.speed = speed;
    }

    /** Sets the target and eases the value toward it by the real time elapsed. */
    public double update(double target) {
        this.target = target;
        long now = System.nanoTime();
        double dt = lastNanos == 0L ? 0.0 : (now - lastNanos) / 1.0e9;
        lastNanos = now;
        if (dt > MAX_STEP) {
            dt = MAX_STEP;
        }
        double alpha = 1.0 - Math.exp(-speed * dt);
        value += (target - value) * alpha;
        return value;
    }

    public void set(double v) {
        this.value = v;
        this.target = v;
    }

    public double get() {
        return value;
    }
}
