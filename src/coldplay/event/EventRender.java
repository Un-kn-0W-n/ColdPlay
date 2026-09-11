package coldplay.event;

/**
 * Fired per frame from {@code EntityRenderer.updateCameraAndRender()} after mouse input and before
 * the camera is built, allowing smooth camera rotation independent of the tick rate.
 *
 * <p>{@link #getPartialTicks()} is the fractional progress between game ticks for this frame.
 */
public class EventRender extends Event {
    private final float partialTicks;

    public EventRender(float partialTicks) {
        this.partialTicks = partialTicks;
    }

    public float getPartialTicks() {
        return partialTicks;
    }
}
