package coldplay.util;

/** Shared arbitration tiers for rotation and held-slot brokers. */
public final class ResourcePriority {
    public static final int FALL_SAFETY = 30;
    public static final int EMERGENCY = 20;
    public static final int NORMAL = 10;
    public static final int BACKGROUND = 0;

    private ResourcePriority() {
    }
}
