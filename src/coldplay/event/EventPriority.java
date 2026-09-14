package coldplay.event;

/** Handler priorities; higher runs first. */
public final class EventPriority {
    public static final int BROKER_RESET = Integer.MAX_VALUE;
    public static final int STATE_TRACKING = 10_000;
    public static final int AUTO_OFF = 9_000;
    public static final int FALL_SAFETY = 700;
    public static final int EMERGENCY = 600;
    public static final int NORMAL = 0;
    public static final int BACKGROUND = -50;
    public static final int AIM = -75; // rotation requests, before RotationManager steps at DRAIN
    public static final int DRAIN = -100;

    private EventPriority() {
    }
}
