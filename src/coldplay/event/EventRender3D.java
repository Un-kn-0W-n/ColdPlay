package coldplay.event;

/**
 * Fired once per frame from {@code EntityRenderer.renderWorldPass()} after the world (terrain,
 * entities and translucent geometry) has been drawn. The camera transform remains active and
 * the depth buffer is populated for world-space overlays.
 */
public class EventRender3D extends Event {
    private final float partialTicks;

    public EventRender3D(float partialTicks) {
        this.partialTicks = partialTicks;
    }

    public float getPartialTicks() {
        return partialTicks;
    }
}
