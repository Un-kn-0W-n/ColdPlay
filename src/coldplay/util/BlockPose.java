package coldplay.util;

/**
 * ItemRenderer's visual block pose. A short hold after each completed attack bridges click gaps
 * without changing network item-use state.
 */
public final class BlockPose {
    // fixed linger; bridges KillAura click gaps (>=5 CPS). Make it a setting if a slower CPS needs it.
    private static final long HOLD_MS = 300L;

    private static boolean active;
    private static long holdUntilMs;

    public static void set(boolean v) {
        active = v;
        if (!v) holdUntilMs = 0L;
    }

    public static void onAttack() {
        holdUntilMs = System.currentTimeMillis() + HOLD_MS;
    }

    public static boolean posing() {
        return active && System.currentTimeMillis() < holdUntilMs;
    }

    private BlockPose() {
    }
}
