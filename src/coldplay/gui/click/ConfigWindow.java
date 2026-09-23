package coldplay.gui.click;

import coldplay.ColdPlay;
import coldplay.module.ModuleManager;
import coldplay.config.ConfigManager;
import coldplay.gui.CustomTextInput;
import coldplay.gui.GlassShader;
import coldplay.gui.Icons;
import coldplay.util.RenderUtil;
import coldplay.util.font.CustomFont;

import net.minecraft.util.MathHelper;
import org.lwjglx.input.Keyboard;

import java.util.List;

/** Config profile window opened from either Click GUI. */
public class ConfigWindow {
    private static final int WIDTH = 200;
    private static final int HEADER = 24;
    private static final int NAME_ROW_H = 28;
    private static final int FIELD_H = 16;
    private static final int PAD = 6;
    private static final int FIELD_GAP = 6;
    private static final int ICON_W = 16;
    private static final int ICON_H = 14;
    private static final int ICON_GAP = 3;
    private static final int PROFILE_ROW_H = 22;
    private static final int MAX_VISIBLE_ROWS = 7; // fits a 240px-high screen
    private static final int MAX_NAME_LEN = 24;
    private static final int STATUS_H = 12;
    private static final long STATUS_HOLD_MS = 2500L;
    private static final int DANGER = 0xFFE05A5A;
    private static final int CLOSE = 18;

    private final Runnable onClose;
    private final Skin skin;
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
    private boolean statusError;
    private long statusExpiresAt; // wall-clock millis

    ConfigWindow(Runnable onClose, int screenWidth, int screenHeight, Skin skin) {
        this.onClose = onClose;
        this.skin = skin;
        this.screenWidth = screenWidth;
        this.screenHeight = screenHeight;
        this.createW = skin.label.get().getStringWidth("Create") + 14;
        refresh();
        // centered once; refresh() must not move the window
        x = (screenWidth - WIDTH) / 2;
        y = (screenHeight - height()) / 2;
        clampToScreen();
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

    private float closeX() {
        return x + WIDTH - 4.5F - CLOSE;
    }

    private float closeY() {
        return y + (HEADER - CLOSE) / 2.0F;
    }

    public void render(int mouseX, int mouseY) {
        CustomFont font = skin.label.get();
        cursorCounter++;
        int h = height();

        GlassShader.panel(x, y, WIDTH, h, skin.radius, skin.glass);
        CustomFont title = skin.heading.get();
        title.drawString("Config", x + 9, y + (HEADER - title.getHeight()) / 2.0F, skin.strong);
        boolean closeHover = RenderUtil.hovered(mouseX, mouseY, closeX(), closeY(), CLOSE, CLOSE);
        if (closeHover) {
            GlassShader.rect(closeX(), closeY(), CLOSE, CLOSE, 3.75F, skin.hover, skin.hover);
        }
        Icons.draw(Icons.Icon.CLOSE, closeX() + 4.5F, closeY() + 4.5F, 9.0F, 2.8F, closeHover ? skin.strong : skin.mute);
        GlassShader.rect(x, y + HEADER - 0.75F, WIDTH, 0.75F, 0.0F, skin.line, skin.line);

        int fy = fieldY();
        Widgets.field(font, fieldX(), fy, fieldWidth(), FIELD_H, nameInput, fieldFocused, "config name",
                cursorCounter, skin);
        boolean createHover = RenderUtil.hovered(mouseX, mouseY, createX(), fy, createW, FIELD_H);
        int createFill = createHover ? (skin.accent & 0x00FFFFFF) | 0xD9000000 : skin.accent;
        GlassShader.rect(createX(), fy, createW, FIELD_H, FIELD_H / 2.0F, createFill, createFill);
        font.drawCenteredInRect("Create", createX(), fy, createW, FIELD_H, skin.onAccent);

        int visible = Math.min(names.size(), MAX_VISIBLE_ROWS);
        int top = rowsTop();
        for (int i = 0; i < visible; i++) {
            renderRow(font, names.get(scrollOffset + i), top + i * PROFILE_ROW_H, mouseX, mouseY);
        }
        if (names.isEmpty()) {
            font.drawString("No profiles yet", x + PAD + 3, top + (PROFILE_ROW_H - font.getHeight()) / 2f, skin.mute);
        }
        renderScrollIndicator(visible);
        renderStatus(font, h);
    }

    /** Indicator only; scrolling is by wheel. */
    private void renderScrollIndicator(int visible) {
        int maxOffset = names.size() - MAX_VISIBLE_ROWS;
        if (maxOffset <= 0) {
            return;
        }
        int trackH = visible * PROFILE_ROW_H;
        int thumbH = RenderUtil.scrollThumbHeight(trackH, visible, names.size());
        int thumbY = rowsTop() + RenderUtil.scrollThumbOffset(trackH, thumbH, scrollOffset, maxOffset);
        GlassShader.rect(x + WIDTH - 3, thumbY, 2, thumbH, 1.0F, skin.mute, skin.mute);
    }

    private void renderStatus(CustomFont font, int h) {
        if (status.isEmpty() || System.currentTimeMillis() >= statusExpiresAt) {
            return;
        }
        float ty = y + h - PAD - STATUS_H + (STATUS_H - font.getHeight()) / 2f;
        font.drawString(font.trimToWidth(status, WIDTH - PAD * 2, "..."), x + PAD + 3, ty, statusError ? DANGER : skin.text);
    }

    private void renderRow(CustomFont font, String name, int rowY, int mouseX, int mouseY) {
        int nameW = updateIconX() - ICON_GAP - (x + PAD);
        if (RenderUtil.hovered(mouseX, mouseY, x + PAD, rowY, nameW, PROFILE_ROW_H)) {
            GlassShader.rect(x + PAD - 1, rowY + 2, nameW, PROFILE_ROW_H - 4, 4.5F, skin.hover, skin.hover);
        }
        font.drawString(font.trimToWidth(name, nameW - 6, "..."), x + PAD + 3,
                rowY + (PROFILE_ROW_H - font.getHeight()) / 2f, skin.text);

        int iy = iconY(rowY);
        boolean armed = name.equals(pendingDelete);
        iconButton(updateIconX(), iy, skin.field, mouseX, mouseY);
        glyphUpdate(updateIconX() + 2, iy + 1, skin.dim);
        iconButton(renameIconX(), iy, skin.field, mouseX, mouseY);
        glyphRename(renameIconX() + 2, iy + 1, skin.dim);
        iconButton(deleteIconX(), iy, armed ? DANGER : skin.field, mouseX, mouseY);
        glyphDelete(deleteIconX() + 2, iy + 1, armed ? 0xFFFFFFFF : DANGER);
    }

    private void iconButton(int bx, int by, int fill, int mouseX, int mouseY) {
        GlassShader.rect(bx, by, ICON_W, ICON_H, 3.75F, fill, fill);
        GlassShader.stroke(bx, by, ICON_W, ICON_H, 3.75F,
                RenderUtil.hovered(mouseX, mouseY, bx, by, ICON_W, ICON_H) ? skin.dim : skin.fieldLine);
    }

    // 12x12 pixel glyphs drawn as strokes.
    private static void glyphUpdate(int gx, int gy, int fg) {
        GlassShader.stroke(gx + 1.5F, gy + 1.5F, 9, 9, 1.5F, fg); // floppy body
        RenderUtil.rect(gx + 4, gy + 2, 4, 3, fg);                 // shutter
    }

    private static void glyphRename(int gx, int gy, int fg) {
        RenderUtil.rect(gx + 7, gy + 1, 4, 2, fg);   // pencil, stepped down the diagonal
        RenderUtil.rect(gx + 5, gy + 3, 4, 2, fg);
        RenderUtil.rect(gx + 3, gy + 5, 4, 2, fg);
        RenderUtil.rect(gx + 1, gy + 7, 4, 2, fg);
        RenderUtil.rect(gx + 1, gy + 9, 2, 2, fg);   // tip
    }

    private static void glyphDelete(int gx, int gy, int fg) {
        RenderUtil.rect(gx + 4, gy, 4, 1, fg);       // handle
        RenderUtil.rect(gx + 2, gy + 1, 8, 2, fg);   // lid
        GlassShader.stroke(gx + 2.5F, gy + 3.5F, 7, 7.5F, 1.0F, fg); // body
    }

    /** Consumes every click inside the window. */
    public boolean mouseClicked(int mouseX, int mouseY, int button) {
        if (!contains(mouseX, mouseY)) {
            return false;
        }
        if (button != 0) {
            return true;
        }
        String armed = pendingDelete;
        pendingDelete = null; // any other click disarms the delete
        if (RenderUtil.hovered(mouseX, mouseY, closeX(), closeY(), CLOSE, CLOSE)) {
            onClose.run();
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
        CustomTextInput.EditResult edit = CustomTextInput.edit(nameInput, typedChar, keyCode, MAX_NAME_LEN);
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

    private void setStatus(String message, boolean error) {
        status = message;
        statusError = error;
        statusExpiresAt = System.currentTimeMillis() + STATUS_HOLD_MS;
    }

    private void create() {
        String n = ConfigManager.sanitizeName(nameInput);
        if (n.isEmpty()) {
            setStatus("Enter a name", true);
            return;
        }
        if (containsIgnoreCase(n)) {
            setStatus("Name in use", true);
            return;
        }
        if (!ColdPlay.getInstance().getConfigManager().saveProfile(n, moduleManager())) {
            setStatus("Create failed", true);
            return;
        }
        nameInput = "";
        setStatus("Created", false);
        refresh();
    }

    private void update(String name) {
        boolean saved = ColdPlay.getInstance().getConfigManager().saveProfile(name, moduleManager());
        setStatus(saved ? "Saved" : "Save failed", !saved);
    }

    private void rename(String old) {
        String n = ConfigManager.sanitizeName(nameInput);
        if (n.isEmpty()) {
            nameInput = old;
            fieldFocused = true;
            cursorCounter = 0;
            setStatus("Edit the name", false);
            return;
        }
        if (containsIgnoreCase(n)) {
            setStatus("Name in use", true);
            return;
        }
        if (!ColdPlay.getInstance().getConfigManager().renameProfile(old, n)) {
            setStatus("Rename failed", true);
            return;
        }
        nameInput = "";
        setStatus("Renamed", false);
        refresh();
    }

    private void delete(String name) {
        boolean deleted = ColdPlay.getInstance().getConfigManager().deleteProfile(name);
        setStatus(deleted ? "Deleted" : "Delete failed", !deleted);
        refresh();
    }

    private void load(String name) {
        if (!ColdPlay.getInstance().getConfigManager().loadProfile(name, moduleManager())) {
            setStatus("Load failed", true);
            return;
        }
        ColdPlay.getInstance().saveConfig();
        setStatus("Loaded", false);
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
