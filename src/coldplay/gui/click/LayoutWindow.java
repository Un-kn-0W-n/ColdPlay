package coldplay.gui.click;

import coldplay.ColdPlay;
import coldplay.gui.CustomTextInput;
import coldplay.gui.GlassShader;
import coldplay.gui.Icons;
import coldplay.setting.CleanerSetting;
import coldplay.setting.HotbarSetting;
import coldplay.util.RenderUtil;
import coldplay.util.font.CustomFont;
import coldplay.util.font.FontRef;
import coldplay.util.font.Fonts;

import net.minecraft.util.MathHelper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Hotbar layout and cleaner editor, opened from their settings rows and floating beside the panel. */
final class LayoutWindow {
    private static final float W = 432.0F;
    private static final float GAP_X = 9.0F; // from the settings panel
    private static final float TITLE_H = 28.5F;
    private static final float PAD = 9.0F;
    private static final float SIDE_W = 112.5F;
    private static final float ROW_H = 18.0F;
    private static final float BTN_W = 15.0F;
    private static final float BTN_H = 13.5F;
    private static final float SEARCH_H = 19.5F;
    private static final int COLS = 12;
    private static final int ROWS = 7;
    private static final float CELL = 22.5F;
    private static final float GAP = 1.5F;
    private static final float GRID_W = COLS * CELL + (COLS - 1) * GAP;
    private static final float GRID_H = ROWS * CELL + (ROWS - 1) * GAP;
    private static final float BODY_H = SEARCH_H + 6.0F + GRID_H;
    private static final float HEAD_H = 13.5F;
    private static final float SLOT = 33.0F;
    private static final float SLOT_GAP = 6.0F;
    private static final float MINI = 9.0F;
    private static final int MAX_MINIS = 3;
    private static final float BAND_H = 96.0F;
    private static final float H = TITLE_H + PAD + BODY_H + BAND_H + PAD;
    private static final float CLOSE = 18.0F;

    private static final int DROP_CLR = 0xFFD05555;
    private static final int KEEP_CLR = 0xFF6FCF6F;
    private static final int EXCLUDE_OVERLAY = 0xC8121212;

    private static final FontRef NAME = new FontRef(Fonts.GEIST, 9.0F);
    private static final FontRef TAB = new FontRef(Fonts.GEIST, 8.625F);
    private static final FontRef HINT = new FontRef(Fonts.GEIST, 8.25F);
    private static final FontRef SMALL = new FontRef(Fonts.GEIST, 7.875F);
    private static final FontRef TIP = new FontRef(Fonts.GEIST_MEDIUM, 7.5F);
    private static final FontRef NAME_MILK = new FontRef(Fonts.JAKARTA, 9.0F);
    private static final FontRef TAB_MILK = new FontRef(Fonts.JAKARTA, 8.625F);
    private static final FontRef HINT_MILK = new FontRef(Fonts.JAKARTA, 8.25F);
    private static final FontRef SMALL_MILK = new FontRef(Fonts.JAKARTA, 7.875F);
    private static final FontRef TIP_MILK = new FontRef(Fonts.JAKARTA_MEDIUM, 7.5F);
    private static final FontRef COUNT = new FontRef(Fonts.GEIST_MONO, 7.5F);
    private static final FontRef MODE = new FontRef(Fonts.GEIST_MONO, 7.125F);
    private static final FontRef BADGE = new FontRef(Fonts.GEIST_MONO, 6.75F);
    private static final FontRef BADGE_BOLD = new FontRef(Fonts.GEIST_MONO_MEDIUM, 6.75F);
    private static final FontRef LETTER = new FontRef(Fonts.GEIST_MONO_MEDIUM, 9.0F);

    private static final String HOTBAR_HINT = "Pick an item or group, then a slot. Later picks become fallbacks.";
    private static final String CLEANER_HINT = "Click an item or a group badge to cycle Drop, Keep one, Ignore, off. "
            + "Right-click steps back. An item's mode beats its group's.";

    private final Skin skin;
    private final HotbarSetting hotbar;
    private final CleanerSetting cleaner;
    private final Runnable onClose;
    private final FontRef name;
    private final FontRef tab;
    private final FontRef hint;
    private final FontRef small;
    private final FontRef tip;

    private boolean cleanerTab;
    private int category;
    private String search = "";
    private boolean searching;
    private int cursorCounter;
    private int scrollRow;
    private String held; // hotbar key picked up, waiting for a slot
    private boolean carrying; // picked up by the current press, so releasing over a slot drops it
    private float heldIconX;
    private float x;
    private float y;
    private final WindowDrag move = new WindowDrag();
    private boolean moved; // dragged by its title bar, so it no longer follows the panel
    private int mouseX;
    private int mouseY;

    LayoutWindow(Skin skin, HotbarSetting hotbar, CleanerSetting cleaner, boolean cleanerTab, Runnable onClose) {
        this.skin = skin;
        this.hotbar = hotbar;
        this.cleaner = cleaner;
        this.cleanerTab = cleanerTab;
        this.onClose = onClose;
        name = skin.milk ? NAME_MILK : NAME;
        tab = skin.milk ? TAB_MILK : TAB;
        hint = skin.milk ? HINT_MILK : HINT;
        small = skin.milk ? SMALL_MILK : SMALL;
        tip = skin.milk ? TIP_MILK : TIP;
    }

    boolean isCleanerTab() {
        return cleanerTab;
    }

    void showTab(boolean cleaner) {
        cleanerTab = cleaner;
        held = null;
        search = "";
        scrollRow = 0;
        if (category == special()) {
            category = 0;
        }
    }

    /** Right of the panel when it fits, otherwise left of it, until the window is dragged. */
    void place(int panelX, int panelW, int panelY, int screenW, int screenH) {
        if (!moved) {
            float right = panelX + panelW + GAP_X;
            x = right + W <= screenW ? right : panelX - GAP_X - W;
            y = panelY;
        }
        x = MathHelper.clamp_float(x, 0.0F, Math.max(0.0F, screenW - W));
        y = MathHelper.clamp_float(y, 0.0F, Math.max(0.0F, screenH - H));
    }

    /** True while the title bar is being dragged. */
    boolean drag(int mouseX, int mouseY, int screenW, int screenH) {
        int[] pos = move.update(mouseX, mouseY, Math.round(W), Math.round(H), screenW, screenH);
        if (pos == null) {
            return false;
        }
        x = pos[0];
        y = pos[1];
        return true;
    }

    boolean contains(int mouseX, int mouseY) {
        return RenderUtil.hovered(mouseX, mouseY, x, y, W, H);
    }

    boolean isSearching() {
        return searching;
    }

    void unfocus() {
        searching = false;
    }

    // ---- Geometry ----

    private static List<HotbarSetting.Category> categories() {
        return HotbarSetting.sharedCategories();
    }

    /** Sidebar index of the Excluded / Set per item row. */
    private static int special() {
        return categories().size();
    }

    private float bodyY() {
        return y + TITLE_H + PAD;
    }

    private float sideX() {
        return x + PAD;
    }

    private float sideRowY(int i) {
        return bodyY() + i * ROW_H + (i == special() ? 6.75F : 0.0F);
    }

    private float sideButtonX() {
        return sideX() + SIDE_W - 2.25F - BTN_W;
    }

    private float searchX() {
        return sideX() + SIDE_W + PAD;
    }

    private float searchW() {
        return x + W - PAD - searchX();
    }

    private float gridY() {
        return bodyY() + SEARCH_H + 6.0F;
    }

    private float cellX(int i) {
        return searchX() + (i % COLS) * (CELL + GAP);
    }

    private float cellY(int i) {
        return gridY() + (i / COLS) * (CELL + GAP);
    }

    private float bandY() {
        return bodyY() + BODY_H + PAD;
    }

    private float headY() {
        return bandY() + 0.75F + 7.5F;
    }

    private float slotY() {
        return headY() + HEAD_H + 19.5F;
    }

    private float slotX(int i) {
        float row = HotbarSetting.SLOTS * SLOT + (HotbarSetting.SLOTS - 1) * SLOT_GAP;
        return x + (W - row) / 2.0F + i * (SLOT + SLOT_GAP);
    }

    private float closeX() {
        return x + W - 4.5F - CLOSE;
    }

    private float closeY() {
        return y + (TITLE_H - CLOSE) / 2.0F;
    }

    private boolean hasTabs() {
        return hotbar != null && cleaner != null;
    }

    /** {containerX, containerY, containerW, hotbarX, hotbarW, cleanerX, cleanerW}. */
    private float[] tabs() {
        CustomFont title = skin.heading.get();
        CustomFont font = tab.get();
        float cx = x + 10.5F + title.getStringWidth("Inventory layout") + 10.5F;
        float cy = y + (TITLE_H - 19.5F) / 2.0F;
        float w0 = font.getStringWidth("Hotbar") + 15.0F;
        float w1 = font.getStringWidth("Cleaner") + 15.0F;
        return new float[]{cx, cy, 1.5F + w0 + 1.5F + w1 + 1.5F, cx + 1.5F, w0, cx + 3.0F + w0, w1};
    }

    private float cancelW() {
        return small.get().getStringWidth("Cancel") + 9.0F;
    }

    private float cancelX() {
        return x + W - PAD - cancelW();
    }

    // ---- Model ----

    private List<HotbarSetting.Entry> entries() {
        List<HotbarSetting.Entry> source;
        if (category < special()) {
            source = categories().get(category).getEntries();
        } else {
            source = new ArrayList<HotbarSetting.Entry>();
            for (String key : cleanerTab ? cleaner.getModes().keySet() : hotbar.getExcluded()) {
                HotbarSetting.Entry entry = HotbarSetting.entryForKey(key);
                if (entry != null) { // group keys have no entry
                    source.add(entry);
                }
            }
        }
        if (search.isEmpty()) {
            return source;
        }
        String query = search.toLowerCase(Locale.ROOT);
        List<HotbarSetting.Entry> found = new ArrayList<HotbarSetting.Entry>();
        for (HotbarSetting.Entry entry : source) {
            if (entry.displayName().toLowerCase(Locale.ROOT).contains(query)) {
                found.add(entry);
            }
        }
        return found;
    }

    private static int maxScroll(int count) {
        return Math.max(0, (count + COLS - 1) / COLS - ROWS);
    }

    /** First slot holding the key, 1-based, or 0. */
    private int slotOf(String key) {
        List<List<String>> slots = hotbar.getSlots();
        for (int i = 0; i < slots.size(); i++) {
            if (slots.get(i).contains(key)) {
                return i + 1;
            }
        }
        return 0;
    }

    private String keyName(String key) {
        if (HotbarSetting.isCategoryKey(key)) {
            return categories().get(hotbar.categoryIndexOf(key)).getName() + " group";
        }
        return HotbarSetting.entryForKey(key).displayName();
    }

    private String categoryLabel() {
        if (category < special()) {
            return categories().get(category).getName();
        }
        return cleanerTab ? "Set per item" : "Excluded";
    }

    private int specialCount() {
        return entriesOf(cleanerTab ? cleaner.getModes().keySet() : hotbar.getExcluded());
    }

    private static int entriesOf(Iterable<String> keys) {
        int n = 0;
        for (String key : keys) {
            if (!HotbarSetting.isCategoryKey(key)) {
                n++;
            }
        }
        return n;
    }

    private int accentFill() {
        return (skin.accent & 0x00FFFFFF) | 0x38000000;
    }

    private int modeColor(CleanerSetting.CleanerMode mode) {
        return mode == CleanerSetting.CleanerMode.DROP ? DROP_CLR
                : mode == CleanerSetting.CleanerMode.KEEP_ONE ? KEEP_CLR : skin.dim;
    }

    private int modeLine(CleanerSetting.CleanerMode mode) {
        if (mode == CleanerSetting.CleanerMode.IGNORE) {
            return skin.milk ? skin.fieldLine : 0x4DFFFFFF;
        }
        return (modeColor(mode) & 0x00FFFFFF) | 0x99000000;
    }

    private static String modeLetter(CleanerSetting.CleanerMode mode) {
        return mode == CleanerSetting.CleanerMode.DROP ? "D" : mode == CleanerSetting.CleanerMode.KEEP_ONE ? "K" : "I";
    }

    private static String modeName(CleanerSetting.CleanerMode mode) {
        if (mode == null) {
            return "off";
        }
        return mode == CleanerSetting.CleanerMode.DROP ? "Drop" : mode == CleanerSetting.CleanerMode.KEEP_ONE ? "Keep one" : "Ignore";
    }

    private int soft() {
        return skin.milk ? skin.dim : 0xB3FFFFFF;
    }

    // ---- Rendering ----

    void render(int mouseX, int mouseY) {
        this.mouseX = mouseX;
        this.mouseY = mouseY;
        cursorCounter++;
        List<HotbarSetting.Entry> list = entries();
        scrollRow = MathHelper.clamp_int(scrollRow, 0, maxScroll(list.size()));
        int target = held != null ? slotAt(mouseX, mouseY) : -1;
        if (target >= 0 && hotbar.getSlots().get(target).contains(held)) {
            target = -1;
        }

        GlassShader.panel(x, y, W, H, skin.radius, skin.glass);
        drawTitle();
        drawSidebar();
        drawSearch();
        drawGrid(list);
        drawScrollbar(list.size());
        GlassShader.rect(x + PAD, bandY(), W - PAD * 2, 0.75F, 0.0F, skin.line, skin.line);
        if (cleanerTab) {
            drawLegend();
        } else {
            drawBand(target);
        }

        RenderUtil.beginItems();
        List<HotbarSetting.Category> cats = categories();
        for (int i = 0; i < cats.size(); i++) {
            RenderUtil.drawItemRaw(cats.get(i).getIcon(), sideX() + 4.5F, sideRowY(i) + 3.0F, 0.75F);
        }
        for (int i = 0; i < COLS * ROWS; i++) {
            int idx = scrollRow * COLS + i;
            if (idx < list.size()) {
                RenderUtil.drawItemRaw(list.get(idx).getIcon(), cellX(i) + 3.75F, cellY(i) + 3.75F, 0.9375F);
            }
        }
        if (!cleanerTab) {
            drawBandIcons();
        }
        RenderUtil.endItems();

        drawGridMarks(list);
        if (!cleanerTab) {
            drawBandMarks(target);
        }
        if (held != null) {
            // up and right of the cursor, so the slot under it stays visible
            RenderUtil.drawItem(hotbar.iconForKey(held), mouseX + 8.25F, mouseY - 24.75F, 1.125F);
        }
    }

    private void drawTitle() {
        CustomFont title = skin.heading.get();
        title.drawString("Inventory layout", x + 10.5F, y + (TITLE_H - title.getHeight()) / 2.0F, skin.strong);
        if (hasTabs()) {
            float[] t = tabs();
            GlassShader.rect(t[0], t[1], t[2], 19.5F, 4.5F, skin.hover, skin.hover);
            drawTab("Hotbar", t[3], t[1] + 1.5F, t[4], !cleanerTab);
            drawTab("Cleaner", t[5], t[1] + 1.5F, t[6], cleanerTab);
        }
        boolean hover = RenderUtil.hovered(mouseX, mouseY, closeX(), closeY(), CLOSE, CLOSE);
        if (hover) {
            GlassShader.rect(closeX(), closeY(), CLOSE, CLOSE, 3.75F, skin.hover, skin.hover);
        }
        Icons.draw(Icons.Icon.CLOSE, closeX() + 4.5F, closeY() + 4.5F, 9.0F, 2.8F, hover ? skin.strong : skin.dim);
        GlassShader.rect(x, y + TITLE_H - 0.75F, W, 0.75F, 0.0F, skin.line, skin.line);
    }

    private void drawTab(String label, float tx, float ty, float tw, boolean on) {
        if (on) {
            GlassShader.rect(tx, ty, tw, 16.5F, 3.0F, accentFill(), accentFill());
        }
        tab.get().drawCenteredInRect(label, tx, ty, tw, 16.5F, on ? skin.accent : skin.dim);
    }

    private void drawSidebar() {
        List<HotbarSetting.Category> cats = categories();
        CustomFont font = name.get();
        CustomFont count = COUNT.get();
        for (int i = 0; i <= cats.size(); i++) {
            float ry = sideRowY(i);
            boolean on = i == category;
            if (on) {
                GlassShader.rect(sideX(), ry, SIDE_W, ROW_H, 3.75F, accentFill(), accentFill());
            }
            float countRight = sideX() + SIDE_W - 3.75F;
            if (i < cats.size()) {
                drawSideButton(HotbarSetting.categoryRef(i), sideButtonX(), ry + (ROW_H - BTN_H) / 2.0F);
                countRight = sideButtonX() - 3.75F;
            } else {
                Icons.draw(Icons.Icon.BLOCKED, sideX() + 4.5F, ry + 3.0F, 12.0F, 2.6F, cleanerTab ? skin.dim : DROP_CLR);
            }
            String label = i < cats.size() ? cats.get(i).getName() : (cleanerTab ? "Set per item" : "Excluded");
            font.drawString(label, sideX() + 21.75F, ry + (ROW_H - font.getHeight()) / 2.0F, on ? skin.strong : skin.text);
            String n = String.valueOf(i < cats.size() ? cats.get(i).getEntries().size() : specialCount());
            count.drawString(n, countRight - count.getStringWidth(n), ry + (ROW_H - count.getHeight()) / 2.0F, skin.mute);
        }
        GlassShader.rect(sideX() + 4.5F, bodyY() + cats.size() * ROW_H + 3.0F, SIDE_W - 9.0F, 0.75F, 0.0F,
                skin.line, skin.line);
    }

    /** Hotbar: picks the whole group up. Cleaner: the group's mode. */
    private void drawSideButton(String key, float bx, float by) {
        if (!cleanerTab) {
            boolean on = key.equals(held);
            if (on) {
                GlassShader.rect(bx, by, BTN_W, BTN_H, 3.0F, accentFill(), accentFill());
            }
            GlassShader.stroke(bx, by, BTN_W, BTN_H, 3.0F, on ? skin.accent : (skin.milk ? skin.fieldLine : 0x24FFFFFF));
            Icons.draw(Icons.Icon.PLUS, bx + (BTN_W - 7.5F) / 2.0F, by + (BTN_H - 7.5F) / 2.0F, 7.5F, 3.1F,
                    on ? skin.accent : (skin.milk ? skin.dim : 0x99FFFFFF));
            return;
        }
        CleanerSetting.CleanerMode mode = cleaner.getMode(key);
        GlassShader.stroke(bx, by, BTN_W, BTN_H, 3.0F, mode != null ? modeLine(mode) : skin.fieldLine);
        MODE.get().drawCenteredInRect(mode != null ? modeLetter(mode) : "-", bx, by, BTN_W, BTN_H,
                mode != null ? modeColor(mode) : skin.mute);
    }

    private void drawSearch() {
        float sx = searchX();
        float sy = bodyY();
        GlassShader.rect(sx, sy, searchW(), SEARCH_H, 3.75F, skin.field, skin.field);
        GlassShader.stroke(sx, sy, searchW(), SEARCH_H, 3.75F, searching ? skin.accent : skin.fieldLine);
        Icons.draw(Icons.Icon.SEARCH, sx + 6.75F, sy + (SEARCH_H - 8.25F) / 2.0F, 8.25F, 2.4F, skin.mute);
        Widgets.text(name.get(), sx + 20.25F, sy, SEARCH_H, search, searching,
                "Search " + categoryLabel().toLowerCase(Locale.ROOT) + "...", cursorCounter, skin);
    }

    private void drawGrid(List<HotbarSetting.Entry> list) {
        int empty = skin.milk ? 0x08101520 : 0x08FFFFFF;
        for (int i = 0; i < COLS * ROWS; i++) {
            int idx = scrollRow * COLS + i;
            float cx = cellX(i);
            float cy = cellY(i);
            if (idx >= list.size()) {
                GlassShader.rect(cx, cy, CELL, CELL, 3.75F, empty, empty);
                continue;
            }
            String key = list.get(idx).getKey();
            int fill = skin.field;
            int frame = RenderUtil.hoveredExclusive(mouseX, mouseY, cx, cy, CELL, CELL) ? skin.dim : skin.fieldLine;
            if (cleanerTab) {
                CleanerSetting.CleanerMode mode = cleaner.getMode(key);
                if (mode != null) {
                    frame = modeLine(mode);
                }
            } else if (key.equals(held)) {
                fill = accentFill();
                frame = skin.accent;
            } else if (hotbar.isExcluded(key)) {
                frame = DROP_CLR;
            } else if (slotOf(key) > 0) {
                frame = (skin.accent & 0x00FFFFFF) | 0x99000000;
            }
            GlassShader.rect(cx, cy, CELL, CELL, 3.75F, fill, fill);
            GlassShader.stroke(cx, cy, CELL, CELL, 3.75F, frame);
        }
    }

    private void drawScrollbar(int count) {
        int rows = (count + COLS - 1) / COLS;
        if (rows <= ROWS) {
            return;
        }
        float tx = searchX() + GRID_W + 3.75F;
        float thumbH = GRID_H * ROWS / rows;
        float thumbY = gridY() + (GRID_H - thumbH) * scrollRow / (rows - ROWS);
        GlassShader.rect(tx, gridY(), 2.25F, GRID_H, 1.125F, skin.line, skin.line);
        int thumb = skin.milk ? skin.mute : 0x66FFFFFF;
        GlassShader.rect(tx, thumbY, 2.25F, thumbH, 1.125F, thumb, thumb);
    }

    private void drawBand(int target) {
        CustomFont section = skin.section.get();
        section.drawString("HOTBAR", x + PAD, headY() + (HEAD_H - section.getHeight()) / 2.0F, skin.mute,
                skin.sectionTracking);
        CustomFont font = hint.get();
        float ty = headY() + (HEAD_H - font.getHeight()) / 2.0F;
        if (held == null) {
            font.drawString(HOTBAR_HINT, x + W - PAD - font.getStringWidth(HOTBAR_HINT), ty, skin.mute);
        } else {
            CustomFont button = small.get();
            GlassShader.rect(cancelX(), headY(), cancelW(), HEAD_H, 3.0F, skin.field, skin.field);
            button.drawCenteredInRect("Cancel", cancelX(), headY(), cancelW(), HEAD_H, soft());
            float tx = cancelX() - 4.5F - font.getStringWidth("click a slot");
            font.drawString("click a slot", tx, ty, skin.mute);
            String heldName = keyName(held);
            tx -= 4.5F + font.getStringWidth(heldName);
            font.drawString(heldName, tx, ty, skin.strong);
            heldIconX = tx - 4.5F - 10.5F;
            tx = heldIconX - 4.5F - font.getStringWidth("Holding");
            font.drawString("Holding", tx, ty, soft());
        }

        CustomFont number = BADGE.get();
        for (int i = 0; i < HotbarSetting.SLOTS; i++) {
            float sx = slotX(i);
            float sy = slotY();
            boolean on = i == target;
            int fill = on ? (skin.accent & 0x00FFFFFF) | 0x29000000 : skin.hover;
            GlassShader.rect(sx, sy, SLOT, SLOT, 4.5F, fill, fill);
            if (on) {
                GlassShader.stroke(sx - 1.5F, sy - 1.5F, SLOT + 3.0F, SLOT + 3.0F, 6.0F,
                        (skin.accent & 0x00FFFFFF) | 0x4D000000);
            }
            GlassShader.stroke(sx, sy, SLOT, SLOT, 4.5F, on ? skin.accent : skin.fieldLine);
            number.drawString(String.valueOf(i + 1), sx + 3.0F, sy + 1.5F, skin.mute);
        }
    }

    private void drawBandIcons() {
        List<List<String>> slots = hotbar.getSlots();
        for (int i = 0; i < slots.size(); i++) {
            List<String> keys = slots.get(i);
            if (keys.isEmpty()) {
                continue;
            }
            RenderUtil.drawItemRaw(hotbar.iconForKey(keys.get(0)), slotX(i) + 6.75F, slotY() + 6.75F, 1.21875F);
            float mx = miniX(i);
            for (int k = 1; k <= Math.min(MAX_MINIS, keys.size() - 1); k++) {
                RenderUtil.drawItemRaw(hotbar.iconForKey(keys.get(k)), mx, miniY(), 0.5625F);
                mx += MINI + GAP;
            }
        }
        if (held != null) {
            RenderUtil.drawItemRaw(hotbar.iconForKey(held), heldIconX, headY() + 1.5F, 0.65625F);
        }
    }

    private void drawBandMarks(int target) {
        List<List<String>> slots = hotbar.getSlots();
        CustomFont more = BADGE.get();
        for (int i = 0; i < slots.size(); i++) {
            List<String> keys = slots.get(i);
            float sx = slotX(i);
            if (!keys.isEmpty() && HotbarSetting.isCategoryKey(keys.get(0))) {
                GlassShader.rect(sx + SLOT - 3.0F - 3.75F, slotY() + 3.0F, 3.75F, 3.75F, 0.0F, skin.accent, skin.accent);
            }
            if (keys.size() - 1 > MAX_MINIS) {
                more.drawString("+" + (keys.size() - 1 - MAX_MINIS), miniX(i) + MAX_MINIS * (MINI + GAP),
                        miniY() + (MINI - more.getHeight()) / 2.0F, skin.mute);
            }
        }
        if (target >= 0) {
            int size = slots.get(target).size();
            String text = size == 0 ? "First choice" : "Fallback " + size;
            CustomFont font = tip.get();
            float tw = font.getStringWidth(text) + 9.0F;
            float th = font.getHeight() + 3.0F;
            float tx = slotX(target) + (SLOT - tw) / 2.0F;
            float ty = slotY() - 18.75F;
            GlassShader.rect(tx, ty, tw, th, 3.0F, skin.accent, skin.accent);
            font.drawCenteredInRect(text, tx, ty, tw, th, skin.onAccent);
        }
    }

    /** Fallback icons sit centered under their slot. */
    private float miniX(int slot) {
        int size = hotbar.getSlots().get(slot).size() - 1;
        int shown = Math.min(MAX_MINIS, size);
        float w = shown * MINI + Math.max(0, shown - 1) * GAP;
        if (size > MAX_MINIS) {
            w += GAP + BADGE.get().getStringWidth("+" + (size - MAX_MINIS));
        }
        return slotX(slot) + (SLOT - w) / 2.0F;
    }

    private float miniY() {
        return slotY() + SLOT + 3.75F;
    }

    private void drawGridMarks(List<HotbarSetting.Entry> list) {
        CustomFont badge = BADGE.get();
        CustomFont bold = BADGE_BOLD.get();
        for (int i = 0; i < COLS * ROWS; i++) {
            int idx = scrollRow * COLS + i;
            if (idx >= list.size()) {
                break;
            }
            String key = list.get(idx).getKey();
            float cx = cellX(i);
            float cy = cellY(i);
            if (cleanerTab) {
                CleanerSetting.CleanerMode mode = cleaner.getMode(key);
                if (mode != null) {
                    String letter = modeLetter(mode);
                    bold.drawString(letter, cx + CELL - 1.5F - bold.getStringWidth(letter), cy + 0.75F, modeColor(mode));
                }
                continue;
            }
            if (hotbar.isExcluded(key) && category < special()) {
                GlassShader.rect(cx, cy, CELL, CELL, 3.75F, EXCLUDE_OVERLAY, EXCLUDE_OVERLAY);
            }
            int slot = slotOf(key);
            if (slot > 0) {
                String n = String.valueOf(slot);
                badge.drawString(n, cx + CELL - 1.5F - badge.getStringWidth(n), cy + CELL - 0.75F - badge.getHeight(),
                        skin.accent);
            }
        }
    }

    private void drawLegend() {
        CustomFont section = skin.section.get();
        section.drawString("CLEANER MODES", x + PAD, headY() + (HEAD_H - section.getHeight()) / 2.0F, skin.mute,
                skin.sectionTracking);
        CustomFont letter = LETTER.get();
        CustomFont label = name.get();
        float cx = x + PAD;
        float cy = headY() + HEAD_H + 9.0F;
        for (CleanerSetting.CleanerMode mode : CleanerSetting.CleanerMode.values()) {
            String l = modeLetter(mode);
            String n = modeName(mode);
            float cw = 7.5F + letter.getStringWidth(l) + 4.5F + label.getStringWidth(n) + 7.5F;
            GlassShader.stroke(cx, cy, cw, 19.5F, 3.75F, modeLine(mode));
            letter.drawString(l, cx + 7.5F, cy + (19.5F - letter.getHeight()) / 2.0F, modeColor(mode));
            label.drawString(n, cx + 7.5F + letter.getStringWidth(l) + 4.5F, cy + (19.5F - label.getHeight()) / 2.0F,
                    skin.text);
            cx += cw + 6.0F;
        }
        CustomFont font = hint.get();
        float ly = cy + 19.5F + 9.0F;
        for (String line : font.wrapToWidth(CLEANER_HINT, Math.round(W - PAD * 2))) {
            font.drawString(line, x + PAD, ly, skin.mute);
            ly += 12.0F;
        }
    }

    // ---- Input ----

    private int slotAt(int mx, int my) {
        for (int i = 0; i < HotbarSetting.SLOTS; i++) {
            if (RenderUtil.hoveredExclusive(mx, my, slotX(i), slotY(), SLOT, SLOT)) {
                return i;
            }
        }
        return -1;
    }

    /** {slot, index in the slot} of the fallback icon under the cursor, or null. */
    private int[] miniAt(int mx, int my) {
        List<List<String>> slots = hotbar.getSlots();
        for (int i = 0; i < slots.size(); i++) {
            float fx = miniX(i);
            for (int k = 1; k <= Math.min(MAX_MINIS, slots.get(i).size() - 1); k++) {
                if (RenderUtil.hoveredExclusive(mx, my, fx, miniY(), MINI, MINI)) {
                    return new int[]{i, k};
                }
                fx += MINI + GAP;
            }
        }
        return null;
    }

    private HotbarSetting.Entry entryAt(int mx, int my) {
        List<HotbarSetting.Entry> list = entries();
        for (int i = 0; i < COLS * ROWS; i++) {
            int idx = scrollRow * COLS + i;
            if (idx < list.size() && RenderUtil.hoveredExclusive(mx, my, cellX(i), cellY(i), CELL, CELL)) {
                return list.get(idx);
            }
        }
        return null;
    }

    void mouseClicked(int mx, int my, int button) {
        searching = false;
        if (button == 0 && RenderUtil.hovered(mx, my, closeX(), closeY(), CLOSE, CLOSE)) {
            onClose.run();
            return;
        }
        if (button == 0 && hasTabs()) {
            float[] t = tabs();
            if (RenderUtil.hovered(mx, my, t[3], t[1], t[4], 19.5F)) {
                showTab(false);
                return;
            }
            if (RenderUtil.hovered(mx, my, t[5], t[1], t[6], 19.5F)) {
                showTab(true);
                return;
            }
        }
        if (button == 0 && RenderUtil.hovered(mx, my, x, y, W, TITLE_H)) {
            move.begin(mx, my, Math.round(x), Math.round(y));
            moved = true;
            return;
        }
        List<HotbarSetting.Category> cats = categories();
        for (int i = 0; i <= cats.size(); i++) {
            float ry = sideRowY(i);
            if (!RenderUtil.hoveredExclusive(mx, my, sideX(), ry, SIDE_W, ROW_H)) {
                continue;
            }
            if (i < cats.size() && RenderUtil.hovered(mx, my, sideButtonX(), ry + (ROW_H - BTN_H) / 2.0F, BTN_W, BTN_H)) {
                clickKey(HotbarSetting.categoryRef(i), button);
            } else if (button == 0) {
                category = i;
                search = "";
                scrollRow = 0;
            }
            return;
        }
        if (button == 0 && RenderUtil.hovered(mx, my, searchX(), bodyY(), searchW(), SEARCH_H)) {
            searching = true;
            cursorCounter = 0;
            return;
        }
        HotbarSetting.Entry entry = entryAt(mx, my);
        if (entry != null) {
            clickKey(entry.getKey(), button);
            if (!cleanerTab && button == 1) {
                hotbar.toggleItem(entry.getKey());
                save();
            }
            return;
        }
        if (cleanerTab) {
            return;
        }
        if (held != null && button == 0 && RenderUtil.hovered(mx, my, cancelX(), headY(), cancelW(), HEAD_H)) {
            held = null;
            return;
        }
        int slot = slotAt(mx, my);
        List<List<String>> slots = hotbar.getSlots();
        if (slot >= 0) {
            if (button == 0 && held != null) {
                drop(slot);
            } else if (button == 1 && !slots.get(slot).isEmpty()) {
                hotbar.removeFromSlot(slot, slots.get(slot).get(0));
                save();
            }
            return;
        }
        int[] mini = miniAt(mx, my);
        if (mini != null && button == 1) {
            hotbar.removeFromSlot(mini[0], slots.get(mini[0]).get(mini[1]));
            save();
        }
    }

    /** Hotbar: left picks the key up. Cleaner: left and right cycle its mode. */
    private void clickKey(String key, int button) {
        if (cleanerTab) {
            if (button == 0) {
                cleaner.cycle(key);
            } else if (button == 1) {
                cleaner.cycleBack(key);
            }
            save();
        } else if (button == 0) {
            held = key.equals(held) ? null : key;
            carrying = held != null;
        }
    }

    private void drop(int slot) {
        hotbar.dropOnSlot(slot, held);
        held = null;
        save();
    }

    /** A press that picked something up and let go over a slot drops it there. */
    void mouseReleased() {
        move.end();
        if (carrying && held != null) {
            int slot = slotAt(mouseX, mouseY);
            if (slot >= 0) {
                drop(slot);
            }
        }
        carrying = false;
    }

    void scroll(int wheel, int mx, int my) {
        if (RenderUtil.hovered(mx, my, searchX(), gridY(), searchW(), GRID_H)) {
            scrollRow = MathHelper.clamp_int(scrollRow + (wheel > 0 ? -1 : 1), 0, maxScroll(entries().size()));
        }
    }

    void charTyped(char typedChar, int keyCode) {
        CustomTextInput.EditResult edit = CustomTextInput.edit(search, typedChar, keyCode, -1);
        searching = edit.isFocused();
        if (edit.isChanged()) {
            search = edit.getValue();
            scrollRow = 0;
        }
    }

    String getTooltipAt(int mx, int my) {
        HotbarSetting.Entry entry = entryAt(mx, my);
        if (entry != null) {
            return entry.displayName();
        }
        List<HotbarSetting.Category> cats = categories();
        for (int i = 0; i < cats.size(); i++) {
            if (RenderUtil.hovered(mx, my, sideButtonX(), sideRowY(i) + (ROW_H - BTN_H) / 2.0F, BTN_W, BTN_H)) {
                String group = cats.get(i).getName();
                return cleanerTab ? group + " group: " + modeName(cleaner.getMode(HotbarSetting.categoryRef(i)))
                        : "Pick up the whole " + group + " group";
            }
        }
        if (cleanerTab || held != null) {
            return null; // the drop tip is the feedback while holding
        }
        int[] mini = miniAt(mx, my);
        if (mini != null) {
            return keyName(hotbar.getSlots().get(mini[0]).get(mini[1])) + ". Right-click to remove.";
        }
        int slot = slotAt(mx, my);
        if (slot < 0) {
            return null;
        }
        List<String> keys = hotbar.getSlots().get(slot);
        if (keys.isEmpty()) {
            return "Slot " + (slot + 1) + " is empty.";
        }
        StringBuilder text = new StringBuilder("Slot ").append(slot + 1).append(": ");
        for (int k = 0; k < keys.size(); k++) {
            text.append(k == 0 ? "" : ", then ").append(keyName(keys.get(k)));
        }
        return text.append(". Right-click to remove the first choice.").toString();
    }

    private static void save() {
        ColdPlay.getInstance().saveConfig();
    }
}
