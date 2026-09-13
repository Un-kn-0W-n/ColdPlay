package coldplay.event;

/** Posted per frame from EntityRenderer.updateCameraAndRender before the camera is built. */
public class EventRender extends Event {
    private final float partialTicks;

    public EventRender(float partialTicks) {
        this.partialTicks = partialTicks;
    }

    public float getPartialTicks() {
        return partialTicks;
    }
}
