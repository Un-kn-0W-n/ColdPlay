package coldplay.util.font;

import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;

import org.lwjgl.opengl.GL11;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * Printable-ASCII glyph atlas drawn as tinted quads. Glyphs bake at {@code supersample}x and draw at
 * the inverse scale; all measurements are GUI pixels. Construct on the render thread.
 */
public final class CustomFont {

    private static final int CHARS = 127;         // atlas covers chars 0-126
    private static final int GRID = 12;           // 12x12 cells hold 127 glyphs
    private static final int PAD = 2;             // atlas texels per glyph edge

    private final int supersample;                // atlas texels per GUI pixel
    private final DynamicTexture texture;
    private final int atlasW;
    private final int atlasH;
    private final int cellW;                      // atlas texels
    private final int cellH;
    private final float guiAscent;                // GUI pixels
    private final int[] advance = new int[CHARS]; // atlas texels

    public CustomFont(Font baseFont, int supersample) {
        this.supersample = supersample;
        Font font = baseFont.deriveFont(baseFont.getSize2D() * supersample);

        // Measure with a throwaway graphics so the atlas can be sized first.
        Graphics2D probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).createGraphics();
        probe.setFont(font);
        FontMetrics fm = probe.getFontMetrics();
        int maxAdvance = 0;
        for (int c = 0; c < CHARS; c++) {
            int w = fm.charWidth((char) c);
            advance[c] = w;
            if (w > maxAdvance) {
                maxAdvance = w;
            }
        }
        int ascent = fm.getAscent();
        this.guiAscent = ascent / (float) supersample;
        this.cellW = maxAdvance + PAD * 2;
        this.cellH = ascent + fm.getDescent() + PAD * 2;
        this.atlasW = cellW * GRID;
        this.atlasH = cellH * GRID;
        probe.dispose();

        BufferedImage image = new BufferedImage(atlasW, atlasH, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setFont(font);
        g.setColor(Color.WHITE);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        for (int c = 32; c < CHARS; c++) { // control chars (< 32) have no glyph
            int col = c % GRID;
            int row = c / GRID;
            g.drawString(String.valueOf((char) c), col * cellW + PAD, row * cellH + PAD + ascent);
        }
        g.dispose();

        this.texture = new DynamicTexture(image);
        // Linear filtering covers the sub-texel rounding of the inverse scale.
        GlStateManager.bindTexture(texture.getGlTextureId());
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GlStateManager.bindTexture(0);
    }

    /** Width of {@code text} in GUI pixels. */
    public int getStringWidth(String text) {
        int width = 0;
        for (int i = 0; i < text.length(); i++) {
            width += advance[clampChar(text.charAt(i))];
        }
        return Math.round(width / (float) supersample);
    }

    /** Height of one line in GUI pixels. */
    public int getHeight() {
        return Math.round((cellH - PAD * 2) / (float) supersample);
    }

    /** Baseline offset from the glyph top, in GUI pixels. */
    public float getAscent() {
        return guiAscent;
    }

    /** Draws {@code text} with its top-left at (x, y); {@code argb} tints the glyphs. */
    public void drawString(String text, float x, float y, int argb) {
        if ((argb & 0xFC000000) == 0) {
            argb |= 0xFF000000; // no alpha supplied -> opaque (matches vanilla FontRenderer)
        }
        drawImmediate(text, x, y, argb);
    }

    /** {@link #drawString} with {@code tracking} GUI px of extra space after each character. */
    public void drawString(String text, float x, float y, int argb, float tracking) {
        if ((argb & 0xFC000000) == 0) {
            argb |= 0xFF000000;
        }
        WorldRenderer wr = beginDraw();
        appendString(wr, text, x, y, argb, tracking);
        endDraw();
    }

    public int getStringWidth(String text, float tracking) {
        return getStringWidth(text) + Math.round(tracking * text.length());
    }

    private void drawImmediate(String text, float x, float y, int argb) {
        WorldRenderer wr = beginDraw();
        appendString(wr, text, x, y, argb, 0.0F);
        endDraw();
    }

    /** GL setup and one open buffer shared by every string appended until {@link #endDraw()}. */
    private WorldRenderer beginDraw() {
        GlStateManager.pushMatrix();
        GlStateManager.scale(1f / supersample, 1f / supersample, 1f / supersample);
        GlStateManager.enableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        GlStateManager.color(1f, 1f, 1f, 1f); // tint comes from vertex colours
        GlStateManager.bindTexture(texture.getGlTextureId());
        WorldRenderer wr = Tessellator.getInstance().getWorldRenderer();
        wr.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX_COLOR);
        return wr;
    }

    private void appendString(WorldRenderer wr, String text, float x, float y, int argb, float tracking) {
        int a = argb >>> 24 & 0xFF;
        int r = argb >> 16 & 0xFF;
        int g = argb >> 8 & 0xFF;
        int b = argb & 0xFF;
        float penX = x * supersample;
        float penY = y * supersample;
        for (int i = 0; i < text.length(); i++) {
            int c = clampChar(text.charAt(i));
            if (c >= 32) {
                appendGlyph(wr, c, penX, penY, r, g, b, a);
            }
            penX += advance[c] + tracking * supersample;
        }
    }

    private static void endDraw() {
        Tessellator.getInstance().draw();
        GlStateManager.popMatrix();
        GlStateManager.color(1f, 1f, 1f, 1f);
    }

    public void drawCentered(String text, float centerX, float y, int argb) {
        drawString(text, centerX - getStringWidth(text) / 2.0F, y, argb);
    }

    public void drawCenteredWithShadow(String text, float centerX, float y, int argb) {
        drawStringWithShadow(text, centerX - getStringWidth(text) / 2.0F, y, argb);
    }

    public void drawCenteredInRect(String text, float x, float y, float width, float height, int argb) {
        drawCentered(text, x + width / 2.0F, y + (height - getHeight()) / 2.0F, argb);
    }

    public void drawCenteredInRectWithShadow(String text, float x, float y,
                                             float width, float height, int argb) {
        drawCenteredWithShadow(text, x + width / 2.0F, y + (height - getHeight()) / 2.0F, argb);
    }

    /** Trims to {@code maxWidth}, appending {@code ellipsis} when cut. */
    public String trimToWidth(String text, int maxWidth, String ellipsis) {
        String source = text == null ? "" : text;
        String suffix = ellipsis == null ? "" : ellipsis;
        if (maxWidth <= 0) {
            return "";
        }
        if (getStringWidth(source) <= maxWidth) {
            return source;
        }
        while (!suffix.isEmpty() && getStringWidth(suffix) > maxWidth) {
            suffix = suffix.substring(0, suffix.length() - 1);
        }
        StringBuilder trimmed = new StringBuilder();
        int contentWidth = maxWidth - getStringWidth(suffix);
        for (int i = 0; i < source.length(); i++) {
            String candidate = trimmed.toString() + source.charAt(i);
            if (getStringWidth(candidate) > contentWidth) {
                break;
            }
            trimmed.append(source.charAt(i));
        }
        return trimmed.append(suffix).toString();
    }

    /** Greedy word wrap; long words are not cut and empty input gives one empty line. */
    public List<String> wrapToWidth(String text, int maxWidth) {
        List<String> lines = new ArrayList<String>();
        String source = text == null ? "" : text;
        StringBuilder line = new StringBuilder();
        for (String word : source.split(" ")) {
            String candidate = line.length() == 0 ? word : line + " " + word;
            if (line.length() > 0 && getStringWidth(candidate) > maxWidth) {
                lines.add(line.toString());
                line.setLength(0);
                line.append(word);
            } else {
                line.setLength(0);
                line.append(candidate);
            }
        }
        if (line.length() > 0 || lines.isEmpty()) {
            lines.add(line.toString());
        }
        return lines;
    }

    /** {@link #drawString} with a one-device-pixel drop shadow. */
    public void drawStringWithShadow(String text, float x, float y, int argb) {
        if ((argb & 0xFC000000) == 0) {
            argb |= 0xFF000000;
        }
        int shadow = (argb & 0xFCFCFC) >> 2 | (argb & 0xFF000000);
        float offset = 1f / supersample; // one device pixel at any GUI scale
        // Shadow first; both layers share one draw.
        WorldRenderer wr = beginDraw();
        appendString(wr, text, x + offset, y + offset, shadow, 0.0F);
        appendString(wr, text, x, y, argb, 0.0F);
        endDraw();
    }

    /** {@link #drawString} over an {@code outline} colored halo about {@code radius} GUI px wide. */
    public void drawStringWithOutline(String text, float x, float y, int argb, int outline, float radius) {
        if ((argb & 0xFC000000) == 0) {
            argb |= 0xFF000000;
        }
        // eight offset copies; about two overlap along a straight edge, so each carries half the density
        double alpha = (outline >>> 24) / 255.0;
        int copy = (int) Math.round((1.0 - Math.sqrt(1.0 - alpha)) * 255.0) << 24 | (outline & 0xFFFFFF);
        WorldRenderer wr = beginDraw();
        for (int i = 0; i < 8; i++) {
            double angle = i * Math.PI / 4.0;
            appendString(wr, text, x + (float) Math.cos(angle) * radius, y + (float) Math.sin(angle) * radius, copy, 0.0F);
        }
        appendString(wr, text, x, y, argb, 0.0F);
        endDraw();
    }

    private void appendGlyph(WorldRenderer wr, int c, float penX, float penY, int r, int g, int b, int a) {
        int col = c % GRID;
        int row = c / GRID;
        // Quad includes the cell padding so overhang (italics) is not clipped.
        float gw = advance[c] + PAD * 2f;
        float gh = cellH;
        float x0 = penX - PAD;
        float y0 = penY - PAD;
        float u0 = (col * cellW) / (float) atlasW;
        float v0 = (row * cellH) / (float) atlasH;
        float u1 = (col * cellW + gw) / atlasW;
        float v1 = (row * cellH + gh) / atlasH;
        wr.pos(x0, y0 + gh, 0).tex(u0, v1).color(r, g, b, a).endVertex();
        wr.pos(x0 + gw, y0 + gh, 0).tex(u1, v1).color(r, g, b, a).endVertex();
        wr.pos(x0 + gw, y0, 0).tex(u1, v0).color(r, g, b, a).endVertex();
        wr.pos(x0, y0, 0).tex(u0, v0).color(r, g, b, a).endVertex();
    }

    /** Frees the GL atlas; call only when dropping the instance. */
    public void dispose() {
        texture.deleteGlTexture();
    }

    /** Out-of-range characters map to '?'. */
    private static int clampChar(char c) {
        return c < CHARS ? c : '?';
    }
}
