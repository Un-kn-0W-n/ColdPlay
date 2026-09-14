package coldplay.setting;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Transient open, search and scroll state shared by the item picker settings. */
public abstract class AbstractItemPickerSetting extends Setting<Void> implements ItemPicker {
    private transient boolean widgetOpen;
    private transient int openCategory = -1;
    private transient String search = "";
    private transient double scroll;
    private transient List<HotbarSetting.Entry> filtered = Collections.emptyList();
    private transient String filteredKey;

    protected AbstractItemPickerSetting(String name) {
        super(name, null);
    }

    @Override
    public List<HotbarSetting.Entry> getFilteredEntries() {
        List<HotbarSetting.Category> categories = getCategories();
        if (openCategory < 0 || openCategory >= categories.size()) {
            return Collections.emptyList();
        }

        String query = search.toLowerCase(Locale.ROOT);
        String key = openCategory + "|" + query;
        if (!key.equals(filteredKey)) {
            List<HotbarSetting.Entry> result = new ArrayList<>();
            for (HotbarSetting.Entry entry : categories.get(openCategory).getEntries()) {
                if (query.isEmpty()
                        || entry.displayName().toLowerCase(Locale.ROOT).contains(query)) {
                    result.add(entry);
                }
            }
            filtered = result;
            filteredKey = key;
        }
        return filtered;
    }

    @Override
    public int getOpenCategory() {
        return openCategory;
    }

    @Override
    public void setOpenCategory(int index) {
        openCategory = index;
        scroll = 0.0D;
        invalidateFilter();
    }

    @Override
    public String getSearch() {
        return search;
    }

    @Override
    public void setSearch(String value) {
        search = value == null ? "" : value;
        scroll = 0.0D;
        invalidateFilter();
    }

    @Override
    public double getScroll() {
        return scroll;
    }

    @Override
    public void setScroll(double value) {
        scroll = value;
    }

    @Override
    public boolean isWidgetOpen() {
        return widgetOpen;
    }

    @Override
    public void setWidgetOpen(boolean open) {
        widgetOpen = open;
    }

    protected final void invalidateFilter() {
        filteredKey = null;
    }
}
