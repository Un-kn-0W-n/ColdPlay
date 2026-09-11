package coldplay.event;

/**
 * Cancellation stops lower-priority subscribers. Hook sites that support suppression also check
 * the flag before performing the underlying vanilla action.
 */
public abstract class Event {
    private boolean cancelled;

    public boolean isCancelled() {
        return cancelled;
    }

    /** Stop lower-priority subscribers and ask a cancellable hook to suppress its vanilla action. */
    public void setCancelled(boolean cancelled) {
        this.cancelled = cancelled;
    }
}
