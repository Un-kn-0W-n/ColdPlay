package coldplay.event;

/** Posted from Minecraft.runTick for each raw LWJGL mouse button press or release. */
public class EventMouse extends Event {
    private final int button;
    private final boolean pressed;

    public EventMouse(int button, boolean pressed) {
        this.button = button;
        this.pressed = pressed;
    }

    public int getButton() {
        return button;
    }

    public boolean isPressed() {
        return pressed;
    }
}
