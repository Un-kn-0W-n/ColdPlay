package coldplay.event;

/**
 * An {@link Event} fired twice around a vanilla action: once as {@link EventPhase#PRE} (before) and
 * once as {@link EventPhase#POST} (after). The posting hook creates one instance per phase.
 */
public abstract class PhasedEvent extends Event {
    private final EventPhase phase;

    protected PhasedEvent(EventPhase phase) {
        this.phase = phase;
    }

    public boolean isPre() {
        return phase == EventPhase.PRE;
    }
}
