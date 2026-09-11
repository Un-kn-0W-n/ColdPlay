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
    private static final int MIN_WIDTH = 200;           // floor: leaves the name field real room beside Create
    private static final int HEADER = ClickGuiScreen.HEADER_HEIGHT;
    private static final int ROW_H = ClickGuiScreen.ROW_HEIGHT;
    private static final int NAME_ROW_H = 28;           // name field + create button row
    private static final int FIELD_H = 16;              // text field / create button height
    private static final int BTN_H = ROW_H - 4;          // per-row Upd/Ren/Del button height
    private static final int PAD = 6;                   // outer left/right/bottom padding
    private static final int BTN_GAP = 2;                // floor for the gap between adjacent action buttons
    private static final int FIELD_GAP = 6;              // gap between the name field and Create
    private static final int CREATE_FALLBACK_W = 50;     // scale-1 width, used only if the font is absent
    private static final int BTN_TEXT_PAD = 4;           // horizontal text padding inside an action button
    private static final int BTN_BOT_PAD = 5;            // gap below the button line inside a row
    private static final int PROFILE_ROW_H = ROW_H + BTN_H + BTN_BOT_PAD; // name line + action-button line
    private static final int MAX_VISIBLE_ROWS = 5;       // two-line rows: 5 keeps the window inside 240px screens
    private static final int MAX_NAME_LEN = 24;
    private static final int STATUS_H = 12;              // always-reserved result line: the tallest bake is 12px
    private static final long STATUS_HOLD_MS = 2500L;

    private final ClickGuiScreen screen;
    private final int screenWidth;
    private final int screenHeight;

    private final int width;
    private final int createW;
    private int x;
    private int y;
    private List<String> names;
    private int scrollOffset;

    private String nameInput = "";
    private boolean fieldFocused;
    private int cursorCounter; // frame counter for the blinking caret
    private String pendingDelete; // profile armed for delete-confirm ("Confirm?" state), or null
    private String status = "";
    private int statusColor;
    private long statusExpiresAt; // wall-clock milliseconds

    public ConfigWindow(ClickGuiScreen screen, int screenWidth, int screenHeight) {
        this.screen = screen;
        this.screenWidth = screenWidth;
        this.screenHeight = screenHeight;
        this.width = computeWidth();
        this.createW = computeCreateW();
        refresh();
        // Center once only; refresh() must not move the window or rows shift under the cursor.
        x = (screenWidth - width) / 2;
        y = (screenHeight - height()) / 2;
        clampToScreen();
    }

    /**
     * Font metrics are baked per GUI scale, so a fixed width only fits the action row at scale 1.
     * measured once — a scale change resizes the screen, and initGui() drops this window.
     */
    private static int computeWidth() {
        CustomFont font = Fonts.medium;
        if (font == null) {
            return MIN_WIDTH; // atlases not baked yet; nothing is rendered or clickable either
        }
        return windowWidth(btnW(font, "Update"), btnW(font, "Rename"), deleteBtnW(font));
    }

    /** Create has the same per-scale bake problem as the action row: a fixed width never fit its label. */
    private static int computeCreateW() {
        CustomFont font = Fonts.medium;
        return font == null ? CREATE_FALLBACK_W : btnW(font, "Create");
    }

    /** Font-free so a check can pin the action-row fit at each scale's measured button widths. */
    static int windowWidth(int updateW, int renameW, int deleteW) {
        return Math.max(MIN_WIDTH, PAD * 2 + updateW + BTN_GAP + renameW + BTN_GAP + deleteW);
    }

    private int height() {
        int rows = Math.min(names.size(), MAX_VISIBLE_ROWS);
        // Reserve status space so feedback cannot recenter rows beneath the cursor.
        // An empty list still reserves one line, so the window is never a bare header strip.
        return HEADER + NAME_ROW_H + (rows == 0 ? ROW_H : rows * PROFILE_ROW_H) + STATUS_H + PAD;
    }

    private int fieldX() {
        return x + PAD;
    }

    private int fieldY() {
        return y + HEADER + (NAME_ROW_H - FIELD_H) / 2;
    }

    private int createX() {
        return x + width - PAD - createW;
    }

    private int fieldWidth() {
        return createX() - FIELD_GAP - fieldX();
    }

    private int rowsTop() {
        return y + HEADER + NAME_ROW_H;
    }

    private static int btnW(CustomFont font, String label) {
        return font.getStringWidth(label) + BTN_TEXT_PAD * 2;
    }

    /** Delete keeps one width in both states so the button never resizes under the cursor mid-confirm. */
    private static int deleteBtnW(CustomFont font) {
        return Math.max(btnW(font, "Delete"), btnW(font, "Confirm?"));
    }

    private int updateX() {
        return x + PAD;
    }

    /** Spread the three buttons over the inner width, so a window wider than them has no dead right edge. */
    private int actionGap(CustomFont font) {
        int slack = width - PAD * 2 - btnW(font, "Update") - btnW(font, "Rename") - deleteBtnW(font);
        return Math.max(BTN_GAP, slack / 2); // floor-divided, so the row can only end short, never past PAD
    }

    private int renameX(CustomFont font) {
        return updateX() + btnW(font, "Update") + actionGap(font);
    }

    private int deleteX(CustomFont font) {
        return renameX(font) + btnW(font, "Rename") + actionGap(font);
    }

    private static int btnLineY(int rowY) {
        return rowY + ROW_H;
    }

    public boolean contains(int mouseX, int mouseY) {
        return RenderUtil.hovered(mouseX, mouseY, x, y, width, height());
    }

    public void render(int mouseX, int mouseY) {
        CustomFont font = Fonts.medium;
        if (font == null) {
            return;
        }
        cursorCounter++;
        int h = height();

        ClickGuiScreen.drawWindowBase(x, y, width, h);
        ClickGuiScreen.drawWindowHeader(font, "Config", x, y, width, mouseX, mouseY);

        renderNameRow(font, mouseX, mouseY);

        int visible = Math.min(names.size(), MAX_VISIBLE_ROWS);
        int top = rowsTop();
        for (int i = 0; i < visible; i++) {
            int rowY = top + i * PROFILE_ROW_H;
            renderRow(font, names.get(scrollOffset + i), rowY, mouseX, mouseY);
            if (i > 0) {
                RenderUtil.rectBounds(x, rowY, x + width, rowY + 1, Theme.SEP);
            }
        }
        if (names.isEmpty()) {
            // Otherwise a fresh install shows an unexplained empty band under the name field.
            font.drawString("No profiles yet", x + PAD, top + (ROW_H - font.getHeight()) / 2f, Theme.TEXT_MUTE);
        }
        renderScrollIndicator(visible);

        renderStatus(font, h);

        ClickGuiScreen.drawWindowFrame(x, y, width, h);
    }

    private void renderNameRow(CustomFont font, int mouseX, int mouseY) {
        int fx = fieldX();
        int fy = fieldY();
        int fw = fieldWidth();
        CustomSearchField.draw(font, fx, fy, fw, FIELD_H, nameInput, fieldFocused,
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
        int trackX = x + width - 2; // clears the action row, which ends PAD inside the frame
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
        font.drawString(font.trimToWidth(status, width - PAD * 2, "..."), x + PAD, ty, statusColor);
    }

    private void renderRow(CustomFont font, String name, int rowY, int mouseX, int mouseY) {
        // Lift only the name line: it loads the profile, while the buttons below hover for themselves.
        if (RenderUtil.hovered(mouseX, mouseY, x, rowY, width, ROW_H)) {
            RenderUtil.rect(x, rowY, width, ROW_H, Theme.HOVER_LIFT);
        }

        String label = font.trimToWidth(name, width - PAD * 2, "...");
        float textY = rowY + (ROW_H - font.getHeight()) / 2f;
        font.drawString(label, x + PAD, textY, Theme.TEXT);

        int by = btnLineY(rowY);
        boolean armed = name.equals(pendingDelete);
        drawActionButton(font, "Update", updateX(), by, btnW(font, "Update"),
                Theme.TEXT_DIM, Theme.WELL, mouseX, mouseY);
        drawActionButton(font, "Rename", renameX(font), by, btnW(font, "Rename"),
                Theme.TEXT_DIM, Theme.WELL, mouseX, mouseY);
        drawActionButton(font, armed ? "Confirm?" : "Delete", deleteX(font), by, deleteBtnW(font),
                armed ? Theme.TEXT : Theme.DANGER,
                armed ? Theme.DANGER : Theme.WELL, mouseX, mouseY);
    }

    private void drawActionButton(CustomFont font, String label, int bx, int by, int bw,
                                  int textColor, int bg, int mouseX, int mouseY) {
        StyledButton.draw(font, label, bx, by, bw, BTN_H, mouseX, mouseY, true, bg, textColor);
    }

    /** Consumes every click within the window, including inactive areas. */
    public boolean mouseClicked(int mouseX, int mouseY, int button) {
        if (!contains(mouseX, mouseY)) {
            return false;
        }
        if (button != 0) {
            return true;
        }
        String armed = pendingDelete;
        pendingDelete = null; // Only a second Delete click on the same row confirms.
        if (ClickGuiScreen.hitsClose(x, y, width, HEADER, mouseX, mouseY)) {
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
        CustomFont font = Fonts.medium;
        if (font == null) {
            return true; // nothing was rendered, so nothing is clickable
        }
        int visible = Math.min(names.size(), MAX_VISIBLE_ROWS);
        int top = rowsTop();
        for (int i = 0; i < visible; i++) {
            int rowY = top + i * PROFILE_ROW_H;
            if (!RenderUtil.hovered(mouseX, mouseY, x, rowY, width, PROFILE_ROW_H)) {
                continue;
            }
            String name = names.get(scrollOffset + i);
            if (mouseY < btnLineY(rowY)) {
                load(name);
                return true;
            }
            int by = btnLineY(rowY);
            if (RenderUtil.hovered(mouseX, mouseY, updateX(), by, btnW(font, "Update"), BTN_H)) {
                update(name);
            } else if (RenderUtil.hovered(mouseX, mouseY, renameX(font), by, btnW(font, "Rename"), BTN_H)) {
                rename(name);
            } else if (RenderUtil.hovered(mouseX, mouseY, deleteX(font), by, deleteBtnW(font), BTN_H)) {
                if (name.equals(armed)) {
                    delete(name);
                } else {
                    pendingDelete = name;
                }
            }
            // empty space on the button line is inert: a mis-click near Delete must not load a profile
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
        pendingDelete = null; // an armed Confirm? must not scroll under the cursor onto another row
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
        x = MathHelper.clamp_int(x, 0, Math.max(0, screenWidth - width));
        y = MathHelper.clamp_int(y, 0, Math.max(0, screenHeight - height()));
    }

    private void refresh() {
        names = ColdPlay.getInstance().getConfigManager().listConfigs();
        int maxOffset = Math.max(0, names.size() - MAX_VISIBLE_ROWS);
        scrollOffset = MathHelper.clamp_int(scrollOffset, 0, maxOffset);
        clampToScreen();
    }
}
