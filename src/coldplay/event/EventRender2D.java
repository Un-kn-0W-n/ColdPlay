package coldplay.event;

import net.minecraft.client.gui.ScaledResolution;

/**
 * Fired at the end of {@code GuiIngame.renderGameOverlay()}, after the vanilla HUD/chat/scoreboard,
 * so client HUD elements (ArrayList, watermark, etc.) draw on top. GL is already in the 2D overlay
 * ortho projection. The {@link ScaledResolution} built by the vanilla method is passed through so
 * listeners do not rebuild it.
 */
public class EventRender2D extends Event {
    private final ScaledResolution resolution;

    public EventRender2D(ScaledResolution resolution) {
        this.resolution = resolution;
    }

    public ScaledResolution getResolution() {
        return resolution;
    }
}
