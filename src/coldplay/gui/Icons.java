package coldplay.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.texture.DynamicTexture;
import org.lwjgl.opengl.GL11;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Map;

/** Stroke icons on a 24-unit grid, rasterized once per device pixel size and tinted when drawn. */
public final class Icons {

    public enum Icon {
        CHECK, CLOSE, CHEVRON_LEFT, CHEVRON_RIGHT, CHEVRON_DOWN, GEAR, SEARCH,
        SWORD, MOVE, WRENCH, EYE, SNOWFLAKE, SLIDERS, LAYOUT
    }

    private static final Map<String, DynamicTexture> baked = new HashMap<String, DynamicTexture>();

    private Icons() {
    }

    /** {@code size} and {@code stroke} are GUI px; the stroke is in grid units, 2 is the usual weight. */
    public static void draw(Icon icon, float x, float y, float size, float stroke, int argb) {
        int scale = new ScaledResolution(Minecraft.getMinecraft()).getScaleFactor();
        int px = Math.max(1, Math.round(size * scale));
        String key = icon.name() + '/' + px + '/' + stroke;
        DynamicTexture texture = baked.get(key);
        if (texture == null) {
            texture = new DynamicTexture(rasterize(icon, px, stroke));
            GlStateManager.bindTexture(texture.getGlTextureId());
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            baked.put(key, texture);
        }
        GlStateManager.bindTexture(texture.getGlTextureId());
        GlassShader.image(x, y, size, size, 0.0F, 0.0F, 0.0F, 1.0F, 1.0F, argb);
    }

    private static BufferedImage rasterize(Icon icon, int px, float stroke) {
        BufferedImage image = new BufferedImage(px, px, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.scale(px / 24.0, px / 24.0);
        g.setColor(Color.WHITE);
        g.setStroke(new BasicStroke(stroke, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        switch (icon) {
            case CHECK:
                g.draw(poly(5, 12.5, 9.6, 17, 19, 7));
                break;
            case CLOSE:
                g.draw(new Line2D.Double(6, 6, 18, 18));
                g.draw(new Line2D.Double(18, 6, 6, 18));
                break;
            case CHEVRON_LEFT:
                g.draw(poly(15, 5, 8, 12, 15, 19));
                break;
            case CHEVRON_RIGHT:
                g.draw(poly(9, 5, 16, 12, 9, 19));
                break;
            case CHEVRON_DOWN:
                g.draw(poly(5, 9, 12, 16, 19, 9));
                break;
            case GEAR:
                g.draw(new Ellipse2D.Double(9, 9, 6, 6));
                g.draw(new Line2D.Double(12, 2, 12, 5));
                g.draw(new Line2D.Double(12, 19, 12, 22));
                g.draw(new Line2D.Double(2, 12, 5, 12));
                g.draw(new Line2D.Double(19, 12, 22, 12));
                g.draw(new Line2D.Double(4.9, 4.9, 7, 7));
                g.draw(new Line2D.Double(17, 17, 19.1, 19.1));
                g.draw(new Line2D.Double(4.9, 19.1, 7, 17));
                g.draw(new Line2D.Double(17, 7, 19.1, 4.9));
                break;
            case SEARCH:
                g.draw(new Ellipse2D.Double(4, 4, 14, 14));
                g.draw(new Line2D.Double(16.5, 16.5, 21, 21));
                break;
            case SWORD:
                g.draw(poly(14.5, 17.5, 3, 6, 3, 3, 6, 3, 17.5, 14.5));
                g.draw(new Line2D.Double(13, 19, 19, 13));
                g.draw(new Line2D.Double(16, 16, 20, 20));
                g.draw(new Line2D.Double(19, 21, 21, 19));
                break;
            case MOVE:
                g.draw(poly(5, 9, 2, 12, 5, 15));
                g.draw(poly(9, 5, 12, 2, 15, 5));
                g.draw(poly(15, 19, 12, 22, 9, 19));
                g.draw(poly(19, 9, 22, 12, 19, 15));
                g.draw(new Line2D.Double(2, 12, 22, 12));
                g.draw(new Line2D.Double(12, 2, 12, 22));
                break;
            case WRENCH:
                // open ring head, gap to the upper right, handle to the lower left
                g.draw(new Arc2D.Double(10.5, 3.5, 10, 10, 85, 280, Arc2D.OPEN));
                g.draw(new Line2D.Double(12, 12, 4.5, 19.5));
                break;
            case EYE:
                Path2D eye = new Path2D.Double();
                eye.moveTo(2, 12);
                eye.quadTo(12, 1.5, 22, 12);
                eye.quadTo(12, 22.5, 2, 12);
                eye.closePath();
                g.draw(eye);
                g.draw(new Ellipse2D.Double(9, 9, 6, 6));
                break;
            case SNOWFLAKE:
                g.draw(new Line2D.Double(12, 2, 12, 22));
                g.draw(new Line2D.Double(3.3, 7, 20.7, 17));
                g.draw(new Line2D.Double(3.3, 17, 20.7, 7));
                g.draw(poly(9, 4, 12, 6.2, 15, 4));
                g.draw(poly(9, 20, 12, 17.8, 15, 20));
                break;
            case SLIDERS:
                g.draw(new Line2D.Double(4, 7, 14, 7));
                g.draw(new Line2D.Double(18, 7, 20, 7));
                g.draw(new Line2D.Double(4, 17, 8, 17));
                g.draw(new Line2D.Double(12, 17, 20, 17));
                g.draw(new Ellipse2D.Double(14, 5, 4, 4));
                g.draw(new Ellipse2D.Double(8, 15, 4, 4));
                break;
            case LAYOUT:
                g.draw(new RoundRectangle2D.Double(3, 4, 18, 16, 6, 6));
                g.draw(new Line2D.Double(7, 9, 12, 9));
                g.draw(new Line2D.Double(7, 13, 10, 13));
                g.draw(new Line2D.Double(15, 13, 17, 13));
                break;
            default:
                break;
        }
        g.dispose();
        return image;
    }

    private static Path2D poly(double... xy) {
        Path2D path = new Path2D.Double();
        path.moveTo(xy[0], xy[1]);
        for (int i = 2; i < xy.length; i += 2) {
            path.lineTo(xy[i], xy[i + 1]);
        }
        return path;
    }
}
