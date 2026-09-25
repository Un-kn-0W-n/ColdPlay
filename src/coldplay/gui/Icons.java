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
        SWORD, MOVE, WRENCH, EYE, SNOWFLAKE, SLIDERS, LAYOUT,
        USER, USERS, SERVERS, GLOBE, DOCUMENT, POWER,
        CHEVRON_UP, PENCIL, REFRESH, TRASH, PLUS, PLAY, LINK, WIFI, KEY, LOGIN, LOCK,
        MONITOR, KEYBOARD, SPEAKER, LAYERS, SHIRT, CHAT, PULSE, SPARKLE, CUBE, STAR, HEART
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
            case USER:
                g.draw(new Ellipse2D.Double(8, 4, 8, 8));
                g.draw(curve(4, 21, 5.5, 17, 8.5, 15, 12, 15, 15.5, 15, 18.5, 17, 20, 21));
                break;
            case USERS:
                g.draw(new Ellipse2D.Double(5.5, 4.5, 7, 7));
                g.draw(curve(2.5, 20, 3.5, 16.5, 6, 14.5, 9, 14.5, 12, 14.5, 14.5, 16.5, 15.5, 20));
                g.draw(new Arc2D.Double(12.5, 4.5, 7, 7, 90, -180, Arc2D.OPEN));
                g.draw(curve(18, 14.8, 19.7, 15.5, 20.9, 17.2, 21.5, 20));
                break;
            case SERVERS:
                g.draw(new RoundRectangle2D.Double(3, 4, 18, 7, 4, 4));
                g.draw(new RoundRectangle2D.Double(3, 13, 18, 7, 4, 4));
                g.draw(new Line2D.Double(7, 7.5, 7.01, 7.5));
                g.draw(new Line2D.Double(7, 16.5, 7.01, 16.5));
                break;
            case GLOBE:
                g.draw(new Ellipse2D.Double(3, 3, 18, 18));
                g.draw(new Ellipse2D.Double(8.2, 3, 7.6, 18));
                g.draw(new Line2D.Double(3, 12, 21, 12));
                break;
            case DOCUMENT:
                Path2D page = poly(6, 3, 15, 3, 19, 7, 19, 21, 6, 21);
                page.closePath();
                g.draw(page);
                g.draw(new Line2D.Double(9, 7, 13, 7));
                g.draw(new Line2D.Double(9, 11, 16, 11));
                g.draw(new Line2D.Double(9, 15, 16, 15));
                break;
            case POWER:
                g.draw(new Line2D.Double(12, 3, 12, 11));
                g.draw(new Arc2D.Double(4, 4.41, 16, 16, 135.5, 269, Arc2D.OPEN));
                break;
            case CHEVRON_UP:
                g.draw(poly(5, 15, 12, 8, 19, 15));
                break;
            case PENCIL:
                g.draw(closed(4, 20, 8, 20, 19, 9, 15, 5, 4, 16));
                g.draw(new Line2D.Double(13.5, 6.5, 17.5, 10.5));
                break;
            case REFRESH:
                g.draw(new Arc2D.Double(4, 4, 16, 16, 0, -315, Arc2D.OPEN));
                g.draw(poly(20, 4, 20, 9, 15, 9));
                break;
            case TRASH:
                g.draw(new Line2D.Double(4, 7, 20, 7));
                g.draw(new Line2D.Double(10, 11, 10, 17));
                g.draw(new Line2D.Double(14, 11, 14, 17));
                g.draw(poly(6, 7, 7, 20, 17, 20, 18, 7));
                g.draw(poly(9, 7, 9, 4, 15, 4, 15, 7));
                break;
            case PLUS:
                g.draw(new Line2D.Double(12, 5, 12, 19));
                g.draw(new Line2D.Double(5, 12, 19, 12));
                break;
            case PLAY:
                g.draw(closed(7, 4.5, 7, 19.5, 19, 12));
                break;
            case LINK:
                g.rotate(-Math.PI / 4, 12, 12);
                g.draw(new RoundRectangle2D.Double(3, 8.5, 10, 7, 7, 7));
                g.draw(new RoundRectangle2D.Double(11, 8.5, 10, 7, 7, 7));
                break;
            case WIFI:
                g.draw(new Arc2D.Double(-3, 5.18, 30, 30, 131.8, -83.6, Arc2D.OPEN));
                g.draw(new Arc2D.Double(2, 9.64, 20, 20, 134.4, -88.8, Arc2D.OPEN));
                g.draw(new Arc2D.Double(7, 14.57, 10, 10, 134.4, -88.8, Arc2D.OPEN));
                g.draw(new Line2D.Double(12, 19.5, 12.01, 19.5));
                break;
            case KEY:
                g.draw(new Ellipse2D.Double(4, 11, 8, 8));
                g.draw(new Line2D.Double(11, 12, 20, 3));
                g.draw(new Line2D.Double(17, 6, 20, 9));
                g.draw(new Line2D.Double(15, 8, 17, 10));
                break;
            case LOGIN:
                Path2D door = new Path2D.Double();
                door.moveTo(14, 4);
                door.lineTo(18, 4);
                door.quadTo(20, 4, 20, 6);
                door.lineTo(20, 18);
                door.quadTo(20, 20, 18, 20);
                door.lineTo(14, 20);
                g.draw(door);
                g.draw(poly(10, 16, 14, 12, 10, 8));
                g.draw(new Line2D.Double(14, 12, 4, 12));
                break;
            case LOCK:
                g.draw(new RoundRectangle2D.Double(5, 11, 14, 9, 4, 4));
                g.draw(new Arc2D.Double(8, 4, 8, 8, 180, -180, Arc2D.OPEN));
                g.draw(new Line2D.Double(8, 8, 8, 11));
                g.draw(new Line2D.Double(16, 8, 16, 11));
                break;
            case MONITOR:
                g.draw(new RoundRectangle2D.Double(3, 4, 18, 12, 4, 4));
                g.draw(new Line2D.Double(8, 20, 16, 20));
                g.draw(new Line2D.Double(12, 16, 12, 20));
                break;
            case KEYBOARD:
                g.draw(new RoundRectangle2D.Double(2, 6, 20, 12, 4, 4));
                for (int k = 6; k <= 18; k += 4) {
                    g.draw(new Line2D.Double(k, 10, k + 0.01, 10));
                }
                g.draw(new Line2D.Double(7, 14, 17, 14));
                break;
            case SPEAKER:
                g.draw(closed(4, 9, 4, 15, 8, 15, 13, 19, 13, 5, 8, 9));
                g.draw(new Arc2D.Double(7.93, 7, 10, 10, 44.4, -88.8, Arc2D.OPEN));
                g.draw(new Arc2D.Double(4.48, 3.5, 17, 17, 44.9, -89.8, Arc2D.OPEN));
                break;
            case LAYERS:
                g.draw(closed(12, 3, 21, 8, 12, 13, 3, 8));
                g.draw(poly(3, 13, 12, 18, 21, 13));
                break;
            case SHIRT:
                g.draw(poly(16, 3, 21, 6, 19, 10, 16, 9, 16, 21, 8, 21, 8, 9, 5, 10, 3, 6, 8, 3));
                g.draw(new Arc2D.Double(8, -1, 8, 8, 180, 180, Arc2D.OPEN));
                break;
            case CHAT:
                g.draw(closed(4, 5, 20, 5, 20, 16, 9, 16, 4, 20));
                break;
            case PULSE:
                g.draw(poly(3, 12, 7, 12, 10, 5, 14, 19, 17, 12, 21, 12));
                break;
            case SPARKLE:
                g.draw(closed(12, 4, 13.8, 8.2, 18, 10, 13.8, 11.8, 12, 16, 10.2, 11.8, 6, 10, 10.2, 8.2));
                g.draw(closed(19, 15, 19.8, 16.9, 21.7, 17.7, 19.8, 18.5, 19, 20.4, 18.2, 18.5, 16.3, 17.7, 18.2, 16.9));
                break;
            case CUBE:
                g.draw(closed(12, 3, 20, 7.5, 20, 16.5, 12, 21, 4, 16.5, 4, 7.5));
                g.draw(poly(4, 7.5, 12, 12, 20, 7.5));
                g.draw(new Line2D.Double(12, 12, 12, 21));
                break;
            case STAR:
                g.draw(closed(12, 3, 14.6, 8.6, 20.7, 9.3, 16.2, 13.5, 17.4, 19.5, 12, 16.5, 6.6, 19.5, 7.8, 13.5,
                        3.3, 9.3, 9.4, 8.6));
                break;
            case HEART:
                Path2D heart = curve(12, 20, 12, 20, 5, 15.6, 5, 10, 5, 6.5, 10.5, 5, 12, 7.4,
                        13.5, 5, 19, 6.5, 19, 10, 19, 15.6, 12, 20, 12, 20);
                heart.closePath();
                g.draw(heart);
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

    private static Path2D closed(double... xy) {
        Path2D path = poly(xy);
        path.closePath();
        return path;
    }

    /** A start point followed by cubic segments, three points each. */
    private static Path2D curve(double... xy) {
        Path2D path = new Path2D.Double();
        path.moveTo(xy[0], xy[1]);
        for (int i = 2; i < xy.length; i += 6) {
            path.curveTo(xy[i], xy[i + 1], xy[i + 2], xy[i + 3], xy[i + 4], xy[i + 5]);
        }
        return path;
    }
}
