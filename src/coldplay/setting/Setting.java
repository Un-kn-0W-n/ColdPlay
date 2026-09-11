package coldplay.setting;

import com.google.gson.JsonElement;

import java.util.function.BooleanSupplier;

/**
 * Base class for a single configurable value attached to a {@link coldplay.module.Module}.
 * Concrete types ({@link BooleanSetting}, {@link NumberSetting}, {@link ModeSetting}) decide how
 * the value is edited in the ClickGUI and how it is (de)serialized by the config manager.
 *
 * @param <T> the value type held by this setting
 */
public abstract class Setting<T> {
    private final String name;
    protected T value;
    private BooleanSupplier visibility;
    /** One-line summary shown as a ClickGUI hover tooltip; empty means no tooltip. */
    private String description = "";
    /** Visual nesting depth in the settings popup (0 = flush left). Presentation only; never persisted. */
    private int indent;
    /** Optional display text shown in the popup; when null the {@link #name} (also the config key) is shown. */
    private String displayName;

    protected Setting(String name, T value) {
        this.name = name;
        this.value = value;
    }

    public String getName() {
        return name;
    }

    public T get() {
        return value;
    }

    public void set(T value) {
        this.value = value;
    }

    public Setting<T> visibleWhen(BooleanSupplier visibility) {
        this.visibility = visibility;
        return this;
    }

    public boolean isVisible() {
        return visibility == null || visibility.getAsBoolean();
    }

    public Setting<T> describe(String description) {
        this.description = description;
        return this;
    }

    public String getDescription() {
        return description;
    }

    public Setting<T> indent(int level) {
        this.indent = Math.max(0, level);
        return this;
    }

    public int getIndent() {
        return indent;
    }

    /**
     * Overrides the text shown in the popup while keeping {@link #name} as the (unique) config key —
     * lets two sibling settings display the same label (e.g. "Instant Swap") without colliding on disk.
     */
    public Setting<T> label(String displayName) {
        this.displayName = displayName;
        return this;
    }

    public String getDisplayName() {
        return displayName != null ? displayName : name;
    }

    /**
     * Serializes the current value to the JSON shape {@link coldplay.config.ConfigManager} persists
     * under this setting's name, or {@code null} for a value-less setting that must be skipped.
     */
    public abstract JsonElement toJson();

    /**
     * Restores a {@link #toJson()} value. {@link coldplay.config.ConfigCodec} isolates failures per
     * setting so a malformed value does not prevent other settings from loading.
     */
    public abstract void fromJson(JsonElement json);
}
