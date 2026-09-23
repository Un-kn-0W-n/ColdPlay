package coldplay.gui.click;

import coldplay.gui.GlassShader;
import coldplay.util.RenderUtil;
import coldplay.util.font.CustomFont;

import net.minecraft.util.MathHelper;

import java.util.List;

/** Controls both Click GUI styles draw the same way, in their skin's colors. */
final class Widgets {

    static final int SWITCH_W = 21;
    static final int SWITCH_H = 12;

    private static final int TOOLTIP_PAD = 6;
    private static final int TOOLTIP_MAX_WIDTH = 160;

    private Widgets() {
    }

    /** Pill toggle; the knob keeps an eighth of the height clear all round. */
    static void toggle(float x, float y, float w, float h, boolean on, Skin skin) {
        int track = on ? skin.accent : (skin.milk ? 0x29101520 : 0x33FFFFFF);
        GlassShader.rect(x, y, w, h, h / 2.0F, track, track);
        float inset = h / 8.0F;
        float knob = h - inset * 2.0F;
        float kx = on ? x + w - inset - knob : x + inset;
        GlassShader.fill(kx, y + inset, knob, knob, knob / 2.0F, 0xFFFFFFFF, 1.5F, 0.5F, 0.25F);
    }

    static void field(CustomFont font, float x, float y, float w, float h, String value, boolean focused,
                      String placeholder, int cursorCounter, Skin skin) {
        float r = skin.milk ? 8.25F : 4.5F;
        GlassShader.rect(x, y, w, h, r, skin.field, skin.field);
        GlassShader.stroke(x, y, w, h, r, focused ? skin.accent : skin.fieldLine);
        text(font, x + 6.0F, y, h, value, focused, placeholder, cursorCounter, skin);
    }

    /** Field text with its caret; the shown part is the tail that fits, so the caret stays visible. */
    static void text(CustomFont font, float x, float y, float h, String value, boolean focused,
                     String placeholder, int cursorCounter, Skin skin) {
        float ty = y + (h - font.getHeight()) / 2.0F;
        if (value.isEmpty() && !focused) {
            font.drawString(placeholder, x, ty, skin.mute);
            return;
        }
        font.drawString(value, x, ty, skin.text);
        if (focused && cursorCounter / 6 % 2 == 0) {
            GlassShader.rect(x + font.getStringWidth(value) + 0.5F, ty, 1.0F, font.getHeight(), 0.0F,
                    skin.accent, skin.accent);
        }
    }

    static void tooltip(Skin skin, String text, int mouseX, int mouseY, int screenWidth, int screenHeight) {
        CustomFont font = skin.label.get();
        List<String> lines = font.wrapToWidth(text, TOOLTIP_MAX_WIDTH);
        int lineH = font.getHeight();
        int contentW = 0;
        for (String line : lines) {
            contentW = Math.max(contentW, font.getStringWidth(line));
        }
        int boxW = contentW + TOOLTIP_PAD * 2;
        int boxH = lines.size() * lineH + (lines.size() - 1) + TOOLTIP_PAD * 2;

        int left = mouseX + 10;
        int top = mouseY + 10;
        if (left + boxW > screenWidth) {
            left = mouseX - 10 - boxW;
        }
        if (top + boxH > screenHeight) {
            top = mouseY - 10 - boxH;
        }
        left = MathHelper.clamp_int(left, 2, Math.max(2, screenWidth - boxW - 2));
        top = MathHelper.clamp_int(top, 2, Math.max(2, screenHeight - boxH - 2));

        GlassShader.panel(left, top, boxW, boxH, skin.radius, skin.glass);
        int textY = top + TOOLTIP_PAD;
        for (String line : lines) {
            font.drawString(line, left + TOOLTIP_PAD, textY, skin.text);
            textY += lineH + 1;
        }
    }

    static boolean hovered(int mouseX, int mouseY, float x, float y, float w, float h) {
        return RenderUtil.hovered(mouseX, mouseY, x, y, w, h);
    }
}
