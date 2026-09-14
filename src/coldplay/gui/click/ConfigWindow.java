package coldplay.gui.click;

import coldplay.ColdPlay;
import coldplay.module.ModuleManager;
import coldplay.config.ConfigManager;
import coldplay.gui.Theme;
import coldplay.gui.CustomSearchField;
import coldplay.gui.CustomTextInput;
import coldplay.gui.StyledButton;
import coldplay.util.RenderUtil;
import coldplay.util.font.CustomFont;
import coldplay.util.font.Fonts;

import net.minecraft.util.MathHelper;
import org.lwjglx.input.Keyboard;

import java.util.List;

/** Config profile window opened from the Click GUI settings flyout. */
public class ConfigWindow {
    private static final int WIDTH = 200;
    private static final int HEADER = ClickGuiScreen.HEADER_HEIGHT;
    private static final int NAME_ROW_H = 28;
    private static final int FIELD_H = 16;
    private static final int PAD = 6;
    private static final int FIELD_GAP = 6;
    private static final int CREATE_FALLBACK_W = 50;
    private static final int MIN_FIELD_W = 110;
    private static final int ICON_W = 16;
    private static final int ICON_H = 12;
    private static final int ICON_GAP = 3;
    private static final int PROFILE_ROW_H = 22;
    private static final int MAX_VISIBLE_ROWS = 7; // fits a 240px-high screen
    private static final int MAX_NAME_LEN = 24;
    private static final int STATUS_H = 12;
    private static final long STATUS_HOLD_MS = 2500L;

    private final ClickGuiScreen screen;
    private final int screenWidth;
    private final int screenHeight;

    private final int createW;
    private int x;
    private int y;
    private List<String> names;
    private int scrollOffset;

    private String nameInput = "";
    private boolean fieldFocused;
    private int cursorCounter;
    private String pendingDelete; // null unless a delete awaits confirmation
    private String status = "";
    private int statusColor;
    private long statusExpiresAt; // wall-clock millis

    public ConfigWindow(ClickGuiScreen screen, int screenWidth, int screenHeight) {
        this.screen = screen;
        this.screenWidth = screenWidth;
        this.screenHeight = screenHeight;
        this.createW = computeCreateW();
        refresh();
        // centered once; refresh() must not move the window
        x = (screenWidth - WIDTH) / 2;
        y = (screenHeight - height()) / 2;
        clampToScreen();
    }

    private static int computeCreateW() {
        CustomFont font = Fonts.medium;
        return font == null ? CREATE_FALLBACK_W : font.getStringWidth("Create") + 8;
    }

    static int fieldWidthFor(int createW) {
        return WIDTH - PAD * 2 - FIELD_GAP - createW;
    }

    private int height() {
        int rows = Math.max(1, Math.min(names.size(), MAX_VISIBLE_ROWS));
        // the status row is always reserved to keep the height stable
        return HEADER + NAME_ROW_H + rows * PROFILE_ROW_H + STATUS_H + PAD;
    }

    private int fieldX() {
        return x + PAD;
    }

    private int fieldY() {
        return y + HEADER + (NAME_ROW_H - FIELD_H) / 2;
    }

    private int createX() {
        return x + WIDTH - PAD - createW;
    }

    private int fieldWidth() {
        return fieldWidthFor(createW);
    }

    private int rowsTop() {
        return y + HEADER + NAME_ROW_H;
    }

    private int deleteIconX() {
        return x + WIDTH - PAD - ICON_W;
    }

    private int renameIconX() {
        return deleteIconX() - ICON_GAP - ICON_W;
    }

    private int updateIconX() {
        return renameIconX() - ICON_GAP - ICON_W;
    }

    private static int iconY(int rowY) {
        return rowY + (PROFILE_ROW_H - ICON_H) / 2;
    }

    public boolean contains(int mouseX, int mouseY) {
        return RenderUtil.hovered(mouseX, mouseY, x, y, WIDTH, height());
    }

    public void render(int mouseX, int mouseY) {
        CustomFont font = Fonts.medium;
        if (font == null) {
            return;
        }
        cursorCounter++;
        int h = height();

        ClickGuiScreen.drawWindowBase(x, y, WIDTH, h);
        ClickGuiScreen.drawWindowHeader(font, "Config", x, y, WIDTH, mouseX, mouseY);

        renderNameRow(font, mouseX, mouseY);

        int visible = Math.min(names.size(), MAX_VISIBLE_ROWS);
        int top = rowsTop();
        for (int i = 0; i < visible; i++) {
            int rowY = top + i * PROFILE_ROW_H;
            renderRow(font, names.get(scrollOffset + i), rowY, mouseX, mouseY);
            if (i > 0) {
                RenderUtil.rectBounds(x, rowY, x + WIDTH, rowY + 1, Theme.SEP);
            }
        }
        if (names.isEmpty()) {
            float ty = top + (PROFILE_ROW_H - font.getHeight()) / 2f;
            font.drawString("No profiles yet", x + PAD, ty, Theme.TEXT_MUTE);
        }
        renderScrollIndicator(visible);

        renderStatus(font, h);

        ClickGuiScreen.drawWindowFrame(x, y, WIDTH, h);
    }

    private void renderNameRow(CustomFont font, int mouseX, int mouseY) {
        int fy = fieldY();
        CustomSearchField.draw(font, fieldX(), fy, fieldWidth(), FIELD_H, nameInput, fieldFocused,
                "config name", cursorCounter);

        StyledButton.draw(font, "Create", createX(), fy, createW, FIELD_H,
                mouseX, mouseY, true, Theme.WELL, Theme.TEXT);
    }

    /** Indicator only; scrolling is by wheel. */
    private void renderScrollIndicator(int visible) {
        int maxOffset = names.size() - MAX_VISIBLE_ROWS;
        if (maxOffset <= 0) {
            return;
        }
        int trackX = x + WIDTH - 2;
        int trackY = rowsTop();
        int trackH = visible * PROFILE_ROW_H;
        int thumbH = RenderUtil.scrollThumbHeight(trackH, visible, names.size());
        RenderUtil.rect(trackX, trackY, 1, trackH, Theme.SEP);
        RenderUtil.rect(trackX, trackY + RenderUtil.scrollThumbOffset(trackH, thumbH, scrollOffset, maxOffset),
                1, thumbH, Theme.FROST);
    }

    private void renderStatus(CustomFont font, int h) {
        if (status.isEmpty() || System.currentTimeMillis() >= statusExpiresAt) {
            return;
        }
        float ty = y + h - PAD - STATUS_H + (STATUS_H - font.getHeight()) / 2f;
        font.drawString(font.trimToWidth(status, WIDTH - PAD * 2, "..."), x + PAD, ty, statusColor);
    }

    private void renderRow(CustomFont font, String name, int rowY, int mouseX, int mouseY) {
        int nameW = updateIconX() - ICON_GAP - (x + PAD);
        if (RenderUtil.hovered(mouseX, mouseY, x, rowY, nameW + PAD, PROFILE_ROW_H)) {
            RenderUtil.rect(x, rowY, nameW + PAD, PROFILE_ROW_H, Theme.HOVER_LIFT);
        }
        float textY = rowY + (PROFILE_ROW_H - font.getHeight()) / 2f;
        font.drawString(font.trimToWidth(name, nameW, "..."), x + PAD, textY, Theme.TEXT);

        int iy = iconY(rowY);
        boolean armed = name.equals(pendingDelete);
        drawIconButton(updateIconX(), iy, Theme.WELL, mouseX, mouseY);
        glyphUpdate(updateIconX() + 2, iy, Theme.TEXT_DIM, Theme.WELL);
        drawIconButton(renameIconX(), iy, Theme.WELL, mouseX, mouseY);
        glyphRename(renameIconX() + 2, iy, Theme.TEXT_DIM);
        drawIconButton(deleteIconX(), iy, armed ? Theme.DANGER : Theme.WELL, mouseX, mouseY);
        glyphDelete(deleteIconX() + 2, iy, armed ? Theme.TEXT : Theme.DANGER,
                armed ? Theme.DANGER : Theme.WELL);
    }

    private static void drawIconButton(int bx, int by, int bg, int mouseX, int mouseY) {
        RenderUtil.rect(bx, by, ICON_W, ICON_H, bg);
        RenderUtil.outline(bx, by, bx + ICON_W, by + ICON_H, 1,
                RenderUtil.hovered(mouseX, mouseY, bx, by, ICON_W, ICON_H) ? Theme.CONTOUR : Theme.SEP);
    }

    // 12x12 pixel glyphs; cutouts are painted in the button background.
    private static void glyphUpdate(int gx, int gy, int fg, int bg) {
        RenderUtil.rect(gx + 1, gy + 1, 10, 10, fg); // floppy body
        RenderUtil.rect(gx + 3, gy + 2, 6, 3, bg);   // shutter
        RenderUtil.rect(gx + 3, gy + 7, 6, 4, bg);   // label
    }

    private static void glyphRename(int gx, int gy, int fg) {
        RenderUtil.rect(gx + 7, gy + 1, 4, 2, fg);   // pencil, stepped down the diagonal
        RenderUtil.rect(gx + 5, gy + 3, 4, 2, fg);
        RenderUtil.rect(gx + 3, gy + 5, 4, 2, fg);
        RenderUtil.rect(gx + 1, gy + 7, 4, 2, fg);
        RenderUtil.rect(gx + 1, gy + 9, 2, 2, fg);   // tip
    }

    private static void glyphDelete(int gx, int gy, int fg, int bg) {
        RenderUtil.rect(gx + 4, gy, 4, 1, fg);       // handle
        RenderUtil.rect(gx + 2, gy + 1, 8, 2, fg);   // lid
        RenderUtil.rect(gx + 2, gy + 3, 8, 9, fg);   // body
        RenderUtil.rect(gx + 4, gy + 5, 1, 5, bg);   // slots
        RenderUtil.rect(gx + 7, gy + 5, 1, 5, bg);
    }

    /** Consumes every click inside the window. */
    public boolean mouseClicked(int mouseX, int mouseY, int button) {
        if (!contains(mouseX, mouseY)) {
            return false;
        }
        if (button != 0) {
            return true;
        }
        if (Fonts.medium == null) {
            return true;
        }
        String armed = pendingDelete;
        pendingDelete = null; // any other click disarms the delete
        if (ClickGuiScreen.hitsClose(x, y, WIDTH, HEADER, mouseX, mouseY)) {
            screen.closeConfigWindow();
            return true;
        }
        if (RenderUtil.hovered(mouseX, mouseY, fieldX(), fieldY(), fieldWidth(), FIELD_H)) {
            fieldFocused = true;
            cursorCounter = 0;
            return true;
        }
        fieldFocused = false;
        if (RenderUtil.hovered(mouseX, mouseY, createX(), fieldY(), createW, FIELD_H)) {
            create();
            return true;
        }
        int visible = Math.min(names.size(), MAX_VISIBLE_ROWS);
        int top = rowsTop();
        for (int i = 0; i < visible; i++) {
            int rowY = top + i * PROFILE_ROW_H;
            if (!RenderUtil.hovered(mouseX, mouseY, x, rowY, WIDTH, PROFILE_ROW_H)) {
                continue;
            }
            String name = names.get(scrollOffset + i);
            int iy = iconY(rowY);
            if (RenderUtil.hovered(mouseX, mouseY, updateIconX(), iy, ICON_W, ICON_H)) {
                update(name);
            } else if (RenderUtil.hovered(mouseX, mouseY, renameIconX(), iy, ICON_W, ICON_H)) {
                rename(name);
            } else if (RenderUtil.hovered(mouseX, mouseY, deleteIconX(), iy, ICON_W, ICON_H)) {
                if (name.equals(armed)) {
                    delete(name);
                } else {
                    pendingDelete = name;
                }
            } else if (mouseX < updateIconX() - ICON_GAP) {
                load(name);
            }
            return true;
        }
        return true;
    }

    public boolean isFieldFocused() {
        return fieldFocused;
    }

    public void charTyped(char typedChar, int keyCode) {
        if (keyCode == Keyboard.KEY_RETURN) {
            create();
            return;
        }
        CustomTextInput.EditResult edit = CustomTextInput.edit(
                nameInput, typedChar, keyCode, MAX_NAME_LEN);
        nameInput = edit.getValue();
        fieldFocused = edit.isFocused();
    }

    public void scroll(int dWheel, int mouseX, int mouseY) {
        if (dWheel == 0 || !contains(mouseX, mouseY)) {
            return;
        }
        pendingDelete = null;
        int maxOffset = Math.max(0, names.size() - MAX_VISIBLE_ROWS);
        if (maxOffset == 0) {
            return;
        }
        scrollOffset = MathHelper.clamp_int(scrollOffset + (dWheel > 0 ? -1 : 1), 0, maxOffset);
    }

    private void setStatus(String message, int color) {
        status = message;
        statusColor = color;
        statusExpiresAt = System.currentTimeMillis() + STATUS_HOLD_MS;
    }

    private void create() {
        String n = ConfigManager.sanitizeName(nameInput);
        if (n.isEmpty()) {
            setStatus("Enter a name", Theme.DANGER);
            return;
        }
        if (containsIgnoreCase(n)) {
            setStatus("Name in use", Theme.DANGER);
            return;
        }
        if (!ColdPlay.getInstance().getConfigManager().saveProfile(n, moduleManager())) {
            setStatus("Create failed", Theme.DANGER);
            return;
        }
        nameInput = "";
        setStatus("Created", Theme.TEXT);
        refresh();
    }

    private void update(String name) {
        boolean saved = ColdPlay.getInstance().getConfigManager().saveProfile(name, moduleManager());
        setStatus(saved ? "Saved" : "Save failed", saved ? Theme.TEXT : Theme.DANGER);
    }

    private void rename(String old) {
        String n = ConfigManager.sanitizeName(nameInput);
        if (n.isEmpty()) {
            nameInput = old;
            fieldFocused = true;
            cursorCounter = 0;
            setStatus("Edit the name", Theme.TEXT);
            return;
        }
        if (containsIgnoreCase(n)) {
            setStatus("Name in use", Theme.DANGER);
            return;
        }
        if (!ColdPlay.getInstance().getConfigManager().renameProfile(old, n)) {
            setStatus("Rename failed", Theme.DANGER);
            return;
        }
        nameInput = "";
        setStatus("Renamed", Theme.TEXT);
        refresh();
    }

    private void delete(String name) {
        boolean deleted = ColdPlay.getInstance().getConfigManager().deleteProfile(name);
        setStatus(deleted ? "Deleted" : "Delete failed", deleted ? Theme.TEXT : Theme.DANGER);
        refresh();
    }

    private void load(String name) {
        if (!ColdPlay.getInstance().getConfigManager().loadProfile(name, moduleManager())) {
            setStatus("Load failed", Theme.DANGER);
            return;
        }
        ColdPlay.getInstance().saveConfig();
        setStatus("Loaded", Theme.TEXT);
    }

    private boolean containsIgnoreCase(String n) {
        for (String existing : names) {
            if (existing.equalsIgnoreCase(n)) {
                return true;
            }
        }
        return false;
    }

    private static ModuleManager moduleManager() {
        return ColdPlay.getInstance().getModuleManager();
    }

    private void clampToScreen() {
        x = MathHelper.clamp_int(x, 0, Math.max(0, screenWidth - WIDTH));
        y = MathHelper.clamp_int(y, 0, Math.max(0, screenHeight - height()));
    }

    private void refresh() {
        names = ColdPlay.getInstance().getConfigManager().listConfigs();
        int maxOffset = Math.max(0, names.size() - MAX_VISIBLE_ROWS);
        scrollOffset = MathHelper.clamp_int(scrollOffset, 0, maxOffset);
        clampToScreen();
    }
}
