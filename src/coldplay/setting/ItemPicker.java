package coldplay.setting;

import java.util.List;

/**
 * Shared category, search and scrolling state for item pickers. Cell decoration and slot strips
 * remain specific to each widget's draw method.
 */
public interface ItemPicker {
    List<HotbarSetting.Category> getCategories();
    List<HotbarSetting.Entry> getFilteredEntries();
    int getOpenCategory();
    void setOpenCategory(int idx);
    String getSearch();
    void setSearch(String s);
    double getScroll();
    void setScroll(double v);
    boolean isWidgetOpen();
    void setWidgetOpen(boolean open);
}
