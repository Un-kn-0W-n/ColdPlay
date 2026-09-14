package coldplay.util;

/** Visual-only block pose for ItemRenderer, held briefly after each attack. */
public final class BlockPose {
    private static final long HOLD_MS = 300L; // bridges click gaps at >= 5 CPS

    private static boolean active;
    private static long holdUntilMs;

    public static void set(boolean v) {
        active = v;
        if (!v) {
            holdUntilMs = 0L;
        }
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
