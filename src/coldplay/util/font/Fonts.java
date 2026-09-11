package coldplay.util.font;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;

import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.io.InputStream;

/**
 * Loads shared font atlases lazily on the render thread so a GL context exists. An AWT logical font
 * keeps the HUD usable if the bundled TTF cannot be loaded.
 */
public final class Fonts {

    /** Classpath path of the bundled font ({@code resources/} is a source root, so it sits at the JAR root). */
    private static final String FONT_PATH = "/assets/minecraft/coldplay/fonts/hud.ttf";

    private static final float LIST_SIZE = 10f;   // HUD module rows
    private static final float TITLE_SIZE = 15f;  // HUD watermark
    private static final float MEDIUM_SIZE = 11f; // ClickGUI headers + rows
    private static final float LOGO_SIZE = 34f;   // boot screens

    public static CustomFont title;  // larger — HUD watermark
    public static CustomFont list;   // smaller — HUD module rows
    public static CustomFont medium; // ClickGUI
    public static CustomFont logo;   // splash + main menu wordmark

    /** Cap on load retries so a permanent failure stops re-attempting, while a transient one can recover. */
    private static final int MAX_ATTEMPTS = 10;

    /** Cap supersampling to keep the logo atlas below common 4096px texture limits; larger GUI
     *  scales magnify the capped atlas. */
    private static final int MAX_SUPERSAMPLE = 4;

    private static int attempts;
    private static boolean loaded;
    private static int bakedScale;

    private Fonts() {
    }

    /**
     * Idempotent; safe to call every frame. Must run on the render thread (GL context required).
     *
     * <p>Rebakes when the capped GUI scale changes to keep glyphs aligned with device pixels.
     */
    public static void load() {
        load(new ScaledResolution(Minecraft.getMinecraft()).getScaleFactor());
    }

    /** Per-frame entry point: pass the frame's {@link ScaledResolution} scale so the already-loaded early-out allocates nothing. */
    public static void load(int scaleFactor) {
        int scale = Math.min(MAX_SUPERSAMPLE, scaleFactor);
        if ((loaded && scale == bakedScale) || attempts >= MAX_ATTEMPTS) {
            return;
        }
        attempts++;
        try {
            Font base = loadBase();
            // Bake all four before swapping, so a failure part-way leaves the old atlases usable.
            CustomFont newList = new CustomFont(base.deriveFont(LIST_SIZE), scale);
            CustomFont newTitle = new CustomFont(base.deriveFont(TITLE_SIZE), scale);
            CustomFont newMedium = new CustomFont(base.deriveFont(MEDIUM_SIZE), scale);
            CustomFont newLogo = new CustomFont(base.deriveFont(LOGO_SIZE), scale);
            dispose(list, title, medium, logo);
            list = newList;
            title = newTitle;
            medium = newMedium;
            logo = newLogo;
            bakedScale = scale;
            loaded = true;
            attempts = 0; // budget is per bake, so a later rescale still gets its own retries
        } catch (Throwable t) {
            // Preserve existing atlases; later frames may retry a transient GL failure.
            t.printStackTrace();
        }
    }

    private static void dispose(CustomFont... fonts) {
        for (CustomFont font : fonts) {
            if (font != null) {
                font.dispose();
            }
        }
    }

    public static boolean isLoaded() {
        return loaded;
    }

    private static Font loadBase() {
        try (InputStream in = Fonts.class.getResourceAsStream(FONT_PATH)) {
            if (in != null) {
                Font f = Font.createFont(Font.TRUETYPE_FONT, in);
                GraphicsEnvironment.getLocalGraphicsEnvironment().registerFont(f);
                return f;
            }
        } catch (Exception ignored) {
        }
        return new Font("SansSerif", Font.BOLD, 16);
    }
}
