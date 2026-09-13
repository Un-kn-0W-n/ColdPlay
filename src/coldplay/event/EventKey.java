package coldplay.event;

/** Posted from Minecraft.runTick per key press with no screen open; key is the vanilla keybind code. */
public class EventKey extends Event {
    private final int key;

    public EventKey(int key) {
        this.key = key;
    }

    public int getKey() {
        return key;
    }
}
