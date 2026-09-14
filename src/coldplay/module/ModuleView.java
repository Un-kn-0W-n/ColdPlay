package coldplay.module;

/** Immutable snapshot of a module for the HUD. */
public final class ModuleView {
    private final String name;
    private final boolean enabled;
    private final String suffix;

    public ModuleView(String name, boolean enabled, String suffix) {
        this.name = name;
        this.enabled = enabled;
        this.suffix = suffix;
    }

    public String getName() {
        return name;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String getSuffix() {
        return suffix;
    }
}
