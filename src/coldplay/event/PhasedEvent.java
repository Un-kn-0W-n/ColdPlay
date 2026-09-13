package coldplay.event;

/** Posted once per phase, PRE before the vanilla action and POST after. */
public abstract class PhasedEvent extends Event {
    private final EventPhase phase;

    protected PhasedEvent(EventPhase phase) {
        this.phase = phase;
    }

    public boolean isPre() {
        return phase == EventPhase.PRE;
    }
}
