package coldplay.event;

import net.minecraft.client.gui.ScaledResolution;

/** Posted at the end of GuiIngame.renderGameOverlay, after the vanilla HUD, in the 2D ortho projection. */
public class EventRender2D extends Event {
    private final ScaledResolution resolution;

    public EventRender2D(ScaledResolution resolution) {
        this.resolution = resolution;
    }

    public ScaledResolution getResolution() {
        return resolution;
    }
}
