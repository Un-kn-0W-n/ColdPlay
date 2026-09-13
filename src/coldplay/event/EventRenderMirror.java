package coldplay.event;

/** Posted from EntityRenderer for each mirror-view pass; only view-dependent ESP drawing belongs here. */
public final class EventRenderMirror extends Event {
    public enum Stage { WORLD, OVERLAY }

    private final Stage stage;
    private final float partialTicks;
    private final int width, height, scale;
    private final float yaw;

    public EventRenderMirror(Stage stage, float partialTicks, int width, int height, int scale, float yaw) {
        this.stage = stage;
        this.partialTicks = partialTicks;
        this.width = width;
        this.height = height;
        this.scale = scale;
        this.yaw = yaw;
    }

    public Stage getStage() { return stage; }
    public float getPartialTicks() { return partialTicks; }
    public int getWidth() { return width; }
    public int getHeight() { return height; }
    public int getScale() { return scale; }
    public float getYaw() { return yaw; }
}
