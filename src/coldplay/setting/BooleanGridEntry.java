package coldplay.setting;

public interface BooleanGridEntry {
    String persistenceKey();
    boolean isEnabled();
    void setEnabled(boolean enabled);
}
