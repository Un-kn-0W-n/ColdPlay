package coldplay.util.font;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;

import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.io.InputStream;

/** Bakes the shared font atlases lazily on the render thread, falling back to an AWT logical font. */
public final class Fonts {

    private static final String FONT_PATH = "/assets/minecraft/coldplay/fonts/hud.ttf";

    private static final float LIST_SIZE = 10f;
    private static final float TITLE_SIZE = 15f;
    private static final float MEDIUM_SIZE = 11f;
    private static final float LOGO_SIZE = 34f;

    public static CustomFont title;
    public static CustomFont list;
    public static CustomFont medium;
    public static CustomFont logo;

    private static final int MAX_ATTEMPTS = 10;

    private static final int MAX_SUPERSAMPLE = 4; // keeps the logo atlas under 4096px

    private static int attempts;
    private static boolean loaded;
    private static int bakedScale;

    private Fonts() {
    }

    /** Idempotent; call every frame from the render thread. Rebakes when the capped GUI scale changes. */
    public static void load() {
        load(new ScaledResolution(Minecraft.getMinecraft()).getScaleFactor());
    }

    /** Pass the frame's {@link ScaledResolution} scale so the early-out allocates nothing. */
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
            attempts = 0; // retry budget is per bake
        } catch (Throwable t) {
            // Keep the old atlases; a later frame retries.
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
