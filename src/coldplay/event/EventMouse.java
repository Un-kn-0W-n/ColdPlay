package coldplay.event;

/**
 * Fired from the vanilla mouse-event drain loop in {@code Minecraft.runTick()} for each real mouse
 * button event (move/wheel-only events, where the LWJGL button index is {@code -1}, are filtered out at
 * the post site). It carries the raw LWJGL button index — {@code 0} left, {@code 1} right, {@code 2}
 * middle — and whether this event is a press ({@code true}) or release ({@code false}), so a listener
 * can react to the physical button independently of any vanilla keybind rebind.
 *
 * <p>Posting from inside vanilla's own {@code while (Mouse.next())} loop is deliberate: that loop owns
 * draining the LWJGL event queue, and a listener calling {@code Mouse.next()} itself would steal events
 * from vanilla. The hook only runs while no full-screen GUI captures the mouse (vanilla gates the loop
 * on {@code currentScreen == null || allowUserInput}), which is exactly when an in-world click is meant.
 */
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
