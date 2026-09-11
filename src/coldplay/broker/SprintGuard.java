package coldplay.broker;

/**
 * One-tick sprint veto, requested during EventStrafe and consumed after vanilla's sprint gates,
 * before physics and the C0B diff-send. The vanilla hook is needed because those gates read input
 * after {@link coldplay.util.MoveFix} remaps it, with no intervening module hook.
 */
public final class SprintGuard {

    private static final SprintGuard INSTANCE = new SprintGuard();

    private boolean suppressed;

    private SprintGuard() {
    }

    public static SprintGuard getInstance() {
        return INSTANCE;
    }

    /** Veto sprint for the current tick (push from EventStrafe, same tick, before the gates run). */
    public void suppress() {
        suppressed = true;
    }

    /** Read-and-clear, called once per tick by the vanilla sprint gate; never leaks across ticks. */
    public boolean consumeSuppression() {
        boolean s = suppressed;
        suppressed = false;
        return s;
    }
}
