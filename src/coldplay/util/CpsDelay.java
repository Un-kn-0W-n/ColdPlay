package coldplay.util;

import java.util.Random;

/** Converts a CPS range to a random delay in milliseconds. */
public final class CpsDelay {
    private CpsDelay() {
    }

    public static long sample(Random random, double minCps, double maxCps) {
        double lower = Math.max(1.0D, Math.min(minCps, maxCps));
        double upper = Math.max(lower, Math.max(minCps, maxCps));
        double slowestMs = 1000.0D / lower;
        double fastestMs = 1000.0D / upper;
        return Math.max(1L, (long)(fastestMs + random.nextDouble() * (slowestMs - fastestMs)));
    }
}
