package coldplay.gui;

import coldplay.util.font.CustomFont;
import coldplay.util.font.FontRef;
import coldplay.util.font.Fonts;
import net.minecraft.client.Minecraft;
import net.minecraft.util.ColorMath;

import java.util.List;

/** Smoke glass pieces shared by the menu screens. GUI px, the design's CSS px x0.75. */
public final class GlassUi {

    public static final int FROST = 0xFF84D2E3;
    public static final int FROST_HOVER = 0xFFA5E2EF;
    public static final int ICE = 0xFFF4F6F8;
    public static final int DANGER = 0xFFEE8A8E;
    public static final int ON_ACCENT = 0xFF0B1A1E;
    public static final int TEXT = 0xDBFFFFFF;
    public static final int DIM = 0x8CFFFFFF;
    public static final int MUTE = 0x73FFFFFF;
    public static final int LINE = 0x14FFFFFF;

    public static final Glass CARD = new Glass(0x800E1015, 0x800E1015, 0x2EFFFFFF, 10.5F, 1.4F, 36.0F, 13.5F, 0.35F);

    public static final FontRef BODY = new FontRef(Fonts.GEIST, 9.375F);
    public static final FontRef BODY_MEDIUM = new FontRef(Fonts.GEIST_MEDIUM, 9.375F);
    public static final FontRef STRONG = new FontRef(Fonts.GEIST_SEMIBOLD, 9.375F);
    public static final FontRef ROW = new FontRef(Fonts.GEIST_MEDIUM, 10.125F);
    public static final FontRef MEDIUM = new FontRef(Fonts.GEIST_MEDIUM, 9.75F);
    public static final FontRef LABEL = new FontRef(Fonts.GEIST_MEDIUM, 8.25F);
    public static final FontRef TITLE = new FontRef(Fonts.GEIST_SEMIBOLD, 13.5F);
    public static final FontRef SECTION = new FontRef(Fonts.GEIST_MEDIUM, 7.5F);
    public static final FontRef MONO = new FontRef(Fonts.GEIST_MONO, 8.25F);
    public static final FontRef MONO_BODY = new FontRef(Fonts.GEIST_MONO, 9.375F);
    public static final FontRef SMALL = new FontRef(Fonts.GEIST, 8.625F);

    public static final float CHIP_H = 25.5F;
    public static final float RADIUS = 4.5F;

    private GlassUi() {
    }

    /** Width of an auto-sized chip or primary button. */
    public static float chipWidth(String label, Icons.Icon icon, boolean primary) {
        float pad = primary ? 10.5F : 9.0F;
        CustomFont font = primary ? STRONG.get() : BODY.get();
        return pad * 2.0F + (icon != null ? 16.5F : 0.0F) + font.getStringWidth(label);
    }

    /** Menu row with an icon and a chevron that slides on hover. */
    public static void row(float x, float y, float w, float h, String label, Icons.Icon icon, float t,
                           boolean enabled, boolean danger) {
        int accent = danger ? DANGER : FROST;
        int tint = Theme.withAlpha(accent, Math.round(0x1F * t));
        GlassShader.rect(x, y, w, h, RADIUS, tint, tint);
        Icons.draw(icon, x + 9.0F, y + (h - 12.0F) / 2.0F, 12.0F, 1.8F,
                ColorMath.lerpArgb(danger ? DANGER : 0x9EFFFFFF, accent, t));
        CustomFont font = ROW.get();
        int color = enabled ? ColorMath.lerpArgb(danger ? DANGER : TEXT, danger ? DANGER : 0xFFFFFFFF, t) : MUTE;
        font.drawString(label, x + 30.0F, y + (h - font.getHeight()) / 2.0F, color);
        Icons.draw(Icons.Icon.CHEVRON_RIGHT, x + w - 18.0F + 2.25F * t, y + (h - 10.5F) / 2.0F, 10.5F, 2.0F,
                ColorMath.lerpArgb(0x4DFFFFFF, accent, t));
    }

    /** Outlined chip; {@code left} packs the content from the left edge instead of centering it. */
    public static void chip(float x, float y, float w, float h, String label, Icons.Icon icon, float t,
                            boolean enabled, boolean danger, boolean left) {
        float a = enabled ? 1.0F : 0.38F;
        if (!enabled) {
            t = 0.0F;
        }
        int accent = danger ? DANGER : FROST;
        int fill = ColorMath.lerpArgb(0x0AFFFFFF, Theme.withAlpha(accent, danger ? 0x1F : 0x1A), t);
        GlassShader.rect(x, y, w, h, RADIUS, Theme.applyAlpha(fill, a), Theme.applyAlpha(fill, a));
        GlassShader.stroke(x, y, w, h, RADIUS, Theme.applyAlpha(
                ColorMath.lerpArgb(LINE, Theme.withAlpha(accent, danger ? 0x66 : 0x59), t), a));
        if (label == null) {
            Icons.draw(icon, x + (w - 10.5F) / 2.0F, y + (h - 10.5F) / 2.0F, 10.5F, 2.0F,
                    Theme.applyAlpha(0x8CFFFFFF, a));
            return;
        }
        CustomFont font = BODY.get();
        float content = (icon != null ? 16.5F : 0.0F) + font.getStringWidth(label);
        float cx = left ? x + 7.5F : x + (w - content) / 2.0F;
        if (icon != null) {
            Icons.draw(icon, cx, y + (h - 10.5F) / 2.0F, 10.5F, 1.9F, Theme.applyAlpha(danger ? DANGER : 0x8CFFFFFF, a));
            cx += 16.5F;
        }
        int text = danger ? DANGER : ColorMath.lerpArgb(0xCCFFFFFF, 0xFFFFFFFF, t);
        font.drawString(label, cx, y + (h - font.getHeight()) / 2.0F, Theme.applyAlpha(text, a));
    }

    /** Frost filled button for the screen's main action. */
    public static void primary(float x, float y, float w, float h, String label, Icons.Icon icon, float t,
                               boolean enabled) {
        float a = enabled ? 1.0F : 0.38F;
        int fill = Theme.applyAlpha(ColorMath.lerpArgb(FROST, FROST_HOVER, enabled ? t : 0.0F), a);
        GlassShader.rect(x, y, w, h, RADIUS, fill, fill);
        CustomFont font = STRONG.get();
        float content = (icon != null ? 16.5F : 0.0F) + font.getStringWidth(label);
        float cx = x + (w - content) / 2.0F;
        if (icon != null) {
            Icons.draw(icon, cx, y + (h - 10.5F) / 2.0F, 10.5F, 2.2F, Theme.applyAlpha(ON_ACCENT, a));
            cx += 16.5F;
        }
        font.drawString(label, cx, y + (h - font.getHeight()) / 2.0F, Theme.applyAlpha(ON_ACCENT, a));
    }

    /** Borderless icon button that lights up on hover. */
    public static void ghost(float x, float y, float size, Icons.Icon icon, boolean hover, boolean danger) {
        if (hover) {
            int fill = danger ? 0x24EE8A8E : 0x14FFFFFF;
            GlassShader.rect(x, y, size, size, 3.75F, fill, fill);
        }
        int color = hover ? (danger ? DANGER : 0xFFFFFFFF) : 0x80FFFFFF;
        Icons.draw(icon, x + (size - 9.0F) / 2.0F, y + (size - 9.0F) / 2.0F, 9.0F, 2.3F, color);
    }

    /** Background of a list row: frost when selected, a faint lift on hover. */
    public static void entry(float x, float y, float w, float h, boolean hover, boolean selected) {
        if (selected) {
            GlassShader.rect(x, y, w, h, RADIUS, 0x1F84D2E3, 0x1F84D2E3);
            GlassShader.stroke(x, y, w, h, RADIUS, 0x5284D2E3);
        } else if (hover) {
            GlassShader.rect(x, y, w, h, RADIUS, 0x0DFFFFFF, 0x0DFFFFFF);
        }
    }

    /** Sunken box a list scrolls inside. */
    public static void well(float x, float y, float w, float h) {
        GlassShader.rect(x, y, w, h, 6.0F, 0x29000000, 0x29000000);
        GlassShader.stroke(x, y, w, h, 6.0F, 0x0FFFFFFF);
    }

    public static void section(String text, float x, float y) {
        CustomFont font = SECTION.get();
        font.drawString(text.toUpperCase(java.util.Locale.ROOT), x, y + (9.0F - font.getHeight()) / 2.0F, MUTE, 0.75F);
    }

    public static void divider(float x, float y, float w) {
        GlassShader.rect(x, y, w, 0.75F, 0.0F, LINE, LINE);
    }

    /** Small tag, returns its width. */
    public static float badge(float x, float y, String text, int fill, int color) {
        CustomFont font = LABEL.get();
        float w = 10.5F + font.getStringWidth(text);
        GlassShader.rect(x, y, w, 15.0F, 3.0F, fill, fill);
        font.drawString(text, x + 5.25F, y + (15.0F - font.getHeight()) / 2.0F, color);
        return w;
    }

    public static float badgeWidth(String text) {
        return 10.5F + LABEL.get().getStringWidth(text);
    }

    public static float kbdWidth(String key) {
        return 10.5F + MONO.get().getStringWidth(key);
    }

    /** Key cap, returns its width. */
    public static float kbd(float x, float y, String key) {
        CustomFont font = MONO.get();
        float w = kbdWidth(key);
        GlassShader.rect(x, y, w, 15.0F, 3.75F, 0x1A84D2E3, 0x1A84D2E3);
        GlassShader.stroke(x, y, w, 15.0F, 3.75F, 0x4D84D2E3);
        font.drawString(key, x + 5.25F, y + (15.0F - font.getHeight()) / 2.0F, FROST);
        return w;
    }

    /** Text field; masked values show as stars and the tail that fits stays visible with the caret. */
    public static void field(CustomFont font, float x, float y, float w, float h, String value, boolean focused,
                             String placeholder, int cursorCounter, boolean masked, Icons.Icon icon) {
        GlassShader.rect(x, y, w, h, RADIUS, 0x0DFFFFFF, 0x0DFFFFFF);
        GlassShader.stroke(x, y, w, h, RADIUS, focused ? 0x8C84D2E3 : 0x1AFFFFFF);
        float tx = x + 7.5F;
        if (icon != null) {
            Icons.draw(icon, tx, y + (h - 10.5F) / 2.0F, 10.5F, 1.9F, 0x80FFFFFF);
            tx += 16.5F;
        }
        float ty = y + (h - font.getHeight()) / 2.0F;
        if (value.isEmpty() && !focused) {
            font.drawString(font.trimToWidth(placeholder, Math.round(x + w - 7.5F - tx), ""), tx, ty, 0x66FFFFFF);
            return;
        }
        String shown = masked ? stars(value.length()) : value;
        shown = tail(font, shown, x + w - 9.0F - tx);
        font.drawString(shown, tx, ty, 0xFFFFFFFF);
        if (focused && cursorCounter / 6 % 2 == 0) {
            GlassShader.rect(tx + font.getStringWidth(shown) + 0.75F, ty, 0.75F, font.getHeight(), 0.0F, FROST, FROST);
        }
    }

    private static String stars(int count) {
        char[] chars = new char[count];
        java.util.Arrays.fill(chars, '*');
        return new String(chars);
    }

    private static String tail(CustomFont font, String value, float maxWidth) {
        for (int i = 0; i < value.length(); i++) {
            if (font.getStringWidth(value.substring(i)) <= maxWidth) {
                return value.substring(i);
            }
        }
        return "";
    }

    /** Segmented switch; {@code hover} is the hovered segment or -1. */
    public static void segmented(float x, float y, float w, float h, String[] labels, int selected, int hover,
                                 boolean enabled) {
        float a = enabled ? 1.0F : 0.38F;
        GlassShader.rect(x, y, w, h, 5.25F, Theme.applyAlpha(0x0AFFFFFF, a), Theme.applyAlpha(0x0AFFFFFF, a));
        GlassShader.stroke(x, y, w, h, 5.25F, Theme.applyAlpha(LINE, a));
        CustomFont font = BODY_MEDIUM.get();
        float segW = segmentWidth(w, labels.length);
        for (int i = 0; i < labels.length; i++) {
            float sx = x + 2.25F + i * (segW + 2.25F);
            if (i == selected) {
                GlassShader.rect(sx, y + 2.25F, segW, h - 4.5F, 3.75F, Theme.applyAlpha(0x2984D2E3, a),
                        Theme.applyAlpha(0x2984D2E3, a));
            }
            int color = i == selected || (enabled && i == hover) ? 0xFFFFFFFF : 0x99FFFFFF;
            font.drawCentered(labels[i], sx + segW / 2.0F, y + (h - font.getHeight()) / 2.0F, Theme.applyAlpha(color, a));
        }
    }

    /** The segment under {@code mouseX}, or -1. */
    public static int segmentAt(float x, float w, int count, int mouseX) {
        float segW = segmentWidth(w, count);
        for (int i = 0; i < count; i++) {
            float sx = x + 2.25F + i * (segW + 2.25F);
            if (mouseX >= sx && mouseX < sx + segW) {
                return i;
            }
        }
        return -1;
    }

    private static float segmentWidth(float w, int count) {
        return (w - 2.25F * (count + 1)) / count;
    }

    public static void toggle(float x, float y, boolean on) {
        int track = on ? FROST : 0x33FFFFFF;
        GlassShader.rect(x, y, 21.0F, 12.0F, 6.0F, track, track);
        GlassShader.fill(on ? x + 10.5F : x + 1.5F, y + 1.5F, 9.0F, 9.0F, 4.5F, 0xFFFFFFFF, 1.5F, 0.75F, 0.3F);
    }

    /** Five signal bars, 9 tall; {@code level} 1-5 lit, 0 or less animates a ping in progress. */
    public static void pingBars(float x, float y, int level, int seed) {
        for (int i = 0; i < 5; i++) {
            float bh = 3.0F + i * 1.5F;
            int color;
            if (level > 0) {
                color = i < level ? FROST : 0x29FFFFFF;
            } else {
                double phase = (Minecraft.getSystemTime() / 1200.0 - i * 0.125 - seed * 0.1) % 1.0;
                color = Theme.withAlpha(FROST, (int) (64 + 191 * (0.5 - 0.5 * Math.cos(phase * Math.PI * 2))));
            }
            GlassShader.rect(x + i * 3.75F, y + 9.0F - bh, 2.25F, bh, 0.75F, color, color);
        }
    }

    /** Glass tooltip beside the cursor, kept on screen. */
    public static void tooltip(List<String> lines, int mouseX, int mouseY, float screenW, float screenH) {
        CustomFont font = BODY.get();
        float w = 0.0F;
        for (String line : lines) {
            w = Math.max(w, font.getStringWidth(line));
        }
        w += 13.5F;
        float h = lines.size() * (font.getHeight() + 1.5F) - 1.5F + 12.0F;
        float x = mouseX + 9.0F + w > screenW ? mouseX - 9.0F - w : mouseX + 9.0F;
        float y = Math.min(mouseY + 9.0F, screenH - h - 3.0F);
        GlassShader.panel(x, y, w, h, 4.5F, Glass.SMOKE_PANEL);
        float ly = y + 6.0F;
        for (String line : lines) {
            font.drawString(line, x + 6.75F, ly, TEXT);
            ly += font.getHeight() + 1.5F;
        }
    }
}
