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
 * Printable-ASCII atlas rendered with tinted vanilla quads. Supersampling bakes larger glyphs and
 * draws at the inverse scale; measurements remain in GUI pixels. Construction requires the render
 * thread because it uploads a GL texture.
 */
public final class CustomFont {

    private static final int CHARS = 127;         // printable ASCII (32-126); clampChar maps anything else to '?'
    private static final int GRID = 12;           // 12×12 cells covers 127 glyphs
    private static final int PAD = 2;            // texel padding per glyph (atlas space), avoids bleed/clip

    private final int supersample;                // atlas texels per GUI pixel = the GUI scale factor
    private final DynamicTexture texture;
    private final int atlasW;
    private final int atlasH;
    private final int cellW;                      // cell size in atlas texels
    private final int cellH;
    private final float guiAscent;                // baseline offset from the top, in GUI pixels
    private final int[] advance = new int[CHARS]; // glyph advance width in atlas texels

    public CustomFont(Font baseFont, int supersample) {
        this.supersample = supersample;
        Font font = baseFont.deriveFont(baseFont.getSize2D() * supersample);

        // Measure first (throwaway graphics) so the atlas can be sized before baking.
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

    /** Baseline offset from the glyph top in GUI pixels — lets mixed-size text share a baseline. */
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

    private void drawImmediate(String text, float x, float y, int argb) {
        WorldRenderer wr = beginDraw();
        appendString(wr, text, x, y, argb);
        endDraw();
    }

    /** GL setup plus one open POSITION_TEX_COLOR buffer shared by every string appended until {@link #endDraw()}. */
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

    private void appendString(WorldRenderer wr, String text, float x, float y, int argb) {
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
            penX += advance[c];
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

    /**
     * Trims text to {@code maxWidth}, appending {@code ellipsis} when truncation is required.
     * The ellipsis itself is trimmed when the available width is narrower than its glyphs.
     */
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

    /**
     * Greedy word wrap in GUI pixels. Long individual words remain uncut; an empty input produces one
     * empty line so callers can still size a tooltip or text block deterministically.
     */
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

    /** Like {@link #drawString} but with a one-device-pixel darkened drop shadow underneath. */
    public void drawStringWithShadow(String text, float x, float y, int argb) {
        if ((argb & 0xFC000000) == 0) {
            argb |= 0xFF000000; // same opaque default as drawString
        }
        int shadow = (argb & 0xFCFCFC) >> 2 | (argb & 0xFF000000);
        float offset = 1f / supersample; // one device pixel, whatever the GUI scale is
        // Append the shadow first so both layers share a draw while preserving their order.
        WorldRenderer wr = beginDraw();
        appendString(wr, text, x + offset, y + offset, shadow);
        appendString(wr, text, x, y, argb);
        endDraw();
    }

    private void appendGlyph(WorldRenderer wr, int c, float penX, float penY, int r, int g, int b, int a) {
        int col = c % GRID;
        int row = c / GRID;
        // Quad covers the glyph plus its cell padding so any overhang (e.g. italics) isn't clipped.
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

    /** Frees the GL atlas. Call only when dropping the instance — {@link Fonts} does this on a rebake. */
    public void dispose() {
        texture.deleteGlTexture();
    }

    /** Characters beyond the atlas range map to '?'; control characters retain blank cells. */
    private static int clampChar(char c) {
        return c < CHARS ? c : '?';
    }
}
