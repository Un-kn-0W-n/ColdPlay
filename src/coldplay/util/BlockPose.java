package coldplay.util;

import java.util.function.BooleanSupplier;

/** Visual-only block pose for ItemRenderer, held briefly after each attack. */
public final class BlockPose {
    private static final long HOLD_MS = 300L; // bridges click gaps at >= 5 CPS

    private static boolean active;
    private static long holdUntilMs;
    private static BooleanSupplier push;

    public static void enable(BooleanSupplier pushMode) {
        active = true;
        push = pushMode;
    }

    public static void disable() {
        active = false;
        holdUntilMs = 0L;
    }

    public static void onAttack() {
        holdUntilMs = System.currentTimeMillis() + HOLD_MS;
    }

    public static boolean posing() {
        return active && System.currentTimeMillis() < holdUntilMs;
    }

    // Push replaces the first person block transform, for the fake pose and a real block alike
    public static boolean push() {
        return active && push.getAsBoolean();
    }

    private BlockPose() {
    }
}
