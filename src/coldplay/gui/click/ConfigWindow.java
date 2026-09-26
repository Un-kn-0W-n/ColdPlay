package coldplay.gui.click;

import coldplay.ColdPlay;
import coldplay.config.ConfigManager;
import coldplay.gui.CustomTextInput;
import coldplay.gui.Glass;
import coldplay.gui.GlassShader;
import coldplay.gui.Icons;
import coldplay.gui.Theme;
import coldplay.module.Category;
import coldplay.module.Module;
import coldplay.module.ModuleManager;
import coldplay.util.RenderUtil;
import coldplay.util.font.CustomFont;
import coldplay.util.font.FontRef;
import coldplay.util.font.Fonts;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.util.ColorMath;
import net.minecraft.util.MathHelper;
import org.lwjglx.input.Keyboard;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Config profiles as a shelf of cards filtered by server, opened from either Click GUI. */
public class ConfigWindow {
    private static final float CARD_W = 182.5F;
    private static final float CARD_H = 96.0F;
    private static final float GAP = 7.5F;
    private static final float PAD = 10.5F;
    private static final float HEADER = 35.25F; // top rim and the header band with its hairline
    private static final float INSET = 9.75F;   // card rim and padding
    private static final float CLOSE = 21.0F;
    private static final float ACTION_H = 16.5F;
    private static final int MAX_NAME_LEN = 24;
    private static final long STATUS_HOLD_MS = 2500L;
    private static final int DANGER = 0xFFE05A5A;
    private static final int DANGER_TEXT = 0xFFEE8A8E;
    private static final int SCRIM = 0x3B000000;
    private static final Glass SMOKE_GLASS = new Glass(0x9E0E1015, 0x9E0E1015, 0x2EFFFFFF, 12.0F, 1.4F, 36.0F, 13.5F, 0.36F);
    private static final String[] CATEGORY_LETTERS = {"C", "M", "V", "U"};
    private static final Faces SMOKE_FACES = new Faces(Fonts.GEIST_SEMIBOLD, Fonts.GEIST, Fonts.GEIST_MEDIUM);
    private static final Faces MILK_FACES = new Faces(Fonts.JAKARTA_BOLD, Fonts.JAKARTA_MEDIUM, Fonts.JAKARTA_SEMIBOLD);

    private static final class Faces {
        final FontRef title;
        final FontRef tab;
        final FontRef button;
        final FontRef badge;
        final FontRef create;
        final FontRef monoSmall = new FontRef(Fonts.GEIST_MONO, 7.125F);
        final FontRef mono = new FontRef(Fonts.GEIST_MONO, 7.5F);
        final FontRef monoLarge = new FontRef(Fonts.GEIST_MONO, 7.875F);

        Faces(String bold, String regular, String medium) {
            title = new FontRef(bold, 10.125F);
            tab = new FontRef(regular, 8.625F);
            button = new FontRef(medium, 8.625F);
            badge = new FontRef(medium, 7.875F);
            create = new FontRef(regular, 9.375F);
        }
    }

    /** What a card shows about one saved profile. */
    static final class Card {
        final String name;
        final String meta;
        final int[] counts; // enabled modules per category

        Card(String name, String meta, int[] counts) {
            this.name = name;
            this.meta = meta;
            this.counts = counts;
        }
    }

    private final Runnable onClose;
    private final Skin skin;
    private final Faces faces;
    private final int screenWidth;
    private final int screenHeight;

    private final List<Card> cards = new ArrayList<Card>();
    private final List<String> servers = new ArrayList<String>(); // "All", then each name's first word
    private final int[] totals = new int[Category.values().length];
    private String tab = "All";
    private final int columns;
    private int rows;
    private int scrollRow;
    private int modulesOn;

    private float x;
    private float y;
    private float width;
    private float height;

    private boolean creating;
    private String renaming; // the profile whose name is being edited, null if none
    private String input = "";
    private int cursorCounter;
    private String pendingDelete; // null unless a delete awaits confirmation
    private String status = "";
    private boolean statusError;
    private long statusExpiresAt; // wall-clock millis

    ConfigWindow(Runnable onClose, int screenWidth, int screenHeight, Skin skin) {
        this.onClose = onClose;
        this.skin = skin;
        this.faces = skin.milk ? MILK_FACES : SMOKE_FACES;
        this.screenWidth = screenWidth;
        this.screenHeight = screenHeight;
        this.columns = MathHelper.clamp_int((int) ((screenWidth - 39) / (CARD_W + GAP)), 1, 3);
        refresh();
    }

    /** Width the hover action row needs for these label widths; it must fit the card at every GUI scale. */
    static float actionRowWidth(float loadText, float saveText) {
        return 18.0F + loadText + 3.75F + 15.0F + saveText + 3.75F + ACTION_H * 2 + 3.75F;
    }

    static float cardInnerWidth() {
        return CARD_W - INSET * 2;
    }

    public boolean contains(int mouseX, int mouseY) {
        return RenderUtil.hovered(mouseX, mouseY, x, y, width, height);
    }

    private List<Card> shown() {
        if ("All".equals(tab)) {
            return cards;
        }
        List<Card> list = new ArrayList<Card>();
        for (Card card : cards) {
            if (server(card.name).equals(tab)) {
                list.add(card);
            }
        }
        return list;
    }

    private static String server(String name) {
        int space = name.indexOf(' ');
        return space > 0 ? name.substring(0, space) : name;
    }

    private int neededRows() {
        return (shown().size() + columns) / columns; // the New card takes a slot too
    }

    /** Top left of the grid slot {@code index}, or null when it is scrolled out. */
    private float[] slot(int index) {
        int row = index / columns - scrollRow;
        if (row < 0 || row >= rows) {
            return null;
        }
        return new float[]{x + 0.75F + PAD + index % columns * (CARD_W + GAP), y + HEADER + PAD + row * (CARD_H + GAP)};
    }

    private float closeX() {
        return x + width - 0.75F - 6.0F - CLOSE;
    }

    private float closeY() {
        return y + 7.125F;
    }

    public void render(int mouseX, int mouseY) {
        cursorCounter++;
        RenderUtil.rectBounds(0, 0, screenWidth, screenHeight, SCRIM);
        GlassShader.panel(x, y, width, height, skin.milk ? skin.radius : 9.0F, skin.milk ? skin.glass : SMOKE_GLASS);
        renderHeader(mouseX, mouseY);

        List<Card> list = shown();
        String loaded = ColdPlay.getInstance().getConfigManager().getProfile();
        for (int i = 0; i < list.size(); i++) {
            float[] at = slot(i);
            if (at != null) {
                Card card = list.get(i);
                boolean hot = RenderUtil.hovered(mouseX, mouseY, at[0], at[1], CARD_W, CARD_H);
                renderCard(card, at[0], at[1], hot, card.name.equalsIgnoreCase(loaded), mouseX, mouseY);
            }
        }
        float[] at = slot(list.size());
        if (at != null) {
            renderNewCard(at[0], at[1], RenderUtil.hovered(mouseX, mouseY, at[0], at[1], CARD_W, CARD_H));
        }
        int maxScroll = neededRows() - rows;
        if (maxScroll > 0) {
            float track = rows * (CARD_H + GAP) - GAP;
            float thumb = Math.max(14.0F, track * rows / neededRows());
            float ty = y + HEADER + PAD + (track - thumb) * scrollRow / maxScroll;
            GlassShader.rect(x + width - 5.25F, ty, 2.25F, thumb, 1.125F, ink(0x40), ink(0x40));
        }
    }

    private void renderHeader(int mouseX, int mouseY) {
        float mid = y + 17.625F;
        CustomFont title = faces.title.get();
        title.drawString("Configs", x + 12.75F, mid - title.getHeight() / 2.0F, text());
        float groupX = x + 12.75F + title.getStringWidth("Configs") + 10.5F;
        float limit = closeX() - 10.5F - statusWidth();
        CustomFont font = faces.tab.get();
        CustomFont mono = faces.mono.get();
        float tx = groupX + 1.5F;
        int fitting = 0;
        for (String server : servers) {
            if (tx + tabWidth(server) + 1.5F > limit) {
                break;
            }
            tx += tabWidth(server) + 1.5F;
            fitting++;
        }
        if (fitting > 0) {
            GlassShader.rect(groupX, y + 7.125F, tx - groupX, 21.0F, 5.25F, ink(0x0F), ink(0x0F));
        }
        tx = groupX + 1.5F;
        for (int i = 0; i < fitting; i++) {
            String server = servers.get(i);
            float w = tabWidth(server);
            boolean on = server.equals(tab);
            if (on) {
                int fill = Theme.withAlpha(skin.accent, 0x33);
                GlassShader.rect(tx, y + 8.625F, w, 18.0F, 3.75F, fill, fill);
            } else if (RenderUtil.hovered(mouseX, mouseY, tx, y + 8.625F, w, 18.0F)) {
                GlassShader.rect(tx, y + 8.625F, w, 18.0F, 3.75F, ink(0x0D), ink(0x0D));
            }
            font.drawString(server, tx + 7.5F, mid - font.getHeight() / 2.0F, on ? skin.accent : ink(0xA6));
            mono.drawString(String.valueOf(count(server)), tx + 7.5F + font.getStringWidth(server) + 4.5F,
                    mid - mono.getHeight() / 2.0F, on ? Theme.withAlpha(skin.accent, 0xBF) : ink(0x66));
            tx += w + 1.5F;
        }
        if (!status.isEmpty() && System.currentTimeMillis() < statusExpiresAt) {
            CustomFont small = faces.monoLarge.get();
            small.drawString(status, closeX() - 7.5F - small.getStringWidth(status), mid - small.getHeight() / 2.0F,
                    statusError ? DANGER_TEXT : ink(0x8C));
        }
        boolean closeHover = RenderUtil.hovered(mouseX, mouseY, closeX(), closeY(), CLOSE, CLOSE);
        if (closeHover) {
            GlassShader.rect(closeX(), closeY(), CLOSE, CLOSE, 4.5F, ink(0x14), ink(0x14));
        }
        Icons.draw(Icons.Icon.CLOSE, closeX() + 6.0F, closeY() + 6.0F, 9.0F, 3.0F, closeHover ? text() : ink(0x8C));
        GlassShader.rect(x + 0.75F, y + 34.5F, width - 1.5F, 0.75F, 0.0F, ink(0x14), ink(0x14));
    }

    private float statusWidth() {
        if (status.isEmpty() || System.currentTimeMillis() >= statusExpiresAt) {
            return 0.0F;
        }
        return faces.monoLarge.get().getStringWidth(status) + 7.5F;
    }

    private float tabWidth(String server) {
        return 15.0F + faces.tab.get().getStringWidth(server) + 4.5F
                + faces.mono.get().getStringWidth(String.valueOf(count(server)));
    }

    private int count(String server) {
        if ("All".equals(server)) {
            return cards.size();
        }
        int n = 0;
        for (Card card : cards) {
            if (server(card.name).equals(server)) {
                n++;
            }
        }
        return n;
    }

    private void renderCard(Card card, float cx, float cy, boolean hot, boolean loaded, int mouseX, int mouseY) {
        float r = 6.75F;
        if (loaded) {
            GlassShader.arc(cx - 1.125F, cy - 1.125F, CARD_W + 2.25F, CARD_H + 2.25F, r + 1.125F, 2.25F, 0.0F, 1.0F,
                    Theme.withAlpha(skin.accent, 0x1F));
        }
        int fill = hot ? ink(0x12) : ink(0x09);
        GlassShader.rect(cx, cy, CARD_W, CARD_H, r, fill, fill);
        GlassShader.stroke(cx, cy, CARD_W, CARD_H, r, loaded ? Theme.withAlpha(skin.accent, 0xB3) : hot ? ink(0x2E) : ink(0x14));

        float ix = cx + INSET;
        float inner = cardInnerWidth();
        CustomFont title = faces.title.get();
        boolean editing = card.name.equals(renaming);
        float nameW = inner;
        if (loaded) {
            CustomFont badge = faces.badge.get();
            float bw = 10.5F + badge.getStringWidth("Loaded");
            float bx = ix + inner - bw;
            int badgeFill = Theme.withAlpha(skin.accent, 0x29);
            GlassShader.rect(bx, cy + 9.75F, bw, 13.5F, 6.75F, badgeFill, badgeFill);
            badge.drawString("Loaded", bx + 5.25F, cy + 9.75F + (13.5F - badge.getHeight()) / 2.0F, skin.accent);
            nameW = inner - bw - 6.0F;
        }
        if (editing) {
            field(ix - 3.0F, cy + 8.25F, nameW + 3.0F, 16.5F, title);
        } else {
            title.drawString(title.trimToWidth(card.name, Math.round(nameW), "..."), ix,
                    cy + 9.75F + (13.5F - title.getHeight()) / 2.0F, text(), -0.1F);
        }

        CustomFont mono = faces.monoLarge.get();
        String meta = editing ? "Enter to rename, Esc to cancel" : card.meta;
        mono.drawString(mono.trimToWidth(meta, Math.round(inner), ""), ix, cy + 30.0F + (10.5F - mono.getHeight()) / 2.0F,
                editing ? skin.accent : ink(0x73));

        CustomFont label = faces.monoSmall.get();
        float colW = (inner - 3 * 4.5F) / 4.0F;
        for (int i = 0; i < card.counts.length; i++) {
            float bx = ix + i * (colW + 4.5F);
            GlassShader.rect(bx, cy + 47.25F, colW, 2.25F, 1.125F, ink(0x1A), ink(0x1A));
            if (card.counts[i] > 0 && totals[i] > 0) {
                float w = colW * Math.min(1.0F, card.counts[i] / (float) totals[i]);
                GlassShader.rect(bx, cy + 47.25F, w, 2.25F, 1.125F, skin.accent, skin.accent);
            }
            label.drawString(CATEGORY_LETTERS[i] + " " + card.counts[i], bx,
                    cy + 52.5F + (9.75F - label.getHeight()) / 2.0F, ink(0x80));
        }

        if (hot || card.name.equals(pendingDelete)) {
            renderActions(card.name, ix, cy + 69.0F, loaded, mouseX, mouseY);
        }
    }

    private void renderActions(String name, float ax, float ay, boolean loaded, int mouseX, int mouseY) {
        CustomFont font = faces.button.get();
        CustomFont plain = faces.tab.get();
        String load = loaded ? "Reload" : "Load";
        float lw = 18.0F + font.getStringWidth(load);
        int loadFill = RenderUtil.hovered(mouseX, mouseY, ax, ay, lw, ACTION_H)
                ? ColorMath.lerpArgb(skin.accent, 0xFFFFFFFF, 0.2F) : skin.accent;
        GlassShader.rect(ax, ay, lw, ACTION_H, 3.75F, loadFill, loadFill);
        font.drawString(load, ax + 9.0F, ay + (ACTION_H - font.getHeight()) / 2.0F, skin.onAccent);

        float sx = ax + lw + 3.75F;
        float sw = 15.0F + plain.getStringWidth("Save over");
        if (RenderUtil.hovered(mouseX, mouseY, sx, ay, sw, ACTION_H)) {
            GlassShader.rect(sx, ay, sw, ACTION_H, 3.75F, ink(0x0F), ink(0x0F));
        }
        GlassShader.stroke(sx, ay, sw, ACTION_H, 3.75F, ink(0x29));
        plain.drawString("Save over", sx + 7.5F, ay + (ACTION_H - plain.getHeight()) / 2.0F, ink(0xCC));

        float dx = ax + cardInnerWidth() - ACTION_H;
        float rx = dx - 3.75F - ACTION_H;
        boolean renameHover = RenderUtil.hovered(mouseX, mouseY, rx, ay, ACTION_H, ACTION_H);
        GlassShader.rect(rx, ay, ACTION_H, ACTION_H, 3.75F, ink(renameHover ? 0x1A : 0x0F), ink(renameHover ? 0x1A : 0x0F));
        Icons.draw(Icons.Icon.PENCIL, rx + 4.125F, ay + 4.125F, 8.25F, 2.2F, ink(renameHover ? 0xFF : 0xB3));

        boolean armed = name.equals(pendingDelete);
        boolean deleteHover = RenderUtil.hovered(mouseX, mouseY, dx, ay, ACTION_H, ACTION_H);
        int deleteFill = armed ? DANGER : deleteHover ? 0x40E05A5A : 0x24E05A5A;
        GlassShader.rect(dx, ay, ACTION_H, ACTION_H, 3.75F, deleteFill, deleteFill);
        Icons.draw(Icons.Icon.TRASH, dx + 4.125F, ay + 4.125F, 8.25F, 2.2F, armed ? 0xFFFFFFFF : 0xFFF07A7A);
    }

    private void renderNewCard(float cx, float cy, boolean hot) {
        if (hot || creating) {
            GlassShader.rect(cx, cy, CARD_W, CARD_H, 6.75F, ink(0x09), ink(0x09));
        }
        dashedOutline(cx, cy, CARD_W, CARD_H, 6.75F, ink(0x38));
        float mid = cx + CARD_W / 2.0F;
        int plusFill = Theme.withAlpha(skin.accent, 0x1F);
        GlassShader.rect(mid - 11.25F, cy + 19.125F, 22.5F, 22.5F, 6.0F, plusFill, plusFill);
        Icons.draw(Icons.Icon.PLUS, mid - 4.5F, cy + 25.875F, 9.0F, 3.0F, skin.accent);
        CustomFont mono = faces.monoLarge.get();
        if (creating) {
            field(cx + INSET, cy + 45.75F, cardInnerWidth(), 16.5F, faces.create.get());
            String hint = "Enter to save, Esc to cancel";
            mono.drawString(hint, mid - mono.getStringWidth(hint) / 2.0F, cy + 66.375F + (10.5F - mono.getHeight()) / 2.0F,
                    skin.accent);
            return;
        }
        CustomFont font = faces.create.get();
        font.drawString("New from current", mid - font.getStringWidth("New from current") / 2.0F,
                cy + 47.625F + (12.75F - font.getHeight()) / 2.0F, ink(0xDB));
        String sub = modulesOn + " modules on";
        mono.drawString(sub, mid - mono.getStringWidth(sub) / 2.0F, cy + 66.375F + (10.5F - mono.getHeight()) / 2.0F,
                ink(0x73));
    }

    /** The name being typed, tail first so the caret stays in view. */
    private void field(float fx, float fy, float fw, float fh, CustomFont font) {
        GlassShader.rect(fx, fy, fw, fh, 3.75F, ink(0x0F), ink(0x0F));
        GlassShader.stroke(fx, fy, fw, fh, 3.75F, Theme.withAlpha(skin.accent, 0x8C));
        String shown = input;
        while (!shown.isEmpty() && font.getStringWidth(shown) > fw - 9.0F) {
            shown = shown.substring(1);
        }
        float ty = fy + (fh - font.getHeight()) / 2.0F;
        if (shown.isEmpty()) {
            font.drawString("Name", fx + 4.5F, ty, ink(0x66));
        } else {
            font.drawString(shown, fx + 4.5F, ty, text());
        }
        if (cursorCounter / 6 % 2 == 0) {
            float caret = fx + 4.5F + (shown.isEmpty() ? 0.0F : font.getStringWidth(shown) + 0.75F);
            GlassShader.rect(caret, ty, 0.75F, font.getHeight(), 0.0F, skin.accent, skin.accent);
        }
    }

    /** Dashes along a rounded outline, one device pixel wide like a CSS dashed border. */
    private static void dashedOutline(float dx, float dy, float w, float h, float r, int color) {
        float px = 1.0F / new ScaledResolution(Minecraft.getMinecraft()).getScaleFactor();
        float ox = dx + px / 2.0F, oy = dy + px / 2.0F, ow = w - px, oh = h - px, or = r - px / 2.0F;
        float length = 2.0F * (ow + oh) - (8.0F - 2.0F * (float) Math.PI) * or;
        int dashes = Math.round(length / 2.5F);
        float period = length / dashes;
        float dash = 1.5F - px;
        for (int i = 0; i < dashes; i++) {
            float from = i * period / length;
            GlassShader.arc(ox, oy, ow, oh, or, px, from, from + dash / length, color);
        }
    }

    private int text() {
        return skin.milk ? skin.strong : 0xFFF4F6F8;
    }

    /** White on Smoke, dark ink on Milk, at {@code alpha}. */
    private int ink(int alpha) {
        return alpha << 24 | (skin.milk ? 0x101520 : 0xFFFFFF);
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
        if (clickTab(mouseX, mouseY)) {
            return true;
        }
        List<Card> list = shown();
        for (int i = 0; i < list.size(); i++) {
            float[] at = slot(i);
            if (at != null && RenderUtil.hovered(mouseX, mouseY, at[0], at[1], CARD_W, CARD_H)) {
                if (!list.get(i).name.equals(renaming)) {
                    stopEditing();
                }
                clickCard(list.get(i), at[0] + INSET, at[1] + 69.0F, armed, mouseX, mouseY);
                return true;
            }
        }
        float[] at = slot(list.size());
        if (at != null && RenderUtil.hovered(mouseX, mouseY, at[0], at[1], CARD_W, CARD_H)) {
            if (!creating) {
                stopEditing();
                creating = true;
                cursorCounter = 0;
            }
            return true;
        }
        stopEditing();
        return true;
    }

    private boolean clickTab(int mouseX, int mouseY) {
        CustomFont title = faces.title.get();
        float tx = x + 12.75F + title.getStringWidth("Configs") + 10.5F + 1.5F;
        float limit = closeX() - 10.5F - statusWidth();
        for (String server : servers) {
            float w = tabWidth(server);
            if (tx + w + 1.5F > limit) {
                return false;
            }
            if (RenderUtil.hovered(mouseX, mouseY, tx, y + 8.625F, w, 18.0F)) {
                stopEditing();
                tab = server;
                scrollRow = 0;
                return true;
            }
            tx += w + 1.5F;
        }
        return false;
    }

    private void clickCard(Card card, float ax, float ay, String armed, int mouseX, int mouseY) {
        if (!RenderUtil.hovered(mouseX, mouseY, ax, ay, cardInnerWidth(), ACTION_H)) {
            return;
        }
        boolean loaded = card.name.equalsIgnoreCase(ColdPlay.getInstance().getConfigManager().getProfile());
        float lw = 18.0F + faces.button.get().getStringWidth(loaded ? "Reload" : "Load");
        float sx = ax + lw + 3.75F;
        float sw = 15.0F + faces.tab.get().getStringWidth("Save over");
        float dx = ax + cardInnerWidth() - ACTION_H;
        float rx = dx - 3.75F - ACTION_H;
        if (RenderUtil.hovered(mouseX, mouseY, ax, ay, lw, ACTION_H)) {
            load(card.name);
        } else if (RenderUtil.hovered(mouseX, mouseY, sx, ay, sw, ACTION_H)) {
            saveOver(card.name);
        } else if (RenderUtil.hovered(mouseX, mouseY, rx, ay, ACTION_H, ACTION_H)) {
            renaming = card.name;
            input = card.name;
            cursorCounter = 0;
        } else if (RenderUtil.hovered(mouseX, mouseY, dx, ay, ACTION_H, ACTION_H)) {
            if (card.name.equals(armed)) {
                delete(card.name);
            } else {
                pendingDelete = card.name;
            }
        }
    }

    public boolean isFieldFocused() {
        return creating || renaming != null;
    }

    public void charTyped(char typedChar, int keyCode) {
        if (keyCode == Keyboard.KEY_RETURN) {
            if (creating) {
                create();
            } else {
                rename();
            }
            return;
        }
        CustomTextInput.EditResult edit = CustomTextInput.edit(input, typedChar, keyCode, MAX_NAME_LEN);
        input = edit.getValue();
        if (!edit.isFocused()) {
            stopEditing();
        }
    }

    private void stopEditing() {
        creating = false;
        renaming = null;
        input = "";
    }

    public void scroll(int dWheel, int mouseX, int mouseY) {
        if (dWheel == 0 || !contains(mouseX, mouseY)) {
            return;
        }
        pendingDelete = null;
        scrollRow = MathHelper.clamp_int(scrollRow + (dWheel > 0 ? -1 : 1), 0, Math.max(0, neededRows() - rows));
    }

    private void setStatus(String message, boolean error) {
        status = message;
        statusError = error;
        statusExpiresAt = System.currentTimeMillis() + STATUS_HOLD_MS;
    }

    private void create() {
        String n = ConfigManager.sanitizeName(input);
        if (n.isEmpty()) {
            setStatus("Enter a name", true);
            return;
        }
        if (find(n) != null) {
            setStatus("Name in use", true);
            return;
        }
        if (!ColdPlay.getInstance().getConfigManager().saveProfile(n, moduleManager())) {
            setStatus("Create failed", true);
            return;
        }
        ColdPlay.getInstance().saveConfig();
        stopEditing();
        setStatus("Created " + n, false);
        refresh();
    }

    private void rename() {
        String old = renaming;
        String n = ConfigManager.sanitizeName(input);
        if (n.isEmpty()) {
            setStatus("Enter a name", true);
            return;
        }
        if (n.equals(old)) {
            stopEditing();
            return;
        }
        if (find(n) != null) {
            setStatus("Name in use", true);
            return;
        }
        if (!ColdPlay.getInstance().getConfigManager().renameProfile(old, n)) {
            setStatus("Rename failed", true);
            return;
        }
        ColdPlay.getInstance().saveConfig();
        stopEditing();
        setStatus("Renamed", false);
        refresh();
    }

    private void saveOver(String name) {
        if (!ColdPlay.getInstance().getConfigManager().saveProfile(name, moduleManager())) {
            setStatus("Save failed", true);
            return;
        }
        ColdPlay.getInstance().saveConfig();
        setStatus("Saved " + name, false);
        refresh();
    }

    private void delete(String name) {
        if (!ColdPlay.getInstance().getConfigManager().deleteProfile(name)) {
            setStatus("Delete failed", true);
            return;
        }
        ColdPlay.getInstance().saveConfig();
        setStatus("Deleted " + name, false);
        refresh();
    }

    private void load(String name) {
        if (!ColdPlay.getInstance().getConfigManager().loadProfile(name, moduleManager())) {
            setStatus("Load failed", true);
            return;
        }
        ColdPlay.getInstance().saveConfig();
        setStatus("Loaded " + name, false);
        refresh();
    }

    private Card find(String name) {
        for (Card card : cards) {
            if (card.name.equalsIgnoreCase(name)) {
                return card;
            }
        }
        return null;
    }

    private static ModuleManager moduleManager() {
        return ColdPlay.getInstance().getModuleManager();
    }

    private void refresh() {
        ConfigManager config = ColdPlay.getInstance().getConfigManager();
        ModuleManager modules = moduleManager();
        cards.clear();
        for (String name : config.listConfigs()) {
            cards.add(summarize(name, config.readProfile(name), config.profileSavedAt(name), modules,
                    LocalDate.now()));
        }
        servers.clear();
        servers.add("All");
        for (Card card : cards) {
            if (!servers.contains(server(card.name))) {
                servers.add(server(card.name));
            }
        }
        if (!servers.contains(tab)) {
            tab = "All";
        }
        modulesOn = 0;
        for (Category category : Category.values()) {
            totals[category.ordinal()] = modules.getModulesInCategory(category).size();
        }
        for (Module module : modules.getModules()) {
            if (module.isEnabled()) {
                modulesOn++;
            }
        }
        int fit = Math.max(1, (int) ((screenHeight - 72.0F + GAP) / (CARD_H + GAP)));
        rows = Math.max(1, Math.min((cards.size() + columns) / columns, fit));
        scrollRow = MathHelper.clamp_int(scrollRow, 0, Math.max(0, neededRows() - rows));
        width = 15.0F + columns * (CARD_W + GAP);
        height = 48.0F + rows * (CARD_H + GAP);
        x = Math.round((screenWidth - width) / 2.0F);
        y = Math.round((screenHeight - height) / 2.0F);
    }

    /** Enabled modules per category and when the profile was saved. */
    static Card summarize(String name, JsonObject root, long savedAt, ModuleManager modules, LocalDate today) {
        JsonObject saved = root.has("modules") && root.get("modules").isJsonObject()
                ? root.getAsJsonObject("modules") : new JsonObject();
        int on = 0;
        int[] counts = new int[Category.values().length];
        for (Module module : modules.getModules()) {
            JsonElement entry = saved.get(module.getName());
            if (entry == null || !entry.isJsonObject() || !enabled(entry.getAsJsonObject())) {
                continue;
            }
            on++;
            counts[module.getCategory().ordinal()]++;
        }
        return new Card(name, on + " on saved " + when(savedAt, today), counts);
    }

    private static boolean enabled(JsonObject entry) {
        try {
            return entry.get("enabled").getAsBoolean();
        } catch (Exception ignored) {
            return false;
        }
    }

    static String when(long millis, LocalDate today) {
        LocalDateTime time = LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault());
        if (time.toLocalDate().equals(today)) {
            return "today " + time.format(DateTimeFormatter.ofPattern("HH:mm"));
        }
        if (time.toLocalDate().equals(today.minusDays(1))) {
            return "yesterday";
        }
        return time.format(DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH));
    }
}
