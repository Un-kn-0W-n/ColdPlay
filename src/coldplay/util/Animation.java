package coldplay.util;

/**
 * Exponential easing based on elapsed time to keep animation independent of frame rate.
 * Render-thread only; not thread-safe.
 */
public final class Animation {

    /** Largest real-time step honored per update (seconds). Caps the catch-up jump after a long
     *  stall (alt-tab, GC pause) so the value eases in instead of snapping. */
    private static final double MAX_STEP = 0.1;

    private final double speed; // higher = snappier; ~= 1/time-constant in 1/seconds
    private double value;
    private double target;
    private long lastNanos; // 0 until the first update; its elapsed time is zero

    public Animation(double initial, double speed) {
        this.value = initial;
        this.target = initial;
        this.speed = speed;
    }

    /** Sets a new target and advances the current value toward it for the elapsed real time. */
    public double update(double target) {
        this.target = target;
        long now = System.nanoTime();
        double dt = lastNanos == 0L ? 0.0 : (now - lastNanos) / 1.0e9;
        lastNanos = now;
        if (dt > MAX_STEP) {
            dt = MAX_STEP;
        }
        // Exponential approach: fraction covered this step is independent of frame rate.
        double alpha = 1.0 - Math.exp(-speed * dt);
        value += (target - value) * alpha;
        return value;
    }

    /** Snaps the value (and target) immediately, e.g. when animations are disabled. */
    public void set(double v) {
        this.value = v;
        this.target = v;
    }

    public double get() {
        return value;
    }
}
