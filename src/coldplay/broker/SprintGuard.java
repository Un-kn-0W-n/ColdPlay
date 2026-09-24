package coldplay.broker;

/** One-tick sprint veto, set during EventStrafe and consumed by the vanilla sprint gate the same tick. */
public final class SprintGuard {

    private static final SprintGuard INSTANCE = new SprintGuard();

    private boolean suppressed, kept;

    private SprintGuard() {
    }

    public static SprintGuard getInstance() {
        return INSTANCE;
    }

    public void suppress() {
        suppressed = true;
    }

    // a jump reset this tick needs the sprint boost, so sprint resets wait for the next hit
    public void keep() {
        kept = true;
    }

    public boolean isKept() {
        return kept;
    }

    public boolean consumeSuppression() {
        boolean s = suppressed;
        suppressed = false;
        kept = false;
        return s;
    }
}
