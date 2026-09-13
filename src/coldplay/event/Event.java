package coldplay.event;

/** Cancelling stops lower-priority subscribers and, where the hook supports it, the vanilla action. */
public abstract class Event {
    private boolean cancelled;

    public boolean isCancelled() {
        return cancelled;
    }

    public void setCancelled(boolean cancelled) {
        this.cancelled = cancelled;
    }
}
