package coldplay.setting;

import com.google.gson.JsonElement;
import net.minecraft.item.ItemStack;

import java.util.Arrays;
import java.util.List;

/** A row of independently toggled item icons. */
public class ItemGridSetting extends Setting<Void> {

    public static final class Entry implements BooleanGridEntry {
        private final String name;
        private final ItemStack icon;
        private boolean enabled;

        public Entry(String name, ItemStack icon, boolean enabled) {
            this.name = name;
            this.icon = icon;
            this.enabled = enabled;
        }

        public String getName() {
            return name;
        }

        @Override
        public String persistenceKey() {
            return name;
        }

        public ItemStack getIcon() {
            return icon;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public void toggle() {
            enabled = !enabled;
        }
    }

    private final List<Entry> entries;

    public ItemGridSetting(String name, Entry... entries) {
        super(name, null);
        this.entries = Arrays.asList(entries);
    }

    public List<Entry> getEntries() {
        return entries;
    }

    @Override
    public ItemGridSetting describe(String description) {
        super.describe(description);
        return this;
    }

    @Override
    public JsonElement toJson() {
        return BooleanGridCodec.encode(entries);
    }

    @Override
    public void fromJson(JsonElement json) {
        BooleanGridCodec.decode(json, entries);
    }
}
