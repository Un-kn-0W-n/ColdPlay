package coldplay.event;

/** Only view-dependent ESP drawing runs here; gameplay and the ordinary HUD run once per frame. */
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
