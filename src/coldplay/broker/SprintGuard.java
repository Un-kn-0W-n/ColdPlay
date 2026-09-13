package coldplay.broker;

/** One-tick sprint veto, set during EventStrafe and consumed by the vanilla sprint gate the same tick. */
public final class SprintGuard {

    private static final SprintGuard INSTANCE = new SprintGuard();

    private boolean suppressed;

    private SprintGuard() {
    }

    public static SprintGuard getInstance() {
        return INSTANCE;
    }

    public void suppress() {
        suppressed = true;
    }

    public boolean consumeSuppression() {
        boolean s = suppressed;
        suppressed = false;
        return s;
    }
}
