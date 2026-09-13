package coldplay.event;

/** Posted from EntityRenderer.renderWorldPass after the world is drawn, camera transform still active. */
public class EventRender3D extends Event {
    private final float partialTicks;

    public EventRender3D(float partialTicks) {
        this.partialTicks = partialTicks;
    }

    public float getPartialTicks() {
        return partialTicks;
    }
}
