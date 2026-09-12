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

/** Profile management window. ClickGuiScreen owns dismissal and input priority. */
public class ConfigWindow {
    private static final int WIDTH = 200;
    private static final int HEADER = ClickGuiScreen.HEADER_HEIGHT;
    private static final int NAME_ROW_H = 28;           // name field + create button row
    private static final int FIELD_H = 16;              // text field / create button height
    private static final int PAD = 6;                   // outer left/right/bottom padding
    private static final int FIELD_GAP = 6;              // gap between the name field and Create
    private static final int CREATE_FALLBACK_W = 50;     // scale-1 width, used only if the font is absent
    private static final int MIN_FIELD_W = 110;          // the name field must stay usable as Create rebakes
    private static final int ICON_W = 16;                // action button: 12px glyph box plus 2px each side
    private static final int ICON_H = 12;
    private static final int ICON_GAP = 3;
    private static final int PROFILE_ROW_H = 22;         // one line: name plus the three action glyphs
    private static final int MAX_VISIBLE_ROWS = 7;       // 7 single-line rows still land inside 240px screens
    private static final int MAX_NAME_LEN = 24;
    private static final int STATUS_H = 12;              // always-reserved result line: the tallest bake is 12px
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
    private int cursorCounter; // frame counter for the blinking caret
    private String pendingDelete; // profile armed for delete-confirm (red button), or null
    private String status = "";
    private int statusColor;
    private long statusExpiresAt; // wall-clock milliseconds

    public ConfigWindow(ClickGuiScreen screen, int screenWidth, int screenHeight) {
        this.screen = screen;
        this.screenWidth = screenWidth;
        this.screenHeight = screenHeight;
        this.createW = computeCreateW();
        refresh();
        // Center once only; refresh() must not move the window or rows shift under the cursor.
        x = (screenWidth - WIDTH) / 2;
        y = (screenHeight - height()) / 2;
        clampToScreen();
    }

    /**
     * Create is the one control sized from text, and the font is re-baked per GUI scale.
     * Measured once — a scale change resizes the screen, and initGui() drops this window.
     */
    private static int computeCreateW() {
        CustomFont font = Fonts.medium;
        return font == null ? CREATE_FALLBACK_W : font.getStringWidth("Create") + 8;
    }

    /** Font-free so a check can pin the field against the widest Create bake. */
    static int fieldWidthFor(int createW) {
        return WIDTH - PAD * 2 - FIELD_GAP - createW;
    }

    private int height() {
        // An empty list still reserves one row, so the window is never a bare header strip.
        int rows = Math.max(1, Math.min(names.size(), MAX_VISIBLE_ROWS));
        // Reserve status space so feedback cannot recenter rows beneath the cursor.
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

    // Action glyphs are anchored to the right edge, so a wider Create can only eat into the
    // name field — never push a control past the frame the way the old label row could.
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
            // Otherwise a fresh install shows an unexplained empty band under the name field.
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

    /** A 1px edge indicator; the wheel already scrolls, this only advertises that rows are hidden. */
    private void renderScrollIndicator(int visible) {
        int maxOffset = names.size() - MAX_VISIBLE_ROWS;
        if (maxOffset <= 0) {
            return;
        }
        int trackX = x + WIDTH - 2; // clears the glyphs, which end PAD inside the frame
        int trackY = rowsTop();
        int trackH = visible * PROFILE_ROW_H;
        // Rows are uniform, so row counts carry the same ratios the helpers need.
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
        // Lift only the name span: it loads the profile, while the glyphs hover for themselves.
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
        // Armed delete floods the button instead of swapping a label; there is no label to swap.
        drawIconButton(deleteIconX(), iy, armed ? Theme.DANGER : Theme.WELL, mouseX, mouseY);
        glyphDelete(deleteIconX() + 2, iy, armed ? Theme.TEXT : Theme.DANGER,
                armed ? Theme.DANGER : Theme.WELL);
    }

    /** StyledButton's chrome without a label: fill, then the same idle/hover outline. */
    private static void drawIconButton(int bx, int by, int bg, int mouseX, int mouseY) {
        RenderUtil.rect(bx, by, ICON_W, ICON_H, bg);
        RenderUtil.outline(bx, by, bx + ICON_W, by + ICON_H, 1,
                RenderUtil.hovered(mouseX, mouseY, bx, by, ICON_W, ICON_H) ? Theme.CONTOUR : Theme.SEP);
    }

    // 12x12 glyphs drawn as solid runs, so they stay on the pixel grid the bundled font uses.
    // Cutouts are painted in the button's own background rather than punched out.
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

    /** Consumes every click within the window, including inactive areas. */
    public boolean mouseClicked(int mouseX, int mouseY, int button) {
        if (!contains(mouseX, mouseY)) {
            return false;
        }
        if (button != 0) {
            return true;
        }
        if (Fonts.medium == null) {
            return true; // nothing was rendered, so nothing is clickable
        }
        String armed = pendingDelete;
        pendingDelete = null; // Only a second Delete click on the same row confirms.
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
            // the gaps between glyphs stay inert: a mis-click near Delete must not load a profile
            return true;
        }
        return true;
    }

    public boolean isFieldFocused() {
        return fieldFocused;
    }

    public void charTyped(char typedChar, int keyCode) {
        if (keyCode == Keyboard.KEY_RETURN) {
            create(); // the only unambiguous target; Rename still needs the row it applies to
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
        pendingDelete = null; // an armed delete must not scroll under the cursor onto another row
        int maxOffset = Math.max(0, names.size() - MAX_VISIBLE_ROWS);
        if (maxOffset == 0) {
            return;
        }
        scrollOffset = MathHelper.clamp_int(scrollOffset + (dWheel > 0 ? -1 : 1), 0, maxOffset);
    }

    /** Verb only: the strip is a bounded width, but a profile name is any width. */
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
            return; // keep the typed name so the click can just be retried
        }
        nameInput = "";
        setStatus("Created", Theme.TEXT);
        refresh();
    }

    /** Overwrites an existing profile, so the name list cannot change and needs no refresh(). */
    private void update(String name) {
        boolean saved = ColdPlay.getInstance().getConfigManager().saveProfile(name, moduleManager());
        setStatus(saved ? "Saved" : "Save failed", saved ? Theme.TEXT : Theme.DANGER);
    }

    private void rename(String old) {
        String n = ConfigManager.sanitizeName(nameInput);
        if (n.isEmpty()) {
            // Arm the shared field instead of dead-ending: nothing else says it is the rename target.
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
            return; // nothing was applied, so there is nothing new to persist
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

    /** Keeps a height change on-screen without re-centering, which would shift rows under the cursor. */
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
