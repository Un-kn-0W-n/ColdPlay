package coldplay.gui;

import coldplay.util.font.Fonts;
import coldplay.util.font.CustomFont;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.shader.ShaderLinkHelper;
import net.minecraft.client.shader.ShaderLoader;
import net.minecraft.util.ColorMath;
import net.minecraft.util.MathHelper;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.BufferUtils;

import java.nio.FloatBuffer;

/**
 * Animated splash/menu backdrop. The GLSL is inlined because it is needed before resources load;
 * if it fails to compile a plain animated fallback is drawn instead.
 */
public final class BackgroundShader {

    private static final Logger logger = LogManager.getLogger();

    public static final int ICE = 0xFFE8F0F8;

    public static final int MIST = 0xFF8294A8;

    private static final String VERTEX_SRC = "#version 120\n"
            + "void main() {\n"
            + "    gl_Position = gl_ModelViewProjectionMatrix * gl_Vertex;\n"
            + "}\n";

    private static final String FRAGMENT_SRC = "#version 120\n"
            + "uniform float time;\n"
            + "uniform vec2 resolution;\n"
            + "float hash(vec2 p) {\n"
            + "    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);\n"
            + "}\n"
            + "float noise(vec2 p) {\n"
            + "    vec2 i = floor(p);\n"
            + "    vec2 f = fract(p);\n"
            + "    vec2 u = f * f * (3.0 - 2.0 * f);\n"
            + "    return mix(mix(hash(i), hash(i + vec2(1.0, 0.0)), u.x),\n"
            + "               mix(hash(i + vec2(0.0, 1.0)), hash(i + vec2(1.0, 1.0)), u.x), u.y);\n"
            + "}\n"
            + "float fbm(vec2 p) {\n"
            + "    return 0.5000 * noise(p)\n"
            + "         + 0.2500 * noise(p * 2.03 + 11.7)\n"
            + "         + 0.1250 * noise(p * 4.07 + 31.1)\n"
            + "         + 0.0625 * noise(p * 8.11 + 67.4);\n"
            + "}\n"
            + "void main() {\n"
            + "    vec2 uv = gl_FragCoord.xy / resolution;\n"
            + "    float aspect = resolution.x / max(resolution.y, 1.0);\n"
            + "    vec2 p = vec2((uv.x - 0.5) * aspect, uv.y - 0.5);\n"
            + "    float t = time * 0.045;\n"
            + "    vec2 warp = vec2(fbm(p * 1.18 + vec2(t, -t * 0.42)),\n"
            + "                     fbm(p * 1.34 + vec2(-t * 0.31, t * 0.57)));\n"
            + "    float current = fbm(p * 1.62 + (warp - 0.5) * 1.55 + vec2(t * 0.18, -t * 0.11));\n"
            + "    float ridge = 1.0 - abs(current * 2.0 - 1.0);\n"
            + "    ridge = pow(clamp(ridge, 0.0, 1.0), 7.0);\n"
            + "    float deepFlow = fbm(p * 0.72 - vec2(t * 0.12, t * 0.06));\n"
            + "    float sweep = exp(-10.0 * pow(p.x + p.y * 0.34 - sin(time * 0.224) * 0.6, 2.0));\n"
            + "    float veil = smoothstep(0.30, 0.76, current);\n"
            + "    vec3 abyss = vec3(0.010, 0.022, 0.044);\n"
            + "    vec3 navy = vec3(0.022, 0.085, 0.150);\n"
            + "    vec3 frost = vec3(0.470, 0.900, 1.000);\n"
            + "    vec3 col = mix(abyss, navy, 0.26 + deepFlow * 0.40);\n"
            + "    col += navy * veil * 0.16;\n"
            + "    col += frost * ridge * (0.16 + deepFlow * 0.28);\n"
            + "    col += frost * sweep * (0.055 + ridge * 0.12);\n"
            + "    col += navy * smoothstep(0.12, 0.95, 1.0 - uv.y) * 0.20;\n"
            + "    vec2 v = uv - 0.5;\n"
            + "    col *= 0.94 - dot(v, v) * 0.74;\n"
            + "    col = pow(max(col, vec3(0.0)), vec3(0.95));\n"
            + "    col += (hash(gl_FragCoord.xy + floor(time * 24.0)) - 0.5) / 210.0;\n"
            + "    gl_FragColor = vec4(col, 1.0);\n"
            + "}\n";

    private static int program;
    private static boolean failed;
    private static int timeLocation;
    private static int resolutionLocation;
    private static final FloatBuffer timeBuffer = BufferUtils.createFloatBuffer(1);
    private static final FloatBuffer resolutionBuffer = BufferUtils.createFloatBuffer(2);

    private BackgroundShader() {
    }

    /** pixelWidth/pixelHeight are the framebuffer's real pixel size, not GUI units; gl_FragCoord is in pixels. */
    public static void draw(int guiWidth, int guiHeight, int pixelWidth, int pixelHeight) {
        if (program == 0 && !failed) {
            compile();
        }
        if (program == 0) {
            drawFallback(guiWidth, guiHeight);
            return;
        }
        OpenGlHelper.glUseProgram(program);
        // wraps hourly to keep float precision
        timeBuffer.clear();
        timeBuffer.put((Minecraft.getSystemTime() % 3600000L) / 1000.0F).flip();
        OpenGlHelper.glUniform1(timeLocation, timeBuffer);
        resolutionBuffer.clear();
        resolutionBuffer.put(pixelWidth).put(pixelHeight).flip();
        OpenGlHelper.glUniform2(resolutionLocation, resolutionBuffer);
        GlStateManager.disableTexture2D();
        Tessellator tessellator = Tessellator.getInstance();
        WorldRenderer worldrenderer = tessellator.getWorldRenderer();
        worldrenderer.begin(7, DefaultVertexFormats.POSITION);
        worldrenderer.pos(0.0D, guiHeight, 0.0D).endVertex();
        worldrenderer.pos(guiWidth, guiHeight, 0.0D).endVertex();
        worldrenderer.pos(guiWidth, 0.0D, 0.0D).endVertex();
        worldrenderer.pos(0.0D, 0.0D, 0.0D).endVertex();
        tessellator.draw();
        GlStateManager.enableTexture2D();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        OpenGlHelper.glUseProgram(0);
    }

    public static void drawWordmark(float centerX, float centerY) {
        drawWordmark(centerX, centerY, 1.0F);
    }

    public static void drawWordmark(float centerX, float centerY, float scale) {
        if (!Fonts.isLoaded()) {
            return;
        }
        final String cold = "COLD";
        final String play = "PLAY";
        final float tracking = 1.25F;
        final float coldWidth = trackedWidth(Fonts.logo, cold, tracking);
        final float playWidth = trackedWidth(Fonts.logo, play, tracking);
        final float totalWidth = coldWidth + playWidth + 2.0F;
        GlStateManager.pushMatrix();
        GlStateManager.translate(centerX, centerY, 0.0F);
        GlStateManager.scale(scale, scale, 1.0F);
        final float x = -totalWidth / 2.0F;
        final float y = -Fonts.logo.getHeight() / 2.0F;
        drawTrackedString(Fonts.logo, cold, x, y, ICE, tracking);
        drawTrackedString(Fonts.logo, play, x + coldWidth + 2.0F, y, Theme.FROST, tracking);
        final int railY = Math.round(y + Fonts.logo.getHeight() + 4.0F);
        final int railStart = Math.round(x + coldWidth + 2.0F);
        final int railEnd = Math.round(x + totalWidth);
        Gui.drawRect(railStart, railY, railEnd, railY + 2, Theme.FROST);
        Gui.drawRect(railEnd - 2, railY - 3, railEnd, railY + 2, ICE);
        GlStateManager.popMatrix();
    }

    private static float trackedWidth(CustomFont font, String text, float tracking) {
        float width = 0.0F;
        for (int i = 0; i < text.length(); i++) {
            width += font.getStringWidth(String.valueOf(text.charAt(i)));
            if (i + 1 < text.length()) {
                width += tracking;
            }
        }
        return width;
    }

    private static void drawTrackedString(CustomFont font, String text, float x, float y, int color, float tracking) {
        float cursor = x;
        for (int i = 0; i < text.length(); i++) {
            final String character = String.valueOf(text.charAt(i));
            font.drawStringWithShadow(character, cursor, y, color);
            cursor += font.getStringWidth(character) + tracking;
        }
    }

    private static void compile() {
        int vertex = 0;
        int fragment = 0;
        int prog = 0;

        try {
            vertex = ShaderLoader.compileSource(
                    ShaderLoader.ShaderType.VERTEX, "coldplay/background.vsh", VERTEX_SRC);
            fragment = ShaderLoader.compileSource(
                    ShaderLoader.ShaderType.FRAGMENT, "coldplay/background.fsh", FRAGMENT_SRC);
            if (ShaderLinkHelper.getStaticShaderLinkHelper() == null) {
                ShaderLinkHelper.setNewStaticShaderLinkHelper();
            }
            ShaderLinkHelper linker = ShaderLinkHelper.getStaticShaderLinkHelper();
            prog = linker.createProgram();
            linker.linkProgram(prog, vertex, fragment,
                    "coldplay/background.vsh", "coldplay/background.fsh");
            timeLocation = OpenGlHelper.glGetUniformLocation(prog, "time");
            resolutionLocation = OpenGlHelper.glGetUniformLocation(prog, "resolution");
            program = prog;
            prog = 0; // keep finally from deleting the linked program
        } catch (Exception e) {
            failed = true;
            logger.error("Background shader unavailable, using visible fallback", e);
        } finally {
            if (vertex != 0) {
                OpenGlHelper.glDeleteShader(vertex);
            }
            if (fragment != 0) {
                OpenGlHelper.glDeleteShader(fragment);
            }
            if (prog != 0) {
                OpenGlHelper.glDeleteProgram(prog);
            }
        }
    }

    private static void drawFallback(int guiWidth, int guiHeight) {
        final int bands = MathHelper.clamp_int(guiHeight / 7, 18, 56);
        final float phase = (Minecraft.getSystemTime() % 24000L) / 24000.0F;
        for (int i = 0; i < bands; i++) {
            final float y0 = i / (float) bands;
            final float y1 = (i + 1) / (float) bands;
            final float current = 0.5F + 0.5F * (float) Math.sin(i * 0.47F + phase * Math.PI * 2.0F);
            final int base = ColorMath.lerpArgb(0xFF0A1A2C, 0xFF040810, y0);
            final int color = ColorMath.lerpArgb(base, 0xFF16414E, 0.10F + current * 0.14F);
            Gui.drawRect(0, Math.round(y0 * guiHeight), guiWidth, Math.round(y1 * guiHeight) + 1, color);
        }

        final int half = Math.max(70, guiWidth / 5);
        final int travel = guiWidth + half * 2;
        final int center = Math.round(phase * travel) - half;
        for (int i = 6; i >= 1; i--) {
            final int width = half * i / 3;
            final int alpha = 3 + (7 - i) * 3;
            Gui.drawRect(center - width, 0, center + width, guiHeight,
                    Theme.withAlpha(0xFF4FBDD0, alpha));
        }
    }

}
