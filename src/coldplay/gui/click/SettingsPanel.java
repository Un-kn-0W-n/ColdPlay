package coldplay.gui.click;

import coldplay.ColdPlay;
import coldplay.gui.StyledButton;
import coldplay.gui.Theme;
import coldplay.gui.CustomSearchField;
import coldplay.gui.CustomTextInput;
import coldplay.module.Module;
import coldplay.setting.BedGridSetting;
import coldplay.setting.BooleanSetting;
import coldplay.setting.ButtonSetting;
import coldplay.setting.CleanerSetting;
import coldplay.setting.ColorSetting;
import coldplay.setting.HeaderSetting;
import coldplay.setting.HotbarSetting;
import coldplay.setting.ItemGridSetting;
import coldplay.setting.ItemPicker;
import coldplay.setting.ModeSetting;
import coldplay.setting.NumberSetting;
import coldplay.setting.RangeSetting;
import coldplay.setting.Setting;
import coldplay.util.Animation;
import coldplay.util.RenderUtil;
import coldplay.util.font.CustomFont;
import coldplay.util.font.Fonts;

import net.minecraft.util.MathHelper;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Edits one module's settings. Starts docked to its category row; a header drag detaches it.
 * Docked content and icons share the animated clip bounds, while ClickGuiScreen draws the union border.
 * Position is transient.
 */
public class SettingsPanel {
    private static final int MIN_WIDTH = 176;   // floor: wide enough for nine native-resolution cells
    private static final int HEADER = ClickGuiScreen.HEADER_HEIGHT;
    private static final int ROW_H = 16;        // boolean + mode rows
    private static final int NUMBER_H = 26;     // label row + slider row
    private static final int HEADER_ROW_H = 13; // section-divider row

    private static final int MAX_BODY_H = 300;
    private static final int VIEWPORT_BOTTOM_PAD = 4;
    private static final int BODY_SCROLL_STEP = 18;
    private static final int TRACK_INSET = 6;
    private static final int INDENT_STEP = 8;   // px added to a row's label x per indent level
    private static final int CHEV_GAP = 2;      // gap between a mode value and its hover chevrons

    private static final int GRID_COLS = 9;     // hotbar-width: nine cells per row, palette and slots align
    private static final int CELL = 18;         // cell box: 16px icon (native, crisp) + 1px frame each side
    private static final int COL_GAP = 1;       // horizontal gap between cells
    private static final int ROW_GAP = 2;       // vertical gap between wrapped rows
    private static final int GRID_LABEL_H = 14; // setting-name label row above the cells
    private static final int GRID_BOT_PAD = 4;  // padding below the last cell row

    private static final int BED_COLS = 2 * BedGridSetting.MAX_RING + 1; // lx in [-MAX_RING, MAX_RING]
    private static final int BED_ROWS = 2 * BedGridSetting.MAX_RING + 2; // lz in [-MAX_RING, 1 + MAX_RING]
    private static final int BED_CELL = 14;     // px per column cell (no icon inside, so smaller than CELL)
    private static final int BED_GAP = 1;       // gap between bed-grid cells

    private static final int HB_SEP = 4;        // gap between the borderless palette block and the hotbar row

    private static final int WIDGET_PAD = 4;    // vertical padding inside the open picker panel
    private static final int SEARCH_H = 14;     // category search-box height
    private static final int VIEW_ROWS = 6;     // visible category-grid rows
    private static final int EXCLUDED_CLR = Theme.DANGER;  // muted red: item clicked-off / Cleaner DROP
    private static final int CAT_CLR = Theme.FROST;        // a whole-category slot binding is a selection
    private static final int EXCLUDE_OVERLAY = 0xC8121212; // dim wash over excluded item icons
    private static final int CLEAN_KEEP_CLR = 0xFF6FCF6F;  // green: Cleaner KEEP_ONE

    private static final int CP_PAD = 5;     // inner padding around the picker contents (clears the bold frame)
    private static final int SB_SIZE = 72;   // saturation/brightness square edge (px)
    private static final int HUE_W = 10;      // hue bar width (px), same height as the SB square
    private static final int CP_GAP = 4;     // gap between the SB square and the hue bar
    private static final int CP_STEP = 4;     // SB square is tiled in CP_STEP cells (no per-vertex gradient exists)
    private static final int HUE_STEP = 3;    // hue bar is tiled in HUE_STEP slices
    private static final int CP_SWATCH = 11;  // colour swatch drawn in the setting row

    private final ClickGuiScreen screen;
    private final Module module;
    private final int width;                    // fits the widest row, floored at MIN_WIDTH
    private int x;
    private int y;

    private CategoryPanel dockHost;    // non-null while docked; null once detached by a header drag
    private boolean dockRight = true;  // which side of the host the seam is on
    private boolean flush;             // seam adjacent this frame -> the union silhouette applies
    /** Dock-wipe progress: the panel grows out of the seam on open (0 -> 1); detach snaps to 1. */
    private final Animation dockAnim = new Animation(0.0, Theme.WIPE_SPEED);
    private int effX;                  // wipe-clipped rect as drawn this frame (anchored at the seam)
    private int effW;
    private int lastScaleFactor = 1;   // for renderItems' matching scissor

    // Scroll only the body so the header remains anchored.
    private int bodyScroll;
    private boolean draggingBodyScroll;

    private final WindowDrag windowDrag = new WindowDrag();
    private NumberSetting draggingSlider;
    private RangeSetting draggingRange;
    private int draggingRangeHandle;    // 0 = lo thumb, 1 = hi thumb

    private ColorSetting activeColor;   // non-null while the picker is open
    private int cpX;
    private int cpY;
    private int cpW;
    private int cpH;
    private int cpSbX;                   // top-left of the SB square
    private int cpSbY;
    private int cpHueX;                  // left of the hue bar (its top == cpSbY)
    private float curH;                  // current HSB while the picker is open
    private float curS;
    private float curB;
    private boolean draggingSB;
    private boolean draggingHue;

    private int dragMouseX;
    private int dragMouseY;

    private ItemPicker searchingPicker;          // which picker's search box currently has keyboard focus
    private HotbarSetting draggingHotbar;        // AutoHotBar we're dragging an item/category out of
    private String draggingKey;                  // the item/category key being dragged to a slot
    private int cursorCounter;                    // frame counter for the blinking search caret

    public SettingsPanel(ClickGuiScreen screen, Module module, CategoryPanel host,
                         int screenWidth, int screenHeight) {
        this.screen = screen;
        this.module = module;
        this.width = computeWidth(); // before layoutDocked: the dock side/clamp math reads it
        this.dockHost = host;
        layoutDocked(screenWidth, screenHeight);
    }

    public Module getModule() {
        return module;
    }

    /**
     * Measure hidden rows too so revealing settings cannot resize the panel.
     * width is fixed until the GUI reopens; remeasure if font-atlas changes require it.
     */
    private int computeWidth() {
        CustomFont font = Fonts.medium;
        if (font == null) {
            return MIN_WIDTH; // atlases not baked yet; the floor still fits the grids
        }
        int w = MIN_WIDTH;
        for (Setting<?> s : module.getSettings()) {
            int vw = valueWidth(s);
            w = Math.max(w, 5 + s.getIndent() * INDENT_STEP + font.getStringWidth(s.getDisplayName())
                    + (vw > 0 ? 4 + vw : 0) + 5);
        }
        return w;
    }

    /**
     * Reserve the widest possible mode/number value so changing it cannot crowd the label.
     * Other controls fit within MIN_WIDTH.
     */
    private int valueWidth(Setting<?> s) {
        CustomFont vf = Fonts.list;
        if (s instanceof ModeSetting) {
            int widest = 0;
            for (String mode : ((ModeSetting) s).getModes()) {
                widest = Math.max(widest, vf.getStringWidth(mode));
            }
            return widest + 2 * (vf.getStringWidth(">") + CHEV_GAP);
        }
        if (s instanceof NumberSetting) {
            NumberSetting n = (NumberSetting) s;
            return vf.getStringWidth(worstValueText(n.getMin(), n.getMax(), n.getIncrement()));
        }
        if (s instanceof RangeSetting) {
            RangeSetting r = (RangeSetting) s;
            String worst = worstValueText(r.getMin(), r.getMax(), r.getIncrement()) + r.getUnit();
            return vf.getStringWidth(worst + " - " + worst);
        }
        return 0;
    }

    /**
     * Recompute after host drags, preferring the right side when it fits. Keep the owning row's Y;
     * the body viewport handles height limits. A missing row returns false so the screen closes the panel.
     */
    public boolean layoutDocked(int screenWidth, int screenHeight) {
        if (dockHost == null) {
            return true;
        }
        int rowY = dockHost.rowTop(module);
        if (rowY < 0) {
            return false;
        }
        boolean fitsRight = dockHost.getX() + dockHost.getWidth() + width <= screenWidth;
        boolean fitsLeft = dockHost.getX() - width >= 0;
        flush = fitsRight || fitsLeft;
        if (flush) {
            dockRight = fitsRight;
            x = dockRight ? dockHost.getX() + dockHost.getWidth() : dockHost.getX() - width;
        } else {
            // A union border requires a flush seam; overlapping windows draw separate borders.
            dockRight = screenWidth - (dockHost.getX() + dockHost.getWidth()) >= dockHost.getX();
            x = dockRight ? dockHost.getX() + dockHost.getWidth() : dockHost.getX() - width;
            x = MathHelper.clamp_int(x, 0, Math.max(0, screenWidth - width));
        }
        y = rowY;
        return true;
    }

    private void detach() {
        dockHost = null;
        flush = false;
        dockAnim.set(1.0);
    }

    public boolean isDocked() {
        return dockHost != null;
    }

    public boolean isDockedTo(CategoryPanel panel) {
        return dockHost == panel;
    }

    public CategoryPanel getDockHost() {
        return dockHost;
    }

    public boolean isFlush() {
        return flush;
    }

    public boolean isDockRight() {
        return dockRight;
    }

    public int getX() {
        return x;
    }

    public int getY() {
        return y;
    }

    public int getHeight() {
        return HEADER + bodyHeight();
    }

    private int contentHeight() {
        int h = 0;
        for (Setting<?> s : module.getSettings()) {
            if (s.isVisible()) {
                h += rowHeight(s);
            }
        }
        return h;
    }

    /** Keep a usable 96px body floor, even when a low dock causes some overflow. */
    private int bodyHeight() {
        int available = Math.max(96, screen.height - y - HEADER - VIEWPORT_BOTTOM_PAD);
        return Math.min(contentHeight(), Math.min(MAX_BODY_H, available));
    }

    private int maxBodyScroll() {
        return Math.max(0, contentHeight() - bodyHeight());
    }

    private void clampBodyScroll() {
        bodyScroll = MathHelper.clamp_int(bodyScroll, 0, maxBodyScroll());
    }

    private int bodyTopY() {
        return y + HEADER - bodyScroll;
    }

    private boolean bodyContains(int mouseX, int mouseY) {
        return bodyHeight() > 0 && RenderUtil.hovered(mouseX, mouseY, x, y + HEADER, width, bodyHeight());
    }

    private boolean hasBodyScroll() {
        return maxBodyScroll() > 0 && scrollbarTrackHeight() > 0;
    }

    /** A one-pixel edge indicator preserves all nine grid columns while advertising scroll position. */
    private int scrollbarTrackX() {
        return x + width - 2;
    }

    private int scrollbarTrackY() {
        return y + HEADER + 2;
    }

    private int scrollbarTrackHeight() {
        return Math.max(0, bodyHeight() - 4);
    }

    private int scrollbarThumbHeight() {
        return RenderUtil.scrollThumbHeight(scrollbarTrackHeight(), bodyHeight(), contentHeight());
    }

    private int scrollbarThumbY() {
        return scrollbarTrackY() + RenderUtil.scrollThumbOffset(scrollbarTrackHeight(),
                scrollbarThumbHeight(), bodyScroll, maxBodyScroll());
    }

    private boolean scrollbarContains(int mouseX, int mouseY) {
        return hasBodyScroll() && RenderUtil.hovered(mouseX, mouseY, scrollbarTrackX() - 1,
                y + HEADER, 3, bodyHeight());
    }

    private void updateBodyScroll(int mouseY) {
        int travel = scrollbarTrackHeight() - scrollbarThumbHeight();
        if (travel <= 0 || maxBodyScroll() <= 0) {
            return;
        }
        double progress = clamp01((mouseY - scrollbarTrackY() - scrollbarThumbHeight() / 2.0) / travel);
        bodyScroll = (int) Math.round(progress * maxBodyScroll());
        clampBodyScroll();
    }

    private void drawBodyScrollbar() {
        if (!hasBodyScroll()) {
            return;
        }
        int trackX = scrollbarTrackX();
        int trackY = scrollbarTrackY();
        RenderUtil.rect(trackX, trackY, 1, scrollbarTrackHeight(), Theme.SEP);
        RenderUtil.rect(trackX, scrollbarThumbY(), 1, scrollbarThumbHeight(),
                draggingBodyScroll ? Theme.TEXT : Theme.FROST);
    }

    /** Starts a scissor that is the intersection of the dock wipe and the scrollable body viewport. */
    private boolean beginBodyScissor() {
        int h = bodyHeight();
        int left = Math.max(x, effX);
        int right = Math.min(x + width, effX + effW);
        if (h <= 0 || right <= left) {
            return false;
        }
        RenderUtil.beginScissor(left, y + HEADER, right - left, h, lastScaleFactor);
        return true;
    }

    /** Left edge of the wipe-clipped rect drawn this frame (== {@link #getX} once fully open). */
    public int getEffectiveX() {
        return effX;
    }

    /** Width of the wipe-clipped rect drawn this frame; the union silhouette traces this. */
    public int getEffectiveWidth() {
        return effW;
    }

    /** Includes the color picker, which may extend outside the panel. */
    public boolean contains(int mouseX, int mouseY) {
        if (RenderUtil.hovered(mouseX, mouseY, x, y, width, getHeight())) {
            return true;
        }
        return activeColor != null && RenderUtil.hovered(mouseX, mouseY, cpX, cpY, cpW, cpH);
    }

    public String getTooltipAt(int mouseX, int mouseY) {
        if (activeColor != null && RenderUtil.hovered(mouseX, mouseY, cpX, cpY, cpW, cpH)) {
            return null;
        }
        int rowY = bodyTopY();
        for (Setting<?> s : module.getSettings()) {
            if (!s.isVisible()) {
                continue;
            }
            int rh = rowHeight(s);
            if (RenderUtil.hovered(mouseX, mouseY, x, rowY, width, rh)) {
                return s.getDescription();
            }
            rowY += rh;
        }
        return null;
    }

    private int rowHeight(Setting<?> s) {
        if (s instanceof NumberSetting || s instanceof RangeSetting) {
            return NUMBER_H;
        }
        if (s instanceof ItemGridSetting) {
            return gridHeight(((ItemGridSetting) s).getEntries().size());
        }
        if (s instanceof BedGridSetting) {
            return bedGridHeight();
        }
        if (s instanceof HotbarSetting) {
            return hotbarHeight((HotbarSetting) s);
        }
        if (s instanceof CleanerSetting) {
            return cleanerHeight((CleanerSetting) s);
        }
        if (s instanceof HeaderSetting) {
            return HEADER_ROW_H;
        }
        return ROW_H;
    }

    private int gridInset() {
        return (width - (GRID_COLS * CELL + (GRID_COLS - 1) * COL_GAP)) / 2;
    }

    private int rowsFor(int count) {
        return Math.max(1, (count + GRID_COLS - 1) / GRID_COLS);
    }

    private int gridHeight(int count) {
        int rows = rowsFor(count);
        return GRID_LABEL_H + rows * CELL + (rows - 1) * ROW_GAP + GRID_BOT_PAD;
    }

    private int colX(int index) {
        return x + gridInset() + (index % GRID_COLS) * (CELL + COL_GAP);
    }

    private int cellY(int rowY, int index) {
        return rowY + GRID_LABEL_H + (index / GRID_COLS) * (CELL + ROW_GAP);
    }

    private int bedGridHeight() {
        return GRID_LABEL_H + BED_ROWS * BED_CELL + (BED_ROWS - 1) * BED_GAP + GRID_BOT_PAD;
    }

    private int bedInset() {
        return (width - (BED_COLS * BED_CELL + (BED_COLS - 1) * BED_GAP)) / 2;
    }

    /** Top-left x of the grid column for bed-local {@code lx} (in [-MAX_RING, MAX_RING]). */
    private int bedCellX(int lx) {
        return x + bedInset() + (lx + BedGridSetting.MAX_RING) * (BED_CELL + BED_GAP);
    }

    /** Top-left y of the grid row for bed-local {@code lz} (in [-MAX_RING, 1+MAX_RING]), row at {@code rowY}. */
    private int bedCellY(int rowY, int lz) {
        return rowY + GRID_LABEL_H + (lz + BedGridSetting.MAX_RING) * (BED_CELL + BED_GAP);
    }

    private int gridStripWidth() {
        return GRID_COLS * CELL + (GRID_COLS - 1) * COL_GAP;
    }

    private int gridViewH() {
        return VIEW_ROWS * CELL + (VIEW_ROWS - 1) * ROW_GAP;
    }

    private int pickerCatY(int rowY) {
        return rowY + ROW_H + WIDGET_PAD;
    }

    private int pickerSearchY(int rowY) {
        return pickerCatY(rowY) + CELL + WIDGET_PAD;
    }

    private int pickerGridY(int rowY) {
        return pickerSearchY(rowY) + SEARCH_H + WIDGET_PAD;
    }

    /** Fallback depth excludes the primary item in each slot. */
    private int maxFallbacks(HotbarSetting hb) {
        int maxF = 0;
        for (List<String> slot : hb.getSlots()) {
            maxF = Math.max(maxF, slot.size() - 1);
        }
        return maxF;
    }

    private int hotbarStripY(HotbarSetting hb, int rowY) {
        if (hb.getOpenCategory() >= 0) {
            return pickerGridY(rowY) + gridViewH() + WIDGET_PAD;
        }
        return pickerCatY(rowY) + CELL + HB_SEP;
    }

    private int hotbarHeight(HotbarSetting hb) {
        if (!hb.isWidgetOpen()) {
            return ROW_H;
        }
        int stripTop = hotbarStripY(hb, 0); // offset from the row top (rowY == 0)
        int stripH = CELL + maxFallbacks(hb) * (CELL + ROW_GAP);
        return stripTop + stripH + WIDGET_PAD;
    }

    private int cleanerHeight(CleanerSetting cg) {
        if (!cg.isWidgetOpen()) {
            return ROW_H;
        }
        if (cg.getOpenCategory() < 0) {
            return pickerCatY(0) + CELL + WIDGET_PAD;
        }
        return pickerGridY(0) + gridViewH() + WIDGET_PAD;
    }

    private int maxRowFor(ItemPicker picker) {
        return Math.max(0, rowsFor(picker.getFilteredEntries().size()) - VIEW_ROWS);
    }

    private int rowOffsetFor(ItemPicker picker) {
        return MathHelper.clamp_int((int) Math.round(picker.getScroll()), 0, maxRowFor(picker));
    }

    /** Docked panels use renderContent so the screen can draw the shared border. */
    public void render(int mouseX, int mouseY, int scaleFactor) {
        renderContent(mouseX, mouseY, scaleFactor);
        renderChrome();
    }

    /**
     * Keep content at its final coordinates while clipping the dock animation outward from the seam.
     * effX/effW also define the union border and item-icon clip bounds.
     */
    public void renderContent(int mouseX, int mouseY, int scaleFactor) {
        CustomFont font = Fonts.medium;
        if (font == null) {
            return;
        }
        cursorCounter++;
        lastScaleFactor = scaleFactor;
        clampBodyScroll();
        int height = getHeight();

        double p = Theme.step(dockAnim, 1.0);
        effW = (int) Math.round(width * p);
        effX = dockRight ? x : x + width - effW;
        boolean clipped = effW < width;
        if (clipped && effW <= 0) {
            return; // wipe not visibly started this frame
        }
        if (clipped) {
            RenderUtil.beginScissor(effX, y, effW, height, scaleFactor);
        }

        ClickGuiScreen.drawWindowBase(x, y, width, height);
        ClickGuiScreen.drawWindowHeader(font, module.getName(), x, y, width, mouseX, mouseY);

        if (clipped) {
            RenderUtil.endScissor();
        }
        if (!beginBodyScissor()) {
            return;
        }

        List<Setting<?>> settings = module.getSettings();
        int rowY = bodyTopY();
        for (int i = 0; i < settings.size(); i++) {
            Setting<?> s = settings.get(i);
            if (!s.isVisible()) {
                continue;
            }
            int groupH = ownerGroupHeight(settings, i);
            if (groupH > 0) {
                RenderUtil.rect(labelX(s) - 3, rowY, Theme.TICK_PX, groupH, Theme.FROST);
            }
            if (s instanceof BooleanSetting) {
                renderBoolean(font, (BooleanSetting) s, rowY, mouseX, mouseY);
            } else if (s instanceof ModeSetting) {
                renderMode(font, (ModeSetting) s, rowY, mouseX, mouseY);
            } else if (s instanceof NumberSetting) {
                renderNumber(font, (NumberSetting) s, rowY);
            } else if (s instanceof RangeSetting) {
                renderRange(font, (RangeSetting) s, rowY);
            } else if (s instanceof ColorSetting) {
                renderColor(font, (ColorSetting) s, rowY, mouseX, mouseY);
            } else if (s instanceof ItemGridSetting) {
                renderItemGrid(font, (ItemGridSetting) s, rowY, mouseX, mouseY);
            } else if (s instanceof BedGridSetting) {
                renderBedGrid(font, (BedGridSetting) s, rowY, mouseX, mouseY);
            } else if (s instanceof HotbarSetting) {
                drawHotbar(font, (HotbarSetting) s, rowY, mouseX, mouseY);
            } else if (s instanceof CleanerSetting) {
                drawCleaner(font, (CleanerSetting) s, rowY, mouseX, mouseY);
            } else if (s instanceof HeaderSetting) {
                renderHeader(font, (HeaderSetting) s, rowY);
            } else if (s instanceof ButtonSetting) {
                int buttonX = labelX(s);
                StyledButton.draw(font, s.getDisplayName(), buttonX, rowY + 2, x + width - 5 - buttonX,
                        ROW_H - 4, mouseX, mouseY, true, Theme.WELL, Theme.TEXT);
            }
            rowY += rowHeight(s);
        }
        drawBodyScrollbar();
        RenderUtil.endScissor();
    }

    public void renderChrome() {
        ClickGuiScreen.drawWindowFrame(x, y, width, getHeight());
    }

    private int labelX(Setting<?> s) {
        return x + 5 + s.getIndent() * INDENT_STEP;
    }

    /** Indentation defines each enabled boolean's child group for the shared state marker. */
    private int ownerGroupHeight(List<Setting<?>> settings, int i) {
        Setting<?> s = settings.get(i);
        if (!(s instanceof BooleanSetting) || !((BooleanSetting) s).get()) {
            return 0;
        }
        int childH = 0;
        for (int j = i + 1; j < settings.size(); j++) {
            Setting<?> c = settings.get(j);
            if (!c.isVisible()) {
                continue;
            }
            if (c.getIndent() <= s.getIndent()) {
                break;
            }
            childH += rowHeight(c);
        }
        return childH > 0 ? rowHeight(s) + childH : 0;
    }

    /** Align value and label baselines across the two font sizes. */
    private static float valueTop(float labelTop) {
        return labelTop + (Fonts.medium.getAscent() - Fonts.list.getAscent());
    }

    /** Trim labels before controls so the editable value remains visible. */
    private void drawLabel(CustomFont font, Setting<?> s, int rowY, boolean centered, int valueLeft) {
        float ty = centered ? rowY + (ROW_H - font.getHeight()) / 2f : rowY + 3;
        int lx = labelX(s);
        font.drawString(font.trimToWidth(s.getDisplayName(), valueLeft - 4 - lx, ".."), lx, ty, labelColor(s));
    }

    private int labelColor(Setting<?> s) {
        return s.getIndent() > 0 ? Theme.TEXT_DIM : Theme.TEXT;
    }

    private void drawSliderLabel(CustomFont font, Setting<?> s, int rowY, int valueLeft) {
        int lx = labelX(s);
        String name = font.trimToWidth(s.getDisplayName(), valueLeft - 4 - lx, "..");
        font.drawString(name, lx, rowY + 3, labelColor(s));
    }

    private void drawGridCells(int rowY, int count, java.util.function.IntPredicate enabled, int mouseX, int mouseY) {
        for (int i = 0; i < count; i++) {
            int cx = colX(i);
            int cy = cellY(rowY, i);
            drawCell(cx, cy, CELL, enabled.test(i), RenderUtil.hovered(mouseX, mouseY, cx, cy, CELL, CELL));
        }
    }

    private void renderBoolean(CustomFont font, BooleanSetting s, int rowY, int mouseX, int mouseY) {
        int box = 11;
        int bx = x + width - box - 5;
        drawLabel(font, s, rowY, true, bx);
        int by = rowY + (ROW_H - box) / 2;
        // hover matches the click hit zone (the whole row), not just the little box
        boolean hover = RenderUtil.hovered(mouseX, mouseY, x, rowY, width, ROW_H);
        RenderUtil.drawBorderedRect(bx, by, bx + box, by + box, Theme.WELL,
                hover ? Theme.CONTOUR : Theme.SEP);
        if (s.get()) {
            RenderUtil.rect(bx + 2, by + 2, box - 4, box - 4, Theme.FROST);
        }
    }

    /** {valueX, leftChevX, rightChevX, chevW} for a mode row — one source for render and hit-test.
     *  The right-chevron slot is reserved permanently so nothing jumps when hover reveals it. */
    private int[] modeValueGeometry(ModeSetting s) {
        CustomFont vf = Fonts.list;
        int chevW = vf.getStringWidth(">");
        int valueRight = x + width - 5 - (chevW + CHEV_GAP);
        int valueX = valueRight - vf.getStringWidth(s.get());
        return new int[]{valueX, valueX - CHEV_GAP - chevW, x + width - 5 - chevW, chevW};
    }

    private void renderMode(CustomFont font, ModeSetting s, int rowY, int mouseX, int mouseY) {
        int[] g = modeValueGeometry(s);
        drawLabel(font, s, rowY, true, g[1]);
        float vy = valueTop(rowY + (ROW_H - font.getHeight()) / 2f);
        Fonts.list.drawString(s.get(), g[0], vy, Theme.TEXT_DIM);
        int arrowColor = RenderUtil.hovered(mouseX, mouseY, x, rowY, width, ROW_H) ? Theme.TEXT : Theme.TEXT_MUTE;
        Fonts.list.drawString("<", g[1], vy, arrowColor);
        Fonts.list.drawString(">", g[2], vy, arrowColor);
    }

    private SliderTrack sliderTrack(int rowY) {
        return new SliderTrack(x + TRACK_INSET, rowY + 17, width - TRACK_INSET * 2);
    }

    private void renderNumber(CustomFont font, NumberSetting s, int rowY) {
        String val = formatValue(s.get());
        int vw = Fonts.list.getStringWidth(val);
        int valueLeft = x + width - vw - 5;
        Fonts.list.drawString(val, valueLeft, valueTop(rowY + 3), Theme.TEXT_DIM);
        drawSliderLabel(font, s, rowY, valueLeft);

        sliderTrack(rowY).drawSingle(s.get(), s.getMin(), s.getMax());
    }

    private void renderRange(CustomFont font, RangeSetting s, int rowY) {
        String val = formatValue(s.getLo()) + s.getUnit() + " - " + formatValue(s.getHi()) + s.getUnit();
        int vw = Fonts.list.getStringWidth(val);
        int valueLeft = x + width - vw - 5;
        Fonts.list.drawString(val, valueLeft, valueTop(rowY + 3), Theme.TEXT_DIM);
        drawSliderLabel(font, s, rowY, valueLeft);

        sliderTrack(rowY).drawRange(s.getLo(), s.getHi(), s.getMin(), s.getMax());
    }

    private static double clamp01(double v) {
        return MathHelper.clamp_double(v, 0.0D, 1.0D);
    }

    private void renderColor(CustomFont font, ColorSetting s, int rowY, int mouseX, int mouseY) {
        int bx = x + width - CP_SWATCH - 5;
        drawLabel(font, s, rowY, true, bx);
        int by = rowY + (ROW_H - CP_SWATCH) / 2;
        boolean hover = RenderUtil.hovered(mouseX, mouseY, bx, by, CP_SWATCH, CP_SWATCH);
        RenderUtil.drawBorderedRect(bx, by, bx + CP_SWATCH, by + CP_SWATCH,
                0xFF000000 | (s.get() & 0xFFFFFF), hover ? Theme.CONTOUR : Theme.SEP);
    }

    private void renderHeader(CustomFont font, HeaderSetting s, int rowY) {
        CustomFont hf = Fonts.list;
        float textY = rowY + (HEADER_ROW_H - hf.getHeight()) / 2f;
        hf.drawString(s.getDisplayName().toUpperCase(), x + 5, textY, Theme.TEXT_MUTE);
        int lineY = rowY + HEADER_ROW_H - 2;
        RenderUtil.rectBounds(x + 4, lineY, x + width - 4, lineY + 1, Theme.SEP);
    }

    private void drawCell(int cx, int cy, int size, boolean enabled, boolean hover) {
        int fill = enabled ? Theme.WELL : Theme.BODY;
        int frame = hover ? Theme.CONTOUR : (enabled ? Theme.FROST : Theme.SEP);
        RenderUtil.drawBorderedRect(cx, cy, cx + size, cy + size, fill, frame);
    }

    private void renderItemGrid(CustomFont font, ItemGridSetting grid, int rowY, int mouseX, int mouseY) {
        drawLabel(font, grid, rowY, false, x + width - 5);
        List<ItemGridSetting.Entry> entries = grid.getEntries();
        drawGridCells(rowY, entries.size(), i -> entries.get(i).isEnabled(), mouseX, mouseY);
    }

    private void renderBedGrid(CustomFont font, BedGridSetting grid, int rowY, int mouseX, int mouseY) {
        drawLabel(font, grid, rowY, false, x + width - 5);
        for (int lz = 0; lz <= 1; lz++) { // the bed footprint (bed-local (0,0) and (0,1)) — never clickable
            int bx = bedCellX(0);
            int by = bedCellY(rowY, lz);
            RenderUtil.drawBorderedRect(bx, by, bx + BED_CELL, by + BED_CELL,
                    Theme.FROST, Theme.CONTOUR);
        }
        for (BedGridSetting.Cell cell : grid.getCells()) {
            int bx = bedCellX(cell.lx);
            int by = bedCellY(rowY, cell.lz);
            boolean hover = RenderUtil.hovered(mouseX, mouseY, bx, by, BED_CELL, BED_CELL);
            drawCell(bx, by, BED_CELL, cell.isEnabled(), hover);
        }
    }

    /** The ring cell under the cursor for a bed grid at {@code rowY}, or null (bed cells ignore clicks). */
    private BedGridSetting.Cell bedCellAt(BedGridSetting grid, int rowY, int mouseX, int mouseY) {
        for (BedGridSetting.Cell cell : grid.getCells()) {
            if (RenderUtil.hovered(mouseX, mouseY, bedCellX(cell.lx), bedCellY(rowY, cell.lz), BED_CELL, BED_CELL)) {
                return cell;
            }
        }
        return null;
    }

    private void drawWidgetHeader(CustomFont font, Setting<?> s, int rowY, boolean open) {
        float textY = rowY + (ROW_H - font.getHeight()) / 2f;
        String tag = open ? "[-]" : "[+]";
        int tw = Fonts.list.getStringWidth(tag);
        int lx = labelX(s);
        font.drawString(font.trimToWidth(s.getDisplayName(), (x + width - tw - 5) - 4 - lx, ".."),
                lx, textY, labelColor(s));
        Fonts.list.drawString(tag, x + width - tw - 5, valueTop(textY), Theme.TEXT_DIM);
    }

    private void drawPickerCell(int cx, int cy, int fill, int frame) {
        RenderUtil.drawBorderedRect(cx, cy, cx + CELL, cy + CELL, fill, frame);
    }

    private interface PickerActions {
        void category(int category, int button);
        void entry(HotbarSetting.Entry entry, int button);
    }

    /** Shared picker geometry and input; settings supply frame colors and click actions. */
    private final class ItemPickerWidget {
        private final ItemPicker picker;
        private final int rowY;

        private ItemPickerWidget(ItemPicker picker, int rowY) {
            this.picker = picker;
            this.rowY = rowY;
        }

        void drawFrames(CustomFont font, int mouseX, int mouseY,
                        java.util.function.ToIntBiFunction<String, Boolean> stateFrame) {
            int catY = pickerCatY(rowY);
            List<HotbarSetting.Category> categories = picker.getCategories();
            for (int i = 0; i < categories.size(); i++) {
                int cx = colX(i);
                boolean open = i == picker.getOpenCategory();
                boolean hover = RenderUtil.hovered(mouseX, mouseY, cx, catY, CELL, CELL);
                int frame = open ? Theme.FROST
                        : stateFrame.applyAsInt(HotbarSetting.categoryRef(i), hover);
                drawPickerCell(cx, catY, open ? Theme.WELL : Theme.BODY, frame);
            }
            if (picker.getOpenCategory() < 0) {
                return;
            }
            CustomSearchField.draw(font, colX(0), pickerSearchY(rowY), gridStripWidth(), SEARCH_H,
                    picker.getSearch(), searchingPicker == picker, "Search items...", cursorCounter);
            for (VisibleCell cell : visibleCells()) {
                boolean hover = RenderUtil.hovered(mouseX, mouseY, cell.x, cell.y, CELL, CELL);
                drawPickerCell(cell.x, cell.y, Theme.BODY,
                        stateFrame.applyAsInt(cell.entry.getKey(), hover));
            }
        }

        void drawIcons() {
            int catY = pickerCatY(rowY);
            List<HotbarSetting.Category> categories = picker.getCategories();
            for (int i = 0; i < categories.size(); i++) {
                RenderUtil.drawItem(categories.get(i).getIcon(), colX(i) + 1, catY + 1);
            }
            if (picker.getOpenCategory() < 0) {
                return;
            }
            for (VisibleCell cell : visibleCells()) {
                RenderUtil.drawItem(cell.entry.getIcon(), cell.x + 1, cell.y + 1);
            }
        }

        /** Handles all shared picker clicks; false means a setting-specific trailing area may handle it. */
        boolean click(int button, int mouseX, int mouseY, PickerActions actions) {
            if (RenderUtil.hovered(mouseX, mouseY, x, rowY, width, ROW_H)) {
                picker.setWidgetOpen(!picker.isWidgetOpen());
                return true;
            }
            if (!picker.isWidgetOpen()) {
                return true;
            }
            int category = categoryAt(mouseX, mouseY);
            if (category >= 0) {
                actions.category(category, button);
                return true;
            }
            if (searchContains(mouseX, mouseY)) {
                searchingPicker = picker;
                cursorCounter = 0;
                return true;
            }
            HotbarSetting.Entry entry = gridEntryAt(mouseX, mouseY);
            if (entry != null) {
                actions.entry(entry, button);
                return true;
            }
            return false;
        }

        int categoryAt(int mouseX, int mouseY) {
            int catY = pickerCatY(rowY);
            List<HotbarSetting.Category> categories = picker.getCategories();
            for (int i = 0; i < categories.size(); i++) {
                if (RenderUtil.hovered(mouseX, mouseY, colX(i), catY, CELL, CELL)) {
                    return i;
                }
            }
            return -1;
        }

        boolean searchContains(int mouseX, int mouseY) {
            return picker.getOpenCategory() >= 0
                    && RenderUtil.hovered(mouseX, mouseY, colX(0), pickerSearchY(rowY),
                    gridStripWidth(), SEARCH_H);
        }

        boolean gridContains(int mouseX, int mouseY) {
            return picker.getOpenCategory() >= 0
                    && RenderUtil.hovered(mouseX, mouseY, colX(0), pickerGridY(rowY),
                    gridStripWidth(), gridViewH());
        }

        HotbarSetting.Entry gridEntryAt(int mouseX, int mouseY) {
            for (VisibleCell cell : visibleCells()) {
                if (RenderUtil.hovered(mouseX, mouseY, cell.x, cell.y, CELL, CELL)) {
                    return cell.entry;
                }
            }
            return null;
        }

        private List<VisibleCell> visibleCells() {
            return visibleCellsFor(picker, rowY);
        }
    }

    private List<VisibleCell> visibleCellsFor(ItemPicker picker, int rowY) {
        int category = picker.getOpenCategory();
        if (category < 0) {
            return Collections.emptyList();
        }
        List<HotbarSetting.Entry> filtered = picker.getFilteredEntries();
        int firstIndex = rowOffsetFor(picker) * GRID_COLS;
        int visibleCount = MathHelper.clamp_int(
                filtered.size() - firstIndex, 0, VIEW_ROWS * GRID_COLS);
        List<VisibleCell> cells = new ArrayList<VisibleCell>(visibleCount);
        int gridY = pickerGridY(rowY);
        for (int visibleIndex = 0; visibleIndex < visibleCount; visibleIndex++) {
            int row = visibleIndex / GRID_COLS;
            int column = visibleIndex % GRID_COLS;
            cells.add(new VisibleCell(filtered.get(firstIndex + visibleIndex),
                    colX(column), gridY + row * (CELL + ROW_GAP)));
        }
        return cells;
    }

    private static final class VisibleCell {
        private final HotbarSetting.Entry entry;
        private final int x;
        private final int y;

        private VisibleCell(HotbarSetting.Entry entry, int x, int y) {
            this.entry = entry;
            this.x = x;
            this.y = y;
        }
    }

    private void drawHotbar(CustomFont font, HotbarSetting hb, int rowY, int mouseX, int mouseY) {
        drawWidgetHeader(font, hb, rowY, hb.isWidgetOpen());
        if (!hb.isWidgetOpen()) {
            return;
        }
        new ItemPickerWidget(hb, rowY).drawFrames(font, mouseX, mouseY, (key, hover) ->
                hb.isExcluded(key) ? EXCLUDED_CLR : (hover ? Theme.CONTOUR : Theme.SEP));
        // hotbar strip: each slot is a vertical stack (primary on top, fallbacks below)
        int stripY = hotbarStripY(hb, rowY);
        List<List<String>> slots = hb.getSlots();
        for (int i = 0; i < HotbarSetting.SLOTS; i++) {
            int cx = colX(i);
            List<String> list = slots.get(i);
            if (list.isEmpty()) {
                drawPickerCell(cx, stripY, Theme.BODY, Theme.SEP);
                font.drawCenteredInRect("+", cx, stripY, CELL, CELL, Theme.TEXT_DIM);
            } else {
                for (int k = 0; k < list.size(); k++) {
                    int cy = stripY + k * (CELL + ROW_GAP);
                    boolean isCat = HotbarSetting.isCategoryKey(list.get(k));
                    int fill = k == 0 ? Theme.WELL : Theme.BODY;
                    int frame = (isCat || k == 0) ? Theme.FROST : Theme.SEP;
                    drawPickerCell(cx, cy, fill, frame);
                }
            }
        }
    }

    private void drawCleaner(CustomFont font, CleanerSetting cg, int rowY, int mouseX, int mouseY) {
        drawWidgetHeader(font, cg, rowY, cg.isWidgetOpen());
        if (!cg.isWidgetOpen()) {
            return;
        }
        new ItemPickerWidget(cg, rowY).drawFrames(font, mouseX, mouseY, (key, hover) -> {
            CleanerSetting.CleanerMode m = cg.getMode(key);
            return m != null ? modeColor(m) : (hover ? Theme.CONTOUR : Theme.SEP);
        });
    }

    private int modeColor(CleanerSetting.CleanerMode m) {
        switch (m) {
            case DROP:
                return EXCLUDED_CLR;
            case KEEP_ONE:
                return CLEAN_KEEP_CLR;
            default:
                return Theme.TEXT_DIM; // IGNORE -> grey
        }
    }

    private String modeLetter(CleanerSetting.CleanerMode m) {
        switch (m) {
            case DROP:
                return "D";
            case KEEP_ONE:
                return "K";
            default:
                return "I"; // IGNORE
        }
    }

    /** Shadow keeps mode letters readable over item icons. */
    private void drawModeBadge(CustomFont font, CleanerSetting.CleanerMode m, int cx, int cy) {
        String letter = modeLetter(m);
        int lw = font.getStringWidth(letter);
        float tx = cx + CELL - lw - 1;
        float ty = cy + 1;
        font.drawString(letter, tx + 0.6f, ty + 0.6f, 0xFF000000);
        font.drawString(letter, tx, ty, modeColor(m));
    }

    /** Draw after cell frames so textured icons remain on top, using the same body clip. */
    public void renderItems(int mouseX, int mouseY) {
        if (!beginBodyScissor()) {
            return;
        }
        int rowY = bodyTopY();
        for (Setting<?> s : module.getSettings()) {
            if (!s.isVisible()) {
                continue;
            }
            if (s instanceof ItemGridSetting) {
                List<ItemGridSetting.Entry> entries = ((ItemGridSetting) s).getEntries();
                for (int i = 0; i < entries.size(); i++) {
                    RenderUtil.drawItem(entries.get(i).getIcon(), colX(i) + 1, cellY(rowY, i) + 1);
                }
            } else if (s instanceof HotbarSetting) {
                renderHotbarItems((HotbarSetting) s, rowY);
            } else if (s instanceof CleanerSetting) {
                renderCleanerItems((CleanerSetting) s, rowY);
            }
            rowY += rowHeight(s);
        }
        RenderUtil.endScissor();
    }

    private void renderHotbarItems(HotbarSetting hb, int rowY) {
        if (!hb.isWidgetOpen()) {
            return;
        }
        new ItemPickerWidget(hb, rowY).drawIcons();
        List<HotbarSetting.Entry> filtered = null;
        int rowOffset = 0;
        int gridY = 0;
        if (hb.getOpenCategory() >= 0) {
            filtered = hb.getFilteredEntries();
            rowOffset = rowOffsetFor(hb);
            gridY = pickerGridY(rowY);
        }
        int stripY = hotbarStripY(hb, rowY);
        List<List<String>> slots = hb.getSlots();
        for (int i = 0; i < HotbarSetting.SLOTS; i++) {
            List<String> list = slots.get(i);
            for (int k = 0; k < list.size(); k++) {
                RenderUtil.drawItem(hb.iconForKey(list.get(k)), colX(i) + 1, stripY + k * (CELL + ROW_GAP) + 1);
            }
        }
        // overlays drawn after the icons: dim the excluded grid cells, dot the whole-category strip cells
        if (filtered != null) {
            for (int r = 0; r < VIEW_ROWS; r++) {
                for (int c = 0; c < GRID_COLS; c++) {
                    int idx = (rowOffset + r) * GRID_COLS + c;
                    if (idx >= filtered.size() || !hb.isExcluded(filtered.get(idx).getKey())) {
                        continue;
                    }
                    RenderUtil.rect(colX(c), gridY + r * (CELL + ROW_GAP), CELL, CELL, EXCLUDE_OVERLAY);
                }
            }
        }
        for (int i = 0; i < HotbarSetting.SLOTS; i++) {
            List<String> list = slots.get(i);
            int cx = colX(i);
            for (int k = 0; k < list.size(); k++) {
                if (!HotbarSetting.isCategoryKey(list.get(k))) {
                    continue;
                }
                RenderUtil.rect(cx + CELL - 4, stripY + k * (CELL + ROW_GAP) + 1, 3, 3, CAT_CLR);
            }
        }
    }

    private void renderCleanerItems(CleanerSetting cg, int rowY) {
        if (!cg.isWidgetOpen()) {
            return;
        }
        new ItemPickerWidget(cg, rowY).drawIcons();
        CustomFont font = Fonts.medium;
        int catY = pickerCatY(rowY);
        List<HotbarSetting.Category> cats = cg.getCategories();
        List<HotbarSetting.Entry> filtered = null;
        int rowOffset = 0;
        int gridY = 0;
        if (cg.getOpenCategory() >= 0) {
            filtered = cg.getFilteredEntries();
            rowOffset = rowOffsetFor(cg);
            gridY = pickerGridY(rowY);
        }
        if (font == null) {
            return;
        }
        for (int i = 0; i < cats.size(); i++) {
            CleanerSetting.CleanerMode m = cg.getMode(HotbarSetting.categoryRef(i));
            if (m != null) {
                drawModeBadge(font, m, colX(i), catY);
            }
        }
        if (filtered != null) {
            for (int r = 0; r < VIEW_ROWS; r++) {
                for (int c = 0; c < GRID_COLS; c++) {
                    int idx = (rowOffset + r) * GRID_COLS + c;
                    if (idx >= filtered.size()) {
                        continue;
                    }
                    CleanerSetting.CleanerMode m = cg.getMode(filtered.get(idx).getKey());
                    if (m != null) {
                        drawModeBadge(font, m, colX(c), gridY + r * (CELL + ROW_GAP));
                    }
                }
            }
        }
    }

    public void renderDragGhost() {
        if (draggingHotbar != null && draggingKey != null) {
            RenderUtil.drawItem(draggingHotbar.iconForKey(draggingKey), dragMouseX - 8, dragMouseY - 8);
        }
    }

    /** Returns whether Esc closed an overlay before the screen dismisses the panel. */
    public boolean closeOverlay() {
        if (activeColor != null) {
            activeColor = null;
            return true;
        }
        return false;
    }

    private void openColorPicker(ColorSetting cs, int anchorX, int anchorY) {
        activeColor = cs;
        float[] hsb = Color.RGBtoHSB(cs.red(), cs.green(), cs.blue(), null);
        curH = hsb[0];
        curS = hsb[1];
        curB = hsb[2];
        CustomFont font = Fonts.medium;
        int titleH = (font != null ? font.getHeight() : 8) + 3;
        int readoutH = (Fonts.list != null ? Fonts.list.getHeight() : 8) + 2;
        cpW = CP_PAD * 2 + SB_SIZE + CP_GAP + HUE_W;
        cpH = CP_PAD * 2 + titleH + SB_SIZE + 3 + readoutH;
        cpX = MathHelper.clamp_int(anchorX, 0, Math.max(0, screen.width - cpW));
        cpY = MathHelper.clamp_int(anchorY, 0, Math.max(0, screen.height - cpH));
        cpSbX = cpX + CP_PAD;
        cpSbY = cpY + CP_PAD + titleH;
        cpHueX = cpSbX + SB_SIZE + CP_GAP;
    }

    /**
     * Draws the open colour picker, layered above everything. The SB square and hue bar are tiled from
     * flat cells because no per-vertex 2D gradient primitive exists.
     */
    public void renderColorPicker(int mouseX, int mouseY) {
        if (activeColor == null) {
            return;
        }
        CustomFont font = Fonts.medium;
        RenderUtil.drawBorderedRect(cpX, cpY, cpX + cpW, cpY + cpH, Theme.BODY,
                Theme.CONTOUR, Theme.CONTOUR_PX);
        if (font != null) {
            font.drawString(activeColor.getDisplayName(), cpX + CP_PAD, cpY + CP_PAD, Theme.TEXT_DIM);
        }
        // saturation (x) / brightness (y) square at the current hue
        for (int dx = 0; dx < SB_SIZE; dx += CP_STEP) {
            int cw = Math.min(CP_STEP, SB_SIZE - dx);
            float s = dx / (float) SB_SIZE;
            for (int dy = 0; dy < SB_SIZE; dy += CP_STEP) {
                int ch = Math.min(CP_STEP, SB_SIZE - dy);
                float b = 1f - dy / (float) SB_SIZE;
                RenderUtil.rect(cpSbX + dx, cpSbY + dy, cw, ch, 0xFF000000 | (Color.HSBtoRGB(curH, s, b) & 0xFFFFFF));
            }
        }
        RenderUtil.outline(cpSbX, cpSbY, cpSbX + SB_SIZE, cpSbY + SB_SIZE, 1, Theme.CONTOUR);
        for (int dy = 0; dy < SB_SIZE; dy += HUE_STEP) {
            int ch = Math.min(HUE_STEP, SB_SIZE - dy);
            float h = dy / (float) SB_SIZE;
            RenderUtil.rect(cpHueX, cpSbY + dy, HUE_W, ch, 0xFF000000 | (Color.HSBtoRGB(h, 1f, 1f) & 0xFFFFFF));
        }
        RenderUtil.outline(cpHueX, cpSbY, cpHueX + HUE_W, cpSbY + SB_SIZE, 1, Theme.CONTOUR);
        int selX = cpSbX + Math.round(curS * SB_SIZE);
        int selY = cpSbY + Math.round((1f - curB) * SB_SIZE);
        RenderUtil.outline(selX - 3, selY - 3, selX + 4, selY + 4, 1, 0xFF000000);
        RenderUtil.outline(selX - 2, selY - 2, selX + 3, selY + 3, 1, Theme.TEXT);
        int hueSelY = cpSbY + Math.round(curH * SB_SIZE);
        RenderUtil.rectBounds(cpHueX - 1, hueSelY - 1, cpHueX + HUE_W + 1, hueSelY + 1, Theme.TEXT);
        if (Fonts.list != null) {
            int rgb = activeColor.get();
            String txt = "R" + ((rgb >> 16) & 0xFF) + " G" + ((rgb >> 8) & 0xFF) + " B" + (rgb & 0xFF);
            Fonts.list.drawString(txt, cpSbX, cpSbY + SB_SIZE + 3, Theme.TEXT_DIM);
        }
    }

    private void updateSB(int mouseX, int mouseY) {
        curS = MathHelper.clamp_float((mouseX - cpSbX) / (float) SB_SIZE, 0f, 1f);
        curB = MathHelper.clamp_float(1f - (mouseY - cpSbY) / (float) SB_SIZE, 0f, 1f);
        applyPickerColor();
    }

    private void updateHue(int mouseY) {
        curH = MathHelper.clamp_float((mouseY - cpSbY) / (float) SB_SIZE, 0f, 1f);
        applyPickerColor();
    }

    private void applyPickerColor() {
        if (activeColor != null) {
            activeColor.setRgb(Color.HSBtoRGB(curH, curS, curB));
        }
    }

    public boolean mouseClicked(int mouseX, int mouseY, int button) {
        // The colour picker can extend outside the panel, so it is hit-tested first, above the bounds gate.
        if (activeColor != null) {
            if (RenderUtil.hovered(mouseX, mouseY, cpX, cpY, cpW, cpH)) {
                if (button == 0) {
                    if (RenderUtil.hovered(mouseX, mouseY, cpSbX, cpSbY, SB_SIZE, SB_SIZE)) {
                        draggingSB = true;
                        updateSB(mouseX, mouseY);
                    } else if (RenderUtil.hovered(mouseX, mouseY, cpHueX, cpSbY, HUE_W, SB_SIZE)) {
                        draggingHue = true;
                        updateHue(mouseY);
                    }
                }
                return true;
            }
            activeColor = null;
            return true;
        }

        clampBodyScroll();
        int height = getHeight();
        if (!RenderUtil.hovered(mouseX, mouseY, x, y, width, height)) {
            return false;
        }
        if (button == 0 && ClickGuiScreen.hitsClose(x, y, width, HEADER, mouseX, mouseY)) {
            screen.closeSettings();
            return true;
        }
        if (RenderUtil.hovered(mouseX, mouseY, x, y, width, HEADER)) {
            if (button == 0) {
                if (dockHost != null) {
                    detach();
                }
                windowDrag.begin(mouseX, mouseY, x, y);
            }
            return true;
        }
        if (button == 0 && scrollbarContains(mouseX, mouseY)) {
            draggingBodyScroll = true;
            updateBodyScroll(mouseY);
            return true;
        }
        if (!bodyContains(mouseX, mouseY)) {
            return true;
        }
        // Search-box handlers restore focus only when their field is clicked.
        searchingPicker = null;
        int rowY = bodyTopY();
        for (Setting<?> s : module.getSettings()) {
            if (!s.isVisible()) {
                continue;
            }
            int rh = rowHeight(s);
            if (RenderUtil.hovered(mouseX, mouseY, x, rowY, width, rh)) {
                handleSettingClick(s, button, mouseX, mouseY, rowY);
                return true;
            }
            rowY += rh;
        }
        return true;
    }

    private void handleSettingClick(Setting<?> s, int button, int mouseX, int mouseY, int rowY) {
        if (s instanceof BooleanSetting) {
            if (button == 0) {
                ((BooleanSetting) s).toggle();
                save();
            }
        } else if (s instanceof ModeSetting) {
            if (button == 0) {
                // left-click on the "<" chevron cycles back; anywhere else in the row cycles forward
                int[] g = modeValueGeometry((ModeSetting) s);
                boolean backChev = RenderUtil.hovered(mouseX, mouseY, g[1] - 2, rowY, g[3] + 4, ROW_H);
                ((ModeSetting) s).cycle(backChev ? -1 : 1);
                save();
            } else if (button == 1) {
                ((ModeSetting) s).cycle(-1);
                save();
            }
        } else if (s instanceof NumberSetting) {
            if (button == 0) {
                draggingSlider = (NumberSetting) s;
                updateSlider(mouseX);
            }
        } else if (s instanceof RangeSetting) {
            if (button == 0) {
                draggingRange = (RangeSetting) s;
                draggingRangeHandle = nearestHandle((RangeSetting) s, mouseX);
                updateRangeSlider(mouseX);
            }
        } else if (s instanceof ColorSetting) {
            if (button == 0) {
                openColorPicker((ColorSetting) s, x + width + 2, rowY);
            }
        } else if (s instanceof ItemGridSetting) {
            if (button == 0) {
                List<ItemGridSetting.Entry> entries = ((ItemGridSetting) s).getEntries();
                int i = gridCellAt(rowY, entries.size(), mouseX, mouseY);
                if (i >= 0) {
                    entries.get(i).toggle();
                    save();
                }
            }
        } else if (s instanceof BedGridSetting) {
            if (button == 0) {
                BedGridSetting.Cell cell = bedCellAt((BedGridSetting) s, rowY, mouseX, mouseY);
                if (cell != null) {
                    cell.toggle();
                    save();
                }
            }
        } else if (s instanceof HotbarSetting) {
            handleHotbarPickerClick((HotbarSetting) s, button, mouseX, mouseY, rowY);
        } else if (s instanceof CleanerSetting) {
            handleCleanerClick((CleanerSetting) s, button, mouseX, mouseY, rowY);
        } else if (s instanceof ButtonSetting) {
            if (button == 0) {
                ((ButtonSetting) s).run();
            }
        }
    }

    private void handleHotbarPickerClick(final HotbarSetting hotbar, int button,
                                         final int mouseX, final int mouseY, int rowY) {
        boolean handled = new ItemPickerWidget(hotbar, rowY)
                .click(button, mouseX, mouseY, new PickerActions() {
            @Override
            public void category(int category, int clickedButton) {
                if (clickedButton == 0) {
                    beginHotbarDrag(hotbar, HotbarSetting.categoryRef(category), mouseX, mouseY);
                } else {
                    hotbar.setOpenCategory(hotbar.getOpenCategory() == category ? -1 : category);
                    save();
                }
            }

            @Override
            public void entry(HotbarSetting.Entry entry, int clickedButton) {
                if (clickedButton == 0) {
                    beginHotbarDrag(hotbar, entry.getKey(), mouseX, mouseY);
                } else {
                    hotbar.toggleItem(entry.getKey());
                    save();
                }
            }
        });
        if (handled) {
            return;
        }
        int[] slotItem = hbSlotItemAt(hotbar, rowY, mouseX, mouseY);
        if (slotItem != null && button == 1) {
            hotbar.removeFromSlot(slotItem[0], hotbar.getSlots().get(slotItem[0]).get(slotItem[1]));
            save();
        }
    }

    private void handleCleanerClick(final CleanerSetting cleaner, int button,
                                    int mouseX, int mouseY, int rowY) {
        new ItemPickerWidget(cleaner, rowY).click(button, mouseX, mouseY, new PickerActions() {
            @Override
            public void category(int category, int clickedButton) {
                if (clickedButton == 1) {
                    cleaner.setOpenCategory(cleaner.getOpenCategory() == category ? -1 : category);
                } else {
                    cleaner.cycle(HotbarSetting.categoryRef(category));
                }
                save();
            }

            @Override
            public void entry(HotbarSetting.Entry entry, int clickedButton) {
                if (clickedButton == 0) {
                    cleaner.cycle(entry.getKey());
                } else {
                    cleaner.cycleBack(entry.getKey());
                }
                save();
            }
        });
    }

    private void beginHotbarDrag(HotbarSetting hb, String key, int mouseX, int mouseY) {
        draggingHotbar = hb;
        draggingKey = key;
        dragMouseX = mouseX;
        dragMouseY = mouseY;
    }

    public void drag(int mouseX, int mouseY, int screenWidth, int screenHeight) {
        if (draggingSB) {
            updateSB(mouseX, mouseY);
            return;
        }
        if (draggingHue) {
            updateHue(mouseY);
            return;
        }
        if (draggingHotbar != null) {
            dragMouseX = mouseX;
            dragMouseY = mouseY;
            return;
        }
        if (draggingBodyScroll) {
            updateBodyScroll(mouseY);
            return;
        }
        int[] pos = windowDrag.update(mouseX, mouseY, width, getHeight(), screenWidth, screenHeight);
        if (pos != null) {
            x = pos[0];
            y = pos[1];
            clampBodyScroll();
        }
        if (draggingSlider != null) {
            updateSlider(mouseX);
        }
        if (draggingRange != null) {
            updateRangeSlider(mouseX);
        }
    }

    private void updateSlider(int mouseX) {
        draggingSlider.set(sliderTrack(0).valueAt(mouseX, draggingSlider.getMin(), draggingSlider.getMax()));
    }

    private int nearestHandle(RangeSetting range, int mouseX) {
        return sliderTrack(0).nearestHandle(range.getLo(), range.getHi(),
                range.getMin(), range.getMax(), mouseX);
    }

    private void updateRangeSlider(int mouseX) {
        double value = sliderTrack(0).valueAt(mouseX, draggingRange.getMin(), draggingRange.getMax());
        // Clamp against the other end so the handle identity cannot swap mid-drag.
        if (draggingRangeHandle == 0) {
            draggingRange.setLo(Math.min(value, draggingRange.getHi()));
        } else {
            draggingRange.setHi(Math.max(value, draggingRange.getLo()));
        }
    }

    public void mouseReleased() {
        windowDrag.end();
        draggingBodyScroll = false;
        if (draggingSB || draggingHue) {
            draggingSB = false;
            draggingHue = false;
            save();
        }
        if (draggingSlider != null) {
            draggingSlider = null;
            save();
        }
        if (draggingRange != null) {
            draggingRange = null;
            save();
        }
        if (draggingHotbar != null && draggingKey != null) {
            finishHotbarDrag();
            draggingHotbar = null;
            draggingKey = null;
        }
    }

    /**
     * Releasing on the source cell acts as a click: toggle the item or open its category.
     * Releasing on a hotbar column binds the dragged key.
     */
    private void finishHotbarDrag() {
        int rowY = rowYOf(draggingHotbar);
        if (rowY < 0) {
            return;
        }
        int col = hbSlotColAt(draggingHotbar, rowY, dragMouseX, dragMouseY);
        if (col != -1) {
            draggingHotbar.dropOnSlot(col, draggingKey);
            save();
        } else if (HotbarSetting.isCategoryKey(draggingKey)) {
            int cat = new ItemPickerWidget(draggingHotbar, rowY).categoryAt(dragMouseX, dragMouseY);
            if (cat != -1 && HotbarSetting.categoryRef(cat).equals(draggingKey)) {
                draggingHotbar.setOpenCategory(draggingHotbar.getOpenCategory() == cat ? -1 : cat);
                save();
            }
        } else {
            HotbarSetting.Entry ge = new ItemPickerWidget(draggingHotbar, rowY).gridEntryAt(dragMouseX, dragMouseY);
            if (ge != null && ge.getKey().equals(draggingKey)) {
                draggingHotbar.toggleItem(draggingKey);
                save();
            }
        }
    }

    private int rowYOf(Setting<?> target) {
        int rowY = bodyTopY();
        for (Setting<?> s : module.getSettings()) {
            if (!s.isVisible()) {
                continue;
            }
            if (s == target) {
                return rowY;
            }
            rowY += rowHeight(s);
        }
        return -1;
    }

    private int gridCellAt(int rowY, int count, int mouseX, int mouseY) {
        for (int i = 0; i < count; i++) {
            if (RenderUtil.hovered(mouseX, mouseY, colX(i), cellY(rowY, i), CELL, CELL)) {
                return i;
            }
        }
        return -1;
    }

    /** Hotbar strip column under the cursor (anywhere in its vertical stack), for drops, or -1. */
    private int hbSlotColAt(HotbarSetting hb, int rowY, int mouseX, int mouseY) {
        int stripY = hotbarStripY(hb, rowY);
        int stripH = CELL + maxFallbacks(hb) * (CELL + ROW_GAP);
        for (int i = 0; i < HotbarSetting.SLOTS; i++) {
            if (RenderUtil.hoveredExclusive(mouseX, mouseY, colX(i), stripY, CELL, stripH)) {
                return i;
            }
        }
        return -1;
    }

    /** {slot, itemIndex} of the specific strip cell under the cursor (for removal), or null. */
    private int[] hbSlotItemAt(HotbarSetting hb, int rowY, int mouseX, int mouseY) {
        int col = hbSlotColAt(hb, rowY, mouseX, mouseY);
        if (col < 0) {
            return null;
        }
        int stripY = hotbarStripY(hb, rowY);
        int k = (mouseY - stripY) / (CELL + ROW_GAP);
        if (k < 0 || k >= hb.getSlots().get(col).size()) {
            return null;
        }
        return new int[]{col, k};
    }

    public boolean isSearching() {
        return searchingPicker != null;
    }

    public void charTyped(char typedChar, int keyCode) {
        if (searchingPicker == null) {
            return;
        }
        String cur = searchingPicker.getSearch();
        CustomTextInput.EditResult edit = CustomTextInput.edit(cur, typedChar, keyCode, -1);
        if (!edit.isFocused()) {
            searchingPicker = null;
        } else if (edit.isChanged()) {
            searchingPicker.setSearch(edit.getValue());
            save();
        }
    }

    /** Sends wheel input to an open picker grid first, then to the bounded settings body itself. */
    public void scroll(int dWheel, int mouseX, int mouseY) {
        clampBodyScroll();
        if (!bodyContains(mouseX, mouseY)) {
            return;
        }
        int rowY = bodyTopY();
        for (Setting<?> s : module.getSettings()) {
            if (!s.isVisible()) {
                continue;
            }
            int rh = rowHeight(s);
            if (s instanceof ItemPicker) {
                ItemPicker picker = (ItemPicker) s;
                if (picker.isWidgetOpen() && new ItemPickerWidget(picker, rowY).gridContains(mouseX, mouseY)) {
                    int maxRow = maxRowFor(picker);
                    double next = picker.getScroll() + (dWheel > 0 ? -1 : 1);
                    next = MathHelper.clamp_double(next, 0.0D, (double)maxRow);
                    picker.setScroll(next);
                    save();
                    return;
                }
            }
            rowY += rh;
        }
        if (maxBodyScroll() > 0) {
            int delta = dWheel > 0 ? -BODY_SCROLL_STEP : BODY_SCROLL_STEP;
            bodyScroll = MathHelper.clamp_int(bodyScroll + delta, 0, maxBodyScroll());
        }
    }

    private void save() {
        ColdPlay.getInstance().saveConfig();
    }

    private static String formatValue(double v) {
        if (v == Math.floor(v)) {
            return String.valueOf((int) v);
        }
        return String.valueOf(Math.round(v * 100.0) / 100.0);
    }

    /**
     * Fractional values one increment inside the bounds can be wider than either endpoint.
     * character counts assume the bundled monospace font; render-time trimming is the fallback.
     */
    static String worstValueText(double min, double max, double increment) {
        String worst = "";
        for (double v : new double[]{min, max, min + increment, max - increment}) {
            String text = formatValue(v);
            if (text.length() > worst.length()) {
                worst = text;
            }
        }
        return worst;
    }
}
