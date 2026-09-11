package coldplay.module;

/** Immutable HUD-facing snapshot; it exposes no concrete module or mutable setting. */
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
