package coldplay.module;

import coldplay.setting.BooleanSetting;
import coldplay.setting.Setting;
import org.lwjglx.input.Keyboard;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public abstract class Module {
    private final String name;
    private final Category category;
    private final String description;
    private boolean enabled;
    private int keyBind = Keyboard.KEY_NONE;
    private final List<Setting<?>> settings = new ArrayList<>();
    private final List<Setting<?>> settingsView = Collections.unmodifiableList(settings);
    /** Set by {@link #addAutoOff()}; modules without it are never swept by {@link ModuleManager}. */
    private BooleanSetting autoOff;

    protected Module(String name, Category category, String description) {
        this.name = name;
        this.category = category;
        this.description = description;
    }

    /** Lifecycle state is writable only by {@link ModuleManager}. */
    void setEnabledState(boolean enabled) {
        this.enabled = enabled;
    }

    protected void onEnable() {
    }

    protected void onDisable() {
    }

    protected <T extends Setting<?>> T add(T setting) {
        settings.add(setting);
        return setting;
    }

    /** Opt-in "Auto Off": ModuleManager disables this module on death, world change, or a far server teleport. */
    protected void addAutoOff() {
        autoOff = add(new BooleanSetting("Auto Off", false)
                .describe("Disable this module on death, world change, or when the server teleports you more than 32 blocks."));
    }

    public boolean isAutoOff() {
        return autoOff != null && autoOff.get();
    }

    public List<Setting<?>> getSettings() {
        return settingsView;
    }

    public String getName() {
        return name;
    }

    public Category getCategory() {
        return category;
    }

    public String getDescription() {
        return description;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public int getKeyBind() {
        return keyBind;
    }

    public void setKeyBind(int keyBind) {
        this.keyBind = keyBind;
    }
}
