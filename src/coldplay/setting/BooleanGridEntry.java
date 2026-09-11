package coldplay.setting;

/** Persistable on/off cell shared by item and bed-grid settings. */
public interface BooleanGridEntry {
    String persistenceKey();
    boolean isEnabled();
    void setEnabled(boolean enabled);
}
