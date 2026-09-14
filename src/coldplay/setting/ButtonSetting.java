package coldplay.setting;

import com.google.gson.JsonElement;

public class ButtonSetting extends Setting<Void> {

    private final Runnable action;

    public ButtonSetting(String name, Runnable action) {
        super(name, null);
        this.action = action;
    }

    /** Runs the action, swallowing exceptions so a bad button cannot break the GUI event loop. */
    public void run() {
        if (action == null) {
            return;
        }
        try {
            action.run();
        } catch (Throwable t) {
            System.err.println("[ColdPlay] setting button '" + getName() + "' failed: " + t);
        }
    }

    @Override
    public ButtonSetting describe(String description) {
        super.describe(description);
        return this;
    }

    @Override
    public JsonElement toJson() {
        return null;
    }

    @Override
    public void fromJson(JsonElement json) {
    }
}
