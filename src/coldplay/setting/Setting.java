package coldplay.setting;

import com.google.gson.JsonElement;

import java.util.function.BooleanSupplier;

/** One configurable value on a module; subclasses define the GUI widget and the JSON form. */
public abstract class Setting<T> {
    private final String name;
    protected T value;
    private BooleanSupplier visibility;
    private String description = ""; // tooltip; empty means none
    private int indent;
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

    /** Display text; name stays the config key, so sibling settings may share a label. */
    public Setting<T> label(String displayName) {
        this.displayName = displayName;
        return this;
    }

    public String getDisplayName() {
        return displayName != null ? displayName : name;
    }

    /** Returns null for a setting with no persisted value. */
    public abstract JsonElement toJson();

    /** May throw on a malformed value; the caller isolates failures per setting. */
    public abstract void fromJson(JsonElement json);
}
