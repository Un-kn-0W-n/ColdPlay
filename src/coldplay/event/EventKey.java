package coldplay.event;

/**
 * Fired from the vanilla keyboard-event drain loop in {@code Minecraft.runTick()} for each real key
 * press while no screen is open (mirror of {@link EventMouse}). Carries the LWJGL key code — or, for
 * keys without one, {@code character + 256}, matching vanilla's own keybind convention — so listeners
 * (module keybinds, the ClickGUI open key) react to exactly the value vanilla keybinds use.
 *
 * <p>Posting from inside vanilla's own {@code while (Keyboard.next())} loop is deliberate: that loop
 * owns draining the LWJGL event queue, and a listener calling {@code Keyboard.next()} itself would
 * steal events from vanilla.
 */
public class EventKey extends Event {
    private final int key;

    public EventKey(int key) {
        this.key = key;
    }

    public int getKey() {
        return key;
    }
}
