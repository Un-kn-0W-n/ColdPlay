package coldplay.gui.click;

import coldplay.ColdPlay;
import coldplay.gui.GlassShader;
import coldplay.gui.Icons;
import coldplay.gui.Theme;
import coldplay.module.Module;
import coldplay.setting.BedGridSetting;
import coldplay.setting.BooleanSetting;
import coldplay.setting.ButtonSetting;
import coldplay.setting.CleanerSetting;
import coldplay.setting.ColorSetting;
import coldplay.setting.HeaderSetting;
import coldplay.setting.HotbarSetting;
import coldplay.setting.ItemGridSetting;
import coldplay.setting.ModeSetting;
import coldplay.setting.NumberSetting;
import coldplay.setting.RangeSetting;
import coldplay.setting.Setting;
import coldplay.util.Animation;
import coldplay.util.RenderUtil;
import coldplay.util.font.CustomFont;
import coldplay.util.font.FontRef;
import coldplay.util.font.Fonts;

import net.minecraft.client.gui.GuiScreen;
import net.minecraft.util.MathHelper;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

/**
 * Settings for one module. Smoke: a glass window docked beside its category panel; dragging the header
 * detaches it. Milk: pinned inside the Milk window and grouped into cards, one per header.
 */
public class SettingsPanel {
    private static final int MIN_WIDTH = 186; // fits a nine-cell grid
    private static final int DOCK_GAP = 4;
    private static final int MAX_BODY_H = 420;
    private static final int VIEWPORT_BOTTOM_PAD = 6;
    private static final int BODY_SCROLL_STEP = 18;
    private static final int SMOKE_BOTTOM_PAD = 6;
    private static final int CLOSE = 18;
    private static final float CHEVRON = 6.0F;

    private static final int CARD_PAD_X = 11;
    private static final int CARD_PAD_Y = 8;
    private static final int CARD_GAP = 8;
    private static final float CARD_RADIUS = 10.5F;
    private static final int CARD_FILL = 0x8CFFFFFF;
    private static final int CARD_LINE = 0xCCFFFFFF;
    private static final int CHIP_H = 21;
    private static final int CHIP_PAD = 9;
    private static final int CHIP_GAP = 5;
    private static final int CHIP_OFF_FILL = 0xB3FFFFFF;
    private static final int SEGMENT_H = 20;
    private static final int SEGMENT_PAD = 9;
    private static final int SEGMENT_FILL = 0x12101520;
    private static final FontRef CHIP = new FontRef(Fonts.JAKARTA_MEDIUM, 9.375F);
    private static final FontRef CHIP_ON = new FontRef(Fonts.JAKARTA_SEMIBOLD, 9.375F);
    private static final FontRef SEGMENT = new FontRef(Fonts.JAKARTA, 9.0F);
    private static final FontRef SEGMENT_ON = new FontRef(Fonts.JAKARTA_SEMIBOLD, 9.0F);

    private static final int GRID_COLS = 9;
    private static final int CELL = 18; // 16px icon plus 1px frame
    private static final int COL_GAP = 1;
    private static final int ROW_GAP = 2;
    private static final int GRID_BOT_PAD = 4;
    private static final float CELL_RADIUS = 3.0F;

    private static final int BED_COLS = 2 * BedGridSetting.MAX_RING + 1; // lx in [-MAX_RING, MAX_RING]
    private static final int BED_ROWS = 2 * BedGridSetting.MAX_RING + 2; // lz in [-MAX_RING, 1 + MAX_RING]
    private static final int BED_CELL = 14;
    private static final int BED_GAP = 1;

    private static final int PREVIEW_ROW = 18; // hotbar preview strip under the Layout label
    private static final float PREVIEW_H = 13.5F;
    private static final float PREVIEW_GAP = 1.5F;
    private static final float PILL_H = 13.5F;
    private static final FontRef PILL = new FontRef(Fonts.GEIST, 7.875F);
    private static final FontRef PILL_MILK = new FontRef(Fonts.JAKARTA, 7.875F);

    private static final int CP_PAD = 7;
    private static final int SB_SIZE = 72; // saturation/brightness square, px
    private static final int HUE_W = 10;
    private static final int CP_GAP = 4;
    private static final int CP_STEP = 4; // gradient tile size, px
    private static final int HUE_STEP = 3;

    private final GuiScreen screen;
    private final Runnable onClose;
    private final Module module;
    private final Skin skin;
    private final boolean milk;
    private final int width;
    private final int maxBodyH;
    private int x;
    private int y;

    private CategoryPanel dockHost; // null once detached
    private boolean dockRight = true;
    private final Animation dockAnim = new Animation(0.0, Theme.WIPE_SPEED); // 0 closed, 1 open
    private int effX; // wipe-clipped rect drawn this frame
    private int effW;
    private int lastScaleFactor = 1;

    private final List<Row> rows = new ArrayList<Row>();
    private final List<int[]> cards = new ArrayList<int[]>(); // {top, bottom} in content px
    private int contentH;

    private int bodyScroll;
    private boolean draggingBodyScroll;

    private final WindowDrag windowDrag = new WindowDrag();
    private NumberSetting draggingSlider;
    private RangeSetting draggingRange;
    private int draggingRangeHandle; // 0 = lo thumb, 1 = hi thumb

    private ColorSetting activeColor; // null unless the picker is open
    private int cpX;
    private int cpY;
    private int cpW;
    private int cpH;
    private int cpSbX;
    private int cpSbY;
    private int cpHueX;
    private float curH;
    private float curS;
    private float curB;
    private boolean draggingSB;
    private boolean draggingHue;

    private LayoutWindow layoutWindow; // null unless a Layout or Items row opened it

    /** One laid-out setting; a Milk chip run is a single row holding every boolean in it. */
    private static final class Row {
        final Setting<?> setting;
        final int top; // content px, before scrolling
        final int height;
        final List<BooleanSetting> chips;

        Row(Setting<?> setting, int top, int height, List<BooleanSetting> chips) {
            this.setting = setting;
            this.top = top;
            this.height = height;
            this.chips = chips;
        }
    }

    /** Smoke: docked beside {@code host}. */
    public SettingsPanel(GuiScreen screen, Runnable onClose, Module module, CategoryPanel host,
                         int screenWidth, int screenHeight) {
        this.screen = screen;
        this.onClose = onClose;
        this.module = module;
        this.skin = Skin.SMOKE;
        this.milk = false;
        this.maxBodyH = MAX_BODY_H;
        this.width = computeWidth(); // layoutDocked needs it
        this.dockHost = host;
        layoutDocked(screenWidth, screenHeight);
        layout();
    }

    /** Milk: pinned in the Milk window, which draws the module title itself. */
    public SettingsPanel(GuiScreen screen, Module module, int x, int y, int width, int bodyHeight) {
        this.screen = screen;
        this.onClose = null;
        this.module = module;
        this.skin = Skin.MILK;
        this.milk = true;
        this.maxBodyH = bodyHeight;
        this.width = width;
        this.x = x;
        this.y = y;
        dockAnim.set(1.0);
        layout();
    }

    public Module getModule() {
        return module;
    }

    /** Follows the Milk window while it is dragged. */
    void moveTo(int x, int y) {
        this.x = x;
        this.y = y;
    }

    /** Hidden rows are measured too, so revealing a setting does not resize the panel. */
    private int computeWidth() {
        CustomFont font = skin.label.get();
        int w = MIN_WIDTH;
        for (Setting<?> s : module.getSettings()) {
            int vw = valueWidth(s);
            w = Math.max(w, skin.pad * 2 + s.getIndent() * skin.indent + font.getStringWidth(s.getDisplayName())
                    + (vw > 0 ? 8 + vw : 0));
        }
        return w;
    }

    /** Width of the widest value the setting can show. */
    private int valueWidth(Setting<?> s) {
        CustomFont vf = skin.value.get();
        if (s instanceof ModeSetting) {
            int widest = 0;
            for (String mode : ((ModeSetting) s).getModes()) {
                widest = Math.max(widest, vf.getStringWidth(mode));
            }
            return widest + Math.round(2 * (CHEVRON + 4.5F));
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

    /** Re-docks beside the host panel, preferring the right side. Returns false when the module's row is gone. */
    public boolean layoutDocked(int screenWidth, int screenHeight) {
        if (dockHost == null) {
            return true;
        }
        if (dockHost.rowTop(module) < 0) {
            return false;
        }
        int right = dockHost.getX() + dockHost.getWidth() + DOCK_GAP;
        dockRight = right + width <= screenWidth || dockHost.getX() - DOCK_GAP - width < 0;
        x = dockRight ? right : dockHost.getX() - DOCK_GAP - width;
        x = MathHelper.clamp_int(x, 0, Math.max(0, screenWidth - width));
        y = dockHost.getY();
        return true;
    }

    private void detach() {
        dockHost = null;
        dockAnim.set(1.0);
    }

    public boolean isDockedTo(CategoryPanel panel) {
        return dockHost == panel;
    }

    public CategoryPanel getDockHost() {
        return dockHost;
    }

    public int getX() {
        return x;
    }

    public int getY() {
        return y;
    }

    public int getHeight() {
        return skin.header + bodyHeight();
    }

    // ---- Layout ----

    private void layout() {
        rows.clear();
        cards.clear();
        List<Setting<?>> settings = module.getSettings();
        int top = 0;
        int cardTop = -1;
        for (int i = 0; i < settings.size(); i++) {
            Setting<?> s = settings.get(i);
            if (!s.isVisible()) {
                continue;
            }
            if (milk && (s instanceof HeaderSetting || cardTop < 0)) {
                if (cardTop >= 0) {
                    top += CARD_PAD_Y;
                    cards.add(new int[]{cardTop, top});
                    top += CARD_GAP;
                }
                cardTop = top;
                top += CARD_PAD_Y;
            }
            List<BooleanSetting> run = milk ? chipRun(settings, i) : null;
            if (run != null) {
                int h = chipRunHeight(run);
                rows.add(new Row(s, top, h, run));
                top += h;
                i = settings.indexOf(run.get(run.size() - 1));
                continue;
            }
            int h = rowHeight(s);
            rows.add(new Row(s, top, h, null));
            top += h;
        }
        if (cardTop >= 0) {
            top += CARD_PAD_Y;
            cards.add(new int[]{cardTop, top});
        }
        contentH = milk ? top : top + SMOKE_BOTTOM_PAD;
    }

    /** Two or more top-level booleans in a row, none with children, show as chips; null otherwise. */
    private static List<BooleanSetting> chipRun(List<Setting<?>> settings, int start) {
        List<BooleanSetting> run = new ArrayList<BooleanSetting>();
        for (int i = start; i < settings.size(); i++) {
            Setting<?> s = settings.get(i);
            if (!s.isVisible()) {
                continue;
            }
            if (!(s instanceof BooleanSetting) || s.getIndent() > 0) {
                if (s.getIndent() > 0 && !run.isEmpty()) {
                    run.remove(run.size() - 1); // that one owns children
                }
                break;
            }
            run.add((BooleanSetting) s);
        }
        return run.size() >= 2 ? run : null;
    }

    private int chipRunHeight(List<BooleanSetting> run) {
        List<float[]> chips = chipRects(run, 0);
        float bottom = chips.get(chips.size() - 1)[1] + CHIP_H;
        return Math.round(bottom) + CHIP_GAP;
    }

    /** {x, y, w} per chip, wrapping across the card. */
    private List<float[]> chipRects(List<BooleanSetting> run, int rowY) {
        List<float[]> rects = new ArrayList<float[]>();
        CustomFont font = CHIP_ON.get();
        float cx = left();
        float cy = rowY;
        for (BooleanSetting b : run) {
            float w = font.getStringWidth(b.getDisplayName()) + CHIP_PAD * 2;
            if (cx + w > right() && cx > left()) {
                cx = left();
                cy += CHIP_H + CHIP_GAP;
            }
            rects.add(new float[]{cx, cy, w});
            cx += w + CHIP_GAP;
        }
        return rects;
    }

    private int rowHeight(Setting<?> s) {
        if (s instanceof NumberSetting || s instanceof RangeSetting) {
            return skin.sliderH;
        }
        if (s instanceof ItemGridSetting) {
            return gridHeight(((ItemGridSetting) s).getEntries().size());
        }
        if (s instanceof BedGridSetting) {
            return bedGridHeight();
        }
        if (s instanceof HotbarSetting) {
            return skin.rowH + PREVIEW_ROW;
        }
        if (s instanceof HeaderSetting) {
            return skin.sectionH;
        }
        return skin.rowH;
    }

    private int left() {
        return x + (milk ? CARD_PAD_X : skin.pad);
    }

    private int right() {
        return x + width - (milk ? CARD_PAD_X : skin.pad);
    }

    private int labelX(Setting<?> s) {
        return left() + s.getIndent() * skin.indent;
    }

    // ---- Scrolling ----

    private int bodyHeight() {
        int available = milk ? maxBodyH : Math.max(96, screen.height - y - skin.header - VIEWPORT_BOTTOM_PAD);
        return Math.min(contentH, Math.min(maxBodyH, available));
    }

    private int maxBodyScroll() {
        return Math.max(0, contentH - bodyHeight());
    }

    private void clampBodyScroll() {
        bodyScroll = MathHelper.clamp_int(bodyScroll, 0, maxBodyScroll());
    }

    private int bodyTopY() {
        return y + skin.header - bodyScroll;
    }

    private boolean bodyContains(int mouseX, int mouseY) {
        return bodyHeight() > 0 && RenderUtil.hovered(mouseX, mouseY, x, y + skin.header, width, bodyHeight());
    }

    private boolean hasBodyScroll() {
        return maxBodyScroll() > 0 && scrollbarTrackHeight() > 0;
    }

    /** Milk keeps the thumb in the gutter beside the cards. */
    private int scrollbarTrackX() {
        return milk ? x + width + 6 : x + width - 3;
    }

    private int scrollbarTrackY() {
        return y + skin.header + 3;
    }

    private int scrollbarTrackHeight() {
        return Math.max(0, bodyHeight() - 6);
    }

    private int scrollbarThumbHeight() {
        return RenderUtil.scrollThumbHeight(scrollbarTrackHeight(), bodyHeight(), contentH);
    }

    private int scrollbarThumbY() {
        return scrollbarTrackY() + RenderUtil.scrollThumbOffset(scrollbarTrackHeight(),
                scrollbarThumbHeight(), bodyScroll, maxBodyScroll());
    }

    private boolean scrollbarContains(int mouseX, int mouseY) {
        return hasBodyScroll() && RenderUtil.hovered(mouseX, mouseY, scrollbarTrackX() - 2,
                y + skin.header, 6, bodyHeight());
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
        int thumb = draggingBodyScroll ? skin.dim : skin.mute;
        GlassShader.rect(scrollbarTrackX() - 0.5F, scrollbarThumbY(), 2.0F, scrollbarThumbHeight(), 1.0F,
                thumb, thumb);
    }

    /** Scissors to the body viewport, clipped by the dock wipe. */
    private boolean beginBodyScissor() {
        int h = bodyHeight();
        int left = Math.max(x, effX);
        int right = Math.min(x + width, effX + effW);
        if (h <= 0 || right <= left) {
            return false;
        }
        RenderUtil.beginScissor(left, y + skin.header, right - left, h, lastScaleFactor);
        return true;
    }

    /** Includes the color picker and the layout window, which sit outside the panel. */
    public boolean contains(int mouseX, int mouseY) {
        if (RenderUtil.hovered(mouseX, mouseY, x, y, width, getHeight())) {
            return true;
        }
        if (layoutWindow != null && layoutWindow.contains(mouseX, mouseY)) {
            return true;
        }
        return activeColor != null && RenderUtil.hovered(mouseX, mouseY, cpX, cpY, cpW, cpH);
    }

    public String getTooltipAt(int mouseX, int mouseY) {
        if (activeColor != null && RenderUtil.hovered(mouseX, mouseY, cpX, cpY, cpW, cpH)) {
            return null;
        }
        if (layoutWindow != null && layoutWindow.contains(mouseX, mouseY)) {
            return layoutWindow.getTooltipAt(mouseX, mouseY);
        }
        if (!bodyContains(mouseX, mouseY)) {
            return null;
        }
        layout();
        Row row = rowAt(mouseY);
        if (row == null) {
            return null;
        }
        if (row.chips != null) {
            int i = chipAt(row, bodyTopY() + row.top, mouseX, mouseY);
            return i >= 0 ? row.chips.get(i).getDescription() : null;
        }
        return row.setting.getDescription();
    }

    private Row rowAt(int mouseY) {
        int top = bodyTopY();
        for (Row row : rows) {
            if (mouseY >= top + row.top && mouseY < top + row.top + row.height) {
                return row;
            }
        }
        return null;
    }

    private int chipAt(Row row, int rowY, int mouseX, int mouseY) {
        List<float[]> rects = chipRects(row.chips, rowY);
        for (int i = 0; i < rects.size(); i++) {
            float[] r = rects.get(i);
            if (RenderUtil.hovered(mouseX, mouseY, r[0], r[1], r[2], CHIP_H)) {
                return i;
            }
        }
        return -1;
    }

    // ---- Grid geometry ----

    private int gridInset() {
        return (width - (GRID_COLS * CELL + (GRID_COLS - 1) * COL_GAP)) / 2;
    }

    private int rowsFor(int count) {
        return Math.max(1, (count + GRID_COLS - 1) / GRID_COLS);
    }

    private int gridHeight(int count) {
        int rowsN = rowsFor(count);
        return skin.rowH + rowsN * CELL + (rowsN - 1) * ROW_GAP + GRID_BOT_PAD;
    }

    /** Smoke centres the nine-cell strip; Milk lines it up with the card's text. */
    private int colX(int index) {
        return (milk ? left() : x + gridInset()) + (index % GRID_COLS) * (CELL + COL_GAP);
    }

    private int cellY(int rowY, int index) {
        return rowY + skin.rowH + (index / GRID_COLS) * (CELL + ROW_GAP);
    }

    private int bedGridHeight() {
        return skin.rowH + BED_ROWS * BED_CELL + (BED_ROWS - 1) * BED_GAP + GRID_BOT_PAD;
    }

    private int bedInset() {
        return (width - (BED_COLS * BED_CELL + (BED_COLS - 1) * BED_GAP)) / 2;
    }

    private int bedCellX(int lx) {
        return x + bedInset() + (lx + BedGridSetting.MAX_RING) * (BED_CELL + BED_GAP);
    }

    private int bedCellY(int rowY, int lz) {
        return rowY + skin.rowH + (lz + BedGridSetting.MAX_RING) * (BED_CELL + BED_GAP);
    }

    /** Nine preview cells span the Layout row under its label. */
    private float previewW(Setting<?> s) {
        return (right() - labelX(s) - (HotbarSetting.SLOTS - 1) * PREVIEW_GAP) / HotbarSetting.SLOTS;
    }

    private float previewX(Setting<?> s, int slot) {
        return labelX(s) + slot * (previewW(s) + PREVIEW_GAP);
    }

    // ---- Rendering ----

    /** Content stays at its final position; the dock animation only clips it. */
    public void render(int mouseX, int mouseY, int scaleFactor) {
        lastScaleFactor = scaleFactor;
        layout();
        clampBodyScroll();
        int height = getHeight();

        double p = Theme.step(dockAnim, 1.0);
        effW = (int) Math.round(width * p);
        effX = dockRight ? x : x + width - effW;
        if (effW <= 0) {
            return;
        }
        boolean clipped = effW < width;
        if (!milk) {
            if (clipped) {
                RenderUtil.beginScissor(effX, y, effW, height, scaleFactor);
            }
            GlassShader.panel(x, y, width, height, skin.radius, skin.glass);
            drawHeader(mouseX, mouseY);
            if (clipped) {
                RenderUtil.endScissor();
            }
        }
        if (!beginBodyScissor()) {
            return;
        }
        int top = bodyTopY();
        for (int[] card : cards) {
            GlassShader.rect(x, top + card[0], width, card[1] - card[0], CARD_RADIUS, CARD_FILL, CARD_FILL);
            GlassShader.stroke(x, top + card[0], width, card[1] - card[0], CARD_RADIUS, CARD_LINE);
        }
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            int rowY = top + row.top;
            int groupH = ownerGroupHeight(i);
            if (groupH > 0) {
                GlassShader.rect(labelX(row.setting) - 4.5F, rowY + 3, 1.5F, groupH - 6, 0.75F, skin.accent, skin.accent);
            }
            drawRow(row, rowY, mouseX, mouseY);
        }
        RenderUtil.endScissor();
        drawBodyScrollbar();
    }

    private void drawHeader(int mouseX, int mouseY) {
        CustomFont title = skin.title.get();
        title.drawString(module.getName(), x + skin.pad, y + (skin.header - title.getHeight()) / 2.0F, skin.strong);
        float cx = x + width - 4.5F - CLOSE;
        float cy = y + (skin.header - CLOSE) / 2.0F;
        boolean hover = RenderUtil.hovered(mouseX, mouseY, cx, cy, CLOSE, CLOSE);
        if (hover) {
            GlassShader.rect(cx, cy, CLOSE, CLOSE, 3.75F, 0x14FFFFFF, 0x14FFFFFF);
        }
        Icons.draw(Icons.Icon.CLOSE, cx + 4.5F, cy + 4.5F, 9.0F, 2.8F, hover ? skin.strong : 0x99FFFFFF);
        GlassShader.rect(x, y + skin.header - 0.75F, width, 0.75F, 0.0F, skin.line, skin.line);
    }

    private boolean hitsClose(int mouseX, int mouseY) {
        return RenderUtil.hovered(mouseX, mouseY, x + width - 4.5F - CLOSE, y + (skin.header - CLOSE) / 2.0F,
                CLOSE, CLOSE);
    }

    private void drawRow(Row row, int rowY, int mouseX, int mouseY) {
        Setting<?> s = row.setting;
        boolean hover = RenderUtil.hovered(mouseX, mouseY, x, rowY, width, row.height);
        if (row.chips != null) {
            drawChips(row, rowY, mouseX, mouseY);
        } else if (s instanceof BooleanSetting) {
            drawBoolean((BooleanSetting) s, rowY, row.height, hover);
        } else if (s instanceof ModeSetting) {
            drawMode((ModeSetting) s, rowY, row.height, hover);
        } else if (s instanceof NumberSetting) {
            NumberSetting n = (NumberSetting) s;
            drawSliderText(s, rowY, formatValue(n.get()));
            sliderTrack(rowY).drawSingle(n.get(), n.getMin(), n.getMax());
        } else if (s instanceof RangeSetting) {
            RangeSetting r = (RangeSetting) s;
            drawSliderText(s, rowY, formatValue(r.getLo()) + r.getUnit() + " - " + formatValue(r.getHi()) + r.getUnit());
            sliderTrack(rowY).drawRange(r.getLo(), r.getHi(), r.getMin(), r.getMax());
        } else if (s instanceof ColorSetting) {
            drawColor((ColorSetting) s, rowY, row.height, hover);
        } else if (s instanceof ItemGridSetting) {
            drawLabel(s, rowY, skin.rowH, right());
            List<ItemGridSetting.Entry> entries = ((ItemGridSetting) s).getEntries();
            for (int i = 0; i < entries.size(); i++) {
                int cx = colX(i);
                int cy = cellY(rowY, i);
                drawCell(cx, cy, CELL, entries.get(i).isEnabled(), RenderUtil.hovered(mouseX, mouseY, cx, cy, CELL, CELL));
            }
        } else if (s instanceof BedGridSetting) {
            drawBedGrid((BedGridSetting) s, rowY, mouseX, mouseY);
        } else if (s instanceof HotbarSetting) {
            drawEditRow(s, rowY, isEditing(false));
            drawPreview(s, rowY);
        } else if (s instanceof CleanerSetting) {
            drawEditRow(s, rowY, isEditing(true));
        } else if (s instanceof HeaderSetting) {
            drawSection(s, rowY, row.height);
        } else if (s instanceof ButtonSetting) {
            drawButton(s, rowY, row.height, hover);
        }
    }

    /** Height of an enabled boolean's row plus its indented children, or 0 when it has none. */
    private int ownerGroupHeight(int i) {
        Row owner = rows.get(i);
        Setting<?> s = owner.setting;
        if (owner.chips != null || !(s instanceof BooleanSetting) || !((BooleanSetting) s).get()) {
            return 0;
        }
        int childH = 0;
        for (int j = i + 1; j < rows.size(); j++) {
            if (rows.get(j).setting.getIndent() <= s.getIndent()) {
                break;
            }
            childH += rows.get(j).height;
        }
        return childH > 0 ? owner.height + childH : 0;
    }

    private void drawLabel(Setting<?> s, int rowY, int h, float valueLeft) {
        CustomFont font = skin.label.get();
        int lx = labelX(s);
        String name = font.trimToWidth(s.getDisplayName(), Math.round(valueLeft - 6 - lx), "..");
        font.drawString(name, lx, rowY + (h - font.getHeight()) / 2.0F, s.getIndent() > 0 ? skin.dim : skin.text);
    }

    private void drawSection(Setting<?> s, int rowY, int h) {
        CustomFont font = skin.section.get();
        float ty = milk ? rowY : rowY + h - 3 - font.getHeight();
        font.drawString(s.getDisplayName().toUpperCase(), left(), ty, skin.mute, skin.sectionTracking);
    }

    private void drawBoolean(BooleanSetting s, int rowY, int h, boolean hover) {
        if (milk) {
            float sx = right() - Widgets.SWITCH_W;
            drawLabel(s, rowY, h, sx);
            Widgets.toggle(sx, rowY + (h - Widgets.SWITCH_H) / 2.0F, Widgets.SWITCH_W, Widgets.SWITCH_H, s.get(), skin);
            return;
        }
        float box = 10.5F;
        float bx = right() - box;
        float by = rowY + (h - box) / 2.0F;
        drawLabel(s, rowY, h, bx);
        if (s.get()) {
            GlassShader.rect(bx, by, box, box, 2.25F, skin.accent, skin.accent);
            Icons.draw(Icons.Icon.CHECK, bx + 0.75F, by + 0.75F, 9.0F, 3.2F, skin.onAccent);
        } else {
            GlassShader.stroke(bx + 0.75F, by + 0.75F, 9.0F, 9.0F, 2.25F, hover ? 0x80FFFFFF : 0x4DFFFFFF);
        }
    }

    private void drawChips(Row row, int rowY, int mouseX, int mouseY) {
        List<float[]> rects = chipRects(row.chips, rowY);
        for (int i = 0; i < rects.size(); i++) {
            BooleanSetting b = row.chips.get(i);
            float[] r = rects.get(i);
            boolean hover = RenderUtil.hovered(mouseX, mouseY, r[0], r[1], r[2], CHIP_H);
            CustomFont font = b.get() ? CHIP_ON.get() : CHIP.get();
            if (b.get()) {
                GlassShader.rect(r[0], r[1], r[2], CHIP_H, CHIP_H / 2.0F, skin.accent, skin.accent);
            } else {
                int fill = hover ? 0xFFFFFFFF : CHIP_OFF_FILL;
                GlassShader.rect(r[0], r[1], r[2], CHIP_H, CHIP_H / 2.0F, fill, fill);
                GlassShader.stroke(r[0], r[1], r[2], CHIP_H, CHIP_H / 2.0F, skin.fieldLine);
            }
            font.drawCenteredInRect(b.getDisplayName(), r[0], r[1], r[2], CHIP_H, b.get() ? skin.onAccent : skin.dim);
        }
    }

    /** Milk segment {x, w} per mode after the container's inset, or null when the modes do not fit. */
    private float[][] segments(ModeSetting s) {
        CustomFont font = SEGMENT_ON.get();
        List<String> modes = s.getModes();
        float[][] segs = new float[modes.size()][];
        float total = 3.0F;
        for (int i = 0; i < modes.size(); i++) {
            float w = font.getStringWidth(modes.get(i)) + SEGMENT_PAD * 2;
            segs[i] = new float[]{total - 1.5F, w};
            total += w;
        }
        int labelRight = labelX(s) + skin.label.get().getStringWidth(s.getDisplayName()) + 12;
        if (right() - total < labelRight) {
            return null;
        }
        float start = right() - total + 1.5F;
        for (float[] seg : segs) {
            seg[0] += start;
        }
        return segs;
    }

    private void drawMode(ModeSetting s, int rowY, int h, boolean hover) {
        if (milk) {
            float[][] segs = segments(s);
            if (segs != null) {
                float cx = segs[0][0] - 1.5F;
                float cw = right() - cx;
                float cy = rowY + (h - SEGMENT_H) / 2.0F;
                drawLabel(s, rowY, h, cx);
                GlassShader.rect(cx, cy, cw, SEGMENT_H, 6.75F, SEGMENT_FILL, SEGMENT_FILL);
                List<String> modes = s.getModes();
                for (int i = 0; i < modes.size(); i++) {
                    boolean on = modes.get(i).equals(s.get());
                    if (on) {
                        GlassShader.fill(segs[i][0], cy + 1.5F, segs[i][1], SEGMENT_H - 3, 5.25F, 0xFFFFFFFF,
                                3.0F, 0.75F, 0.12F);
                    }
                    CustomFont font = on ? SEGMENT_ON.get() : SEGMENT.get();
                    font.drawCenteredInRect(modes.get(i), segs[i][0], cy + 1.5F, segs[i][1], SEGMENT_H - 3,
                            on ? skin.strong : skin.dim);
                }
                return;
            }
        }
        float[] g = modeGeometry(s);
        drawLabel(s, rowY, h, milk ? g[0] - 6 : g[1]);
        CustomFont vf = skin.value.get();
        if (milk) {
            GlassShader.rect(g[0], rowY + (h - SEGMENT_H) / 2.0F, right() - g[0], SEGMENT_H, SEGMENT_H / 2.0F,
                    SEGMENT_FILL, SEGMENT_FILL);
        }
        vf.drawString(s.get(), g[2], rowY + (h - vf.getHeight()) / 2.0F, milk ? skin.strong : skin.accent);
        int arrow = milk ? (hover ? skin.dim : skin.mute) : (hover ? 0xB3FFFFFF : 0x66FFFFFF);
        float cy = rowY + (h - CHEVRON) / 2.0F;
        Icons.draw(Icons.Icon.CHEVRON_LEFT, g[1], cy, CHEVRON, 3.0F, arrow);
        Icons.draw(Icons.Icon.CHEVRON_RIGHT, right() - CHEVRON - (milk ? 6 : 0), cy, CHEVRON, 3.0F, arrow);
    }

    /** {pillLeft, leftChevronX, valueX} for a chevron mode row. */
    private float[] modeGeometry(ModeSetting s) {
        float inset = milk ? 6.0F : 0.0F;
        float valueRight = right() - inset - CHEVRON - 4.5F;
        float valueX = valueRight - skin.value.get().getStringWidth(s.get());
        float chevX = valueX - 4.5F - CHEVRON;
        return new float[]{chevX - inset, chevX, valueX};
    }

    private void drawSliderText(Setting<?> s, int rowY, String value) {
        CustomFont lf = skin.label.get();
        CustomFont vf = skin.value.get();
        float labelTop = rowY + (milk ? 1 : 3);
        float valueX = right() - vf.getStringWidth(value);
        vf.drawString(value, valueX, labelTop + (lf.getAscent() - vf.getAscent()), milk ? skin.dim : 0xB3FFFFFF);
        int lx = labelX(s);
        lf.drawString(lf.trimToWidth(s.getDisplayName(), Math.round(valueX - 6 - lx), ".."), lx, labelTop,
                s.getIndent() > 0 ? skin.dim : skin.text);
    }

    private SliderTrack sliderTrack(int rowY) {
        return new SliderTrack(left(), rowY + (milk ? 19.0F : 20.0F), right() - left(), skin);
    }

    private static double clamp01(double v) {
        return MathHelper.clamp_double(v, 0.0D, 1.0D);
    }

    private void drawColor(ColorSetting s, int rowY, int h, boolean hover) {
        float size = milk ? 13.0F : 10.5F;
        float bx = right() - size;
        float by = rowY + (h - size) / 2.0F;
        drawLabel(s, rowY, h, bx);
        int rgb = 0xFF000000 | (s.get() & 0xFFFFFF);
        GlassShader.rect(bx, by, size, size, size * 0.25F, rgb, rgb);
        GlassShader.stroke(bx, by, size, size, size * 0.25F, hover ? skin.dim : (milk ? skin.fieldLine : 0x4DFFFFFF));
    }

    private void drawButton(Setting<?> s, int rowY, int h, boolean hover) {
        float bx = left();
        float by = rowY + 2;
        float bw = right() - left();
        float bh = h - 4;
        float r = milk ? bh / 2.0F : 4.5F;
        int fill = milk ? (hover ? 0xFFFFFFFF : 0xCCFFFFFF) : (hover ? 0x24FFFFFF : 0x14FFFFFF);
        GlassShader.rect(bx, by, bw, bh, r, fill, fill);
        GlassShader.stroke(bx, by, bw, bh, r, skin.fieldLine);
        skin.label.get().drawCenteredInRect(s.getDisplayName(), bx, by, bw, bh, skin.strong);
    }

    private void drawCell(int cx, int cy, int size, boolean enabled, boolean hover) {
        int fill = enabled ? (skin.accent & 0x00FFFFFF) | 0x38000000 : skin.field;
        GlassShader.rect(cx, cy, size, size, CELL_RADIUS, fill, fill);
        GlassShader.stroke(cx, cy, size, size, CELL_RADIUS,
                hover ? skin.dim : (enabled ? skin.accent : skin.fieldLine));
    }

    private void drawBedGrid(BedGridSetting grid, int rowY, int mouseX, int mouseY) {
        drawLabel(grid, rowY, skin.rowH, right());
        for (int lz = 0; lz <= 1; lz++) { // bed footprint, not clickable
            GlassShader.rect(bedCellX(0), bedCellY(rowY, lz), BED_CELL, BED_CELL, CELL_RADIUS, skin.accent, skin.accent);
        }
        for (BedGridSetting.Cell cell : grid.getCells()) {
            int bx = bedCellX(cell.lx);
            int by = bedCellY(rowY, cell.lz);
            drawCell(bx, by, BED_CELL, cell.isEnabled(), RenderUtil.hovered(mouseX, mouseY, bx, by, BED_CELL, BED_CELL));
        }
    }

    private BedGridSetting.Cell bedCellAt(BedGridSetting grid, int rowY, int mouseX, int mouseY) {
        for (BedGridSetting.Cell cell : grid.getCells()) {
            if (RenderUtil.hovered(mouseX, mouseY, bedCellX(cell.lx), bedCellY(rowY, cell.lz), BED_CELL, BED_CELL)) {
                return cell;
            }
        }
        return null;
    }

    /** Label plus an Edit pill for the layout window; the pill reads Editing while its tab is showing. */
    private void drawEditRow(Setting<?> s, int rowY, boolean editing) {
        CustomFont font = (milk ? PILL_MILK : PILL).get();
        String text = editing ? "Editing" : "Edit";
        float pw = 4.5F + font.getStringWidth(text) + 3.0F + 6.0F + 4.5F;
        float px = right() - pw;
        float py = rowY + (skin.rowH - PILL_H) / 2.0F;
        drawLabel(s, rowY, skin.rowH, px);
        int ink = editing ? skin.accent : (milk ? skin.dim : 0xB3FFFFFF);
        if (editing) {
            int fill = (skin.accent & 0x00FFFFFF) | 0x38000000;
            GlassShader.rect(px, py, pw, PILL_H, 3.0F, fill, fill);
        }
        GlassShader.stroke(px, py, pw, PILL_H, 3.0F, editing ? skin.accent : (milk ? skin.fieldLine : 0x33FFFFFF));
        font.drawString(text, px + 4.5F, py + (PILL_H - font.getHeight()) / 2.0F, ink);
        Icons.draw(Icons.Icon.ARROW_UP_RIGHT, px + pw - 10.5F, py + (PILL_H - 6.0F) / 2.0F, 6.0F, 3.4F, ink);
    }

    private void drawPreview(Setting<?> s, int rowY) {
        float cw = previewW(s);
        for (int i = 0; i < HotbarSetting.SLOTS; i++) {
            GlassShader.rect(previewX(s, i), rowY + skin.rowH, cw, PREVIEW_H, 2.25F, skin.hover, skin.hover);
            GlassShader.stroke(previewX(s, i), rowY + skin.rowH, cw, PREVIEW_H, 2.25F, skin.fieldLine);
        }
    }

    private boolean isEditing(boolean cleanerTab) {
        return layoutWindow != null && layoutWindow.isCleanerTab() == cleanerTab;
    }

    /** Opens the window on that tab, switches to it, or closes the window when that tab is already showing. */
    private void toggleLayoutWindow(boolean cleanerTab) {
        if (isEditing(cleanerTab)) {
            layoutWindow = null;
        } else if (layoutWindow != null) {
            layoutWindow.showTab(cleanerTab);
        } else {
            layoutWindow = new LayoutWindow(skin, setting(HotbarSetting.class), setting(CleanerSetting.class),
                    cleanerTab, () -> layoutWindow = null);
        }
    }

    private <T> T setting(Class<T> type) {
        for (Setting<?> s : module.getSettings()) {
            if (type.isInstance(s)) {
                return type.cast(s);
            }
        }
        return null;
    }

    /** Drawn after render so item icons sit above the cell frames. */
    public void renderItems(int mouseX, int mouseY) {
        if (!beginBodyScissor()) {
            return;
        }
        int top = bodyTopY();
        for (Row row : rows) {
            Setting<?> s = row.setting;
            int rowY = top + row.top;
            if (s instanceof ItemGridSetting) {
                List<ItemGridSetting.Entry> entries = ((ItemGridSetting) s).getEntries();
                for (int i = 0; i < entries.size(); i++) {
                    RenderUtil.drawItem(entries.get(i).getIcon(), colX(i) + 1, cellY(rowY, i) + 1);
                }
            } else if (s instanceof HotbarSetting) {
                renderPreviewItems((HotbarSetting) s, rowY);
            }
        }
        RenderUtil.endScissor();
    }

    private void renderPreviewItems(HotbarSetting hb, int rowY) {
        float inset = (previewW(hb) - 9.0F) / 2.0F;
        List<List<String>> slots = hb.getSlots();
        RenderUtil.beginItems();
        for (int i = 0; i < HotbarSetting.SLOTS; i++) {
            if (!slots.get(i).isEmpty()) {
                RenderUtil.drawItemRaw(hb.iconForKey(slots.get(i).get(0)), previewX(hb, i) + inset,
                        rowY + skin.rowH + (PREVIEW_H - 9.0F) / 2.0F, 0.5625F);
            }
        }
        RenderUtil.endItems();
    }

    /** The window sits beside the panel, placed each frame so it follows a dragged panel. */
    public void renderLayoutWindow(int mouseX, int mouseY) {
        if (layoutWindow != null) {
            layoutWindow.place(x, width, y, screen.width, screen.height);
            layoutWindow.render(mouseX, mouseY);
        }
    }

    /** True if an overlay was open and got closed. */
    public boolean closeOverlay() {
        if (activeColor != null) {
            activeColor = null;
            return true;
        }
        if (layoutWindow != null) {
            layoutWindow = null;
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
        int titleH = skin.label.get().getHeight() + 5;
        int readoutH = skin.value.get().getHeight() + 3;
        cpW = CP_PAD * 2 + SB_SIZE + CP_GAP + HUE_W;
        cpH = CP_PAD * 2 + titleH + SB_SIZE + 3 + readoutH;
        cpX = MathHelper.clamp_int(anchorX, 0, Math.max(0, screen.width - cpW));
        cpY = MathHelper.clamp_int(anchorY, 0, Math.max(0, screen.height - cpH));
        cpSbX = cpX + CP_PAD;
        cpSbY = cpY + CP_PAD + titleH;
        cpHueX = cpSbX + SB_SIZE + CP_GAP;
    }

    /** The gradients are tiled from flat rects; there is no gradient primitive. */
    public void renderColorPicker(int mouseX, int mouseY) {
        if (activeColor == null) {
            return;
        }
        GlassShader.panel(cpX, cpY, cpW, cpH, skin.radius, skin.glass);
        skin.label.get().drawString(activeColor.getDisplayName(), cpX + CP_PAD, cpY + CP_PAD, skin.text);
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
        for (int dy = 0; dy < SB_SIZE; dy += HUE_STEP) {
            int ch = Math.min(HUE_STEP, SB_SIZE - dy);
            float h = dy / (float) SB_SIZE;
            RenderUtil.rect(cpHueX, cpSbY + dy, HUE_W, ch, 0xFF000000 | (Color.HSBtoRGB(h, 1f, 1f) & 0xFFFFFF));
        }
        int selX = cpSbX + Math.round(curS * SB_SIZE);
        int selY = cpSbY + Math.round((1f - curB) * SB_SIZE);
        GlassShader.stroke(selX - 3.5F, selY - 3.5F, 7.0F, 7.0F, 3.5F, 0xFF000000);
        GlassShader.stroke(selX - 2.5F, selY - 2.5F, 5.0F, 5.0F, 2.5F, 0xFFFFFFFF);
        int hueSelY = cpSbY + Math.round(curH * SB_SIZE);
        GlassShader.rect(cpHueX - 1, hueSelY - 1, HUE_W + 2, 2, 1.0F, 0xFFFFFFFF, 0xFFFFFFFF);
        int rgb = activeColor.get();
        String txt = "R" + ((rgb >> 16) & 0xFF) + " G" + ((rgb >> 8) & 0xFF) + " B" + (rgb & 0xFF);
        skin.value.get().drawString(txt, cpSbX, cpSbY + SB_SIZE + 3, skin.dim);
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

    // ---- Input ----

    public boolean mouseClicked(int mouseX, int mouseY, int button) {
        // the picker can extend outside the panel, so test it before the bounds check
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
        if (layoutWindow != null) {
            if (layoutWindow.contains(mouseX, mouseY)) {
                layoutWindow.mouseClicked(mouseX, mouseY, button);
                return true;
            }
            layoutWindow.unfocus();
        }

        layout();
        clampBodyScroll();
        if (!RenderUtil.hovered(mouseX, mouseY, x, y, width, getHeight())) {
            return false;
        }
        if (!milk && RenderUtil.hovered(mouseX, mouseY, x, y, width, skin.header)) {
            if (button == 0 && hitsClose(mouseX, mouseY)) {
                onClose.run();
            } else if (button == 0) {
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
        Row row = rowAt(mouseY);
        if (row != null) {
            handleClick(row, button, mouseX, mouseY, bodyTopY() + row.top);
        }
        return true;
    }

    private void handleClick(Row row, int button, int mouseX, int mouseY, int rowY) {
        Setting<?> s = row.setting;
        if (row.chips != null) {
            int i = chipAt(row, rowY, mouseX, mouseY);
            if (button == 0 && i >= 0) {
                row.chips.get(i).toggle();
                save();
            }
        } else if (s instanceof BooleanSetting) {
            if (button == 0) {
                ((BooleanSetting) s).toggle();
                save();
            }
        } else if (s instanceof ModeSetting) {
            clickMode((ModeSetting) s, button, mouseX);
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
                openColorPicker((ColorSetting) s, x + width + 4, rowY);
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
        } else if (s instanceof HotbarSetting || s instanceof CleanerSetting) {
            if (button == 0) {
                toggleLayoutWindow(s instanceof CleanerSetting);
            }
        } else if (s instanceof ButtonSetting) {
            if (button == 0) {
                ((ButtonSetting) s).run();
            }
        }
    }

    /** Segments pick directly; otherwise the left chevron steps back and the rest steps forward. */
    private void clickMode(ModeSetting s, int button, int mouseX) {
        if (button == 1) {
            s.cycle(-1);
            save();
            return;
        }
        if (button != 0) {
            return;
        }
        float[][] segs = milk ? segments(s) : null;
        if (segs != null) {
            for (int i = 0; i < segs.length; i++) {
                if (mouseX >= segs[i][0] && mouseX < segs[i][0] + segs[i][1]) {
                    s.set(s.getModes().get(i));
                    save();
                }
            }
            return;
        }
        float[] g = modeGeometry(s);
        s.cycle(mouseX < g[2] && mouseX >= g[1] - 3 ? -1 : 1);
        save();
    }

    public void drag(int mouseX, int mouseY, int screenWidth, int screenHeight) {
        if (layoutWindow != null && layoutWindow.drag(mouseX, mouseY, screenWidth, screenHeight)) {
            return;
        }
        if (draggingSB) {
            updateSB(mouseX, mouseY);
            return;
        }
        if (draggingHue) {
            updateHue(mouseY);
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
        return sliderTrack(0).nearestHandle(range.getLo(), range.getHi(), range.getMin(), range.getMax(), mouseX);
    }

    private void updateRangeSlider(int mouseX) {
        double value = sliderTrack(0).valueAt(mouseX, draggingRange.getMin(), draggingRange.getMax());
        // clamp against the other handle so they cannot cross
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
        if (layoutWindow != null) {
            layoutWindow.mouseReleased();
        }
    }

    private int gridCellAt(int rowY, int count, int mouseX, int mouseY) {
        for (int i = 0; i < count; i++) {
            if (RenderUtil.hovered(mouseX, mouseY, colX(i), cellY(rowY, i), CELL, CELL)) {
                return i;
            }
        }
        return -1;
    }

    public boolean isSearching() {
        return layoutWindow != null && layoutWindow.isSearching();
    }

    public void charTyped(char typedChar, int keyCode) {
        layoutWindow.charTyped(typedChar, keyCode);
    }

    public void scroll(int dWheel, int mouseX, int mouseY) {
        if (layoutWindow != null && layoutWindow.contains(mouseX, mouseY)) {
            layoutWindow.scroll(dWheel, mouseX, mouseY);
            return;
        }
        layout();
        clampBodyScroll();
        if (!bodyContains(mouseX, mouseY)) {
            return;
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

    /** Widest value text; a value one increment inside the bounds can be wider than either endpoint. */
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
