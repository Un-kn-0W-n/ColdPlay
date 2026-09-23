package coldplay.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.shader.ShaderLinkHelper;
import net.minecraft.client.shader.ShaderLoader;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL20;

/** Anti-aliased rounded shapes for glass HUD panels, with a soft shadow and a blurred copy of the frame behind them. */
public final class GlassShader {

    private static final Logger logger = LogManager.getLogger();

    private static final int FLAT = 0;
    private static final int BLUR = 1;
    private static final int IMAGE = 2;

    private static final String VERTEX_SRC = "#version 120\n"
            + "uniform vec2 origin;\n"
            + "varying vec2 local;\n"
            + "void main() {\n"
            + "    local = gl_Vertex.xy - origin;\n"
            + "    gl_Position = gl_ModelViewProjectionMatrix * gl_Vertex;\n"
            + "}\n";

    private static final String FRAGMENT_SRC = "#version 120\n"
            + "uniform vec2 size;\n"
            + "uniform float radius;\n"
            + "uniform vec4 color0;\n"
            + "uniform vec4 color1;\n"
            + "uniform vec2 gradient;\n"
            + "uniform vec4 border;\n"
            + "uniform float shadow;\n"
            + "uniform int mode;\n"
            + "uniform vec4 uv;\n"
            + "uniform vec2 screen;\n"
            + "uniform sampler2D image;\n"
            + "varying vec2 local;\n"
            + "float box(vec2 p) {\n"
            + "    vec2 q = abs(p - size * 0.5) - size * 0.5 + radius;\n"
            + "    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - radius;\n"
            + "}\n"
            + "vec3 backdrop() {\n"
            + "    vec3 sum = vec3(0.0);\n"
            + "    float total = 0.0;\n"
            + "    for (int x = -5; x <= 5; x++) {\n"
            + "        for (int y = -5; y <= 5; y++) {\n"
            + "            vec2 o = vec2(float(x), float(y)) * 2.0;\n"
            + "            float w = exp(-dot(o, o) / 72.0);\n"
            // the half-pixel offset makes each linear tap average a 2x2 block
            + "            sum += texture2D(image, (gl_FragCoord.xy + o + 0.5) / screen).rgb * w;\n"
            + "            total += w;\n"
            + "        }\n"
            + "    }\n"
            + "    return sum / total;\n"
            + "}\n"
            + "void main() {\n"
            + "    float d = box(local);\n"
            + "    float aa = fwidth(d);\n"
            + "    float cover = clamp(0.5 - d / aa, 0.0, 1.0);\n"
            + "    float inner = clamp(0.5 - (d + aa) / aa, 0.0, 1.0);\n"
            + "    vec2 t = local / size;\n"
            + "    vec4 fill = mix(color0, color1, clamp(dot(t, gradient), 0.0, 1.0));\n"
            + "    if (mode == 1) {\n"
            + "        fill = vec4(mix(backdrop(), fill.rgb, fill.a), 1.0);\n"
            + "    } else if (mode == 2) {\n"
            + "        fill *= texture2D(image, mix(uv.xy, uv.zw, clamp(t, 0.0, 0.999)));\n"
            + "    }\n"
            // one device pixel rim, brighter along the top like light on glass
            + "    float rim = border.a * (1.0 - inner) * (1.0 - 0.55 * clamp(t.y, 0.0, 1.0));\n"
            + "    fill = vec4(mix(fill.rgb, border.rgb, rim), fill.a + (1.0 - fill.a) * rim);\n"
            + "    float body = fill.a * cover;\n"
            + "    float shade = 0.0;\n"
            + "    if (shadow > 0.0) {\n"
            + "        float s = clamp(1.0 - box(local - vec2(0.0, 1.0)) / shadow, 0.0, 1.0);\n"
            + "        shade = 0.45 * s * s;\n"
            + "    }\n"
            + "    float alpha = body + shade * (1.0 - body);\n"
            + "    gl_FragColor = vec4(fill.rgb * body / max(alpha, 0.0001), alpha);\n"
            + "}\n";

    private static int program;
    private static boolean failed;
    private static int backdrop;
    private static int backdropW;
    private static int backdropH;

    private GlassShader() {
    }

    /** Blurs what is already drawn under the panel and tints it, fading from {@code top} to {@code bottom}. */
    public static void panel(float x, float y, float w, float h, float r, int top, int bottom, int rim, float shadow) {
        if (use()) {
            copyBackdrop();
            draw(x, y, w, h, r, top, bottom, true, rim, shadow, BLUR);
        }
    }

    /** Flat fill fading from {@code left} to {@code right}. */
    public static void rect(float x, float y, float w, float h, float r, int left, int right) {
        if (w > 0.0F && use()) {
            draw(x, y, w, h, r, left, right, false, 0, 0.0F, FLAT);
        }
    }

    /** The (u0, v0)-(u1, v1) region of the bound texture, tinted. */
    public static void image(float x, float y, float w, float h, float r,
                             float u0, float v0, float u1, float v1, int tint) {
        if (use()) {
            GL20.glUniform4f(GL20.glGetUniformLocation(program, "uv"), u0, v0, u1, v1);
            draw(x, y, w, h, r, tint, tint, false, 0, 0.0F, IMAGE);
        }
    }

    private static boolean use() {
        if (program == 0 && !failed) {
            compile();
        }
        if (program == 0) {
            return false;
        }
        GL20.glUseProgram(program);
        return true;
    }

    private static void draw(float x, float y, float w, float h, float r, int c0, int c1, boolean vertical,
                             int rim, float shadow, int mode) {
        Minecraft mc = Minecraft.getMinecraft();
        GL20.glUniform2f(uniform("origin"), x, y);
        GL20.glUniform2f(uniform("size"), w, h);
        GL20.glUniform1f(uniform("radius"), Math.min(r, Math.min(w, h) / 2.0F));
        color("color0", c0);
        color("color1", c1);
        GL20.glUniform2f(uniform("gradient"), vertical ? 0.0F : 1.0F, vertical ? 1.0F : 0.0F);
        color("border", rim);
        GL20.glUniform1f(uniform("shadow"), shadow);
        GL20.glUniform1i(uniform("mode"), mode);
        GL20.glUniform2f(uniform("screen"), mc.displayWidth, mc.displayHeight);

        GlStateManager.enableBlend();
        GlStateManager.disableAlpha(); // the shadow fades below the alpha test cutoff
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        float m = shadow + 1.0F; // room for the shadow and the edge fade
        Tessellator tessellator = Tessellator.getInstance();
        WorldRenderer wr = tessellator.getWorldRenderer();
        wr.begin(7, DefaultVertexFormats.POSITION);
        wr.pos(x - m, y + h + m, 0.0D).endVertex();
        wr.pos(x + w + m, y + h + m, 0.0D).endVertex();
        wr.pos(x + w + m, y - m, 0.0D).endVertex();
        wr.pos(x - m, y - m, 0.0D).endVertex();
        tessellator.draw();
        GL20.glUseProgram(0);
        GlStateManager.enableAlpha();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
    }

    private static int uniform(String name) {
        return GL20.glGetUniformLocation(program, name);
    }

    private static void color(String name, int argb) {
        GL20.glUniform4f(uniform(name), (argb >> 16 & 0xFF) / 255.0F, (argb >> 8 & 0xFF) / 255.0F,
                (argb & 0xFF) / 255.0F, (argb >>> 24) / 255.0F);
    }

    /** Copies the frame drawn so far; the shader cannot sample the framebuffer it is drawing into. */
    private static void copyBackdrop() {
        Minecraft mc = Minecraft.getMinecraft();
        if (backdrop == 0) {
            backdrop = GL11.glGenTextures();
            GlStateManager.bindTexture(backdrop);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        }
        GlStateManager.bindTexture(backdrop);
        if (mc.displayWidth != backdropW || mc.displayHeight != backdropH) {
            backdropW = mc.displayWidth;
            backdropH = mc.displayHeight;
            GL11.glCopyTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGB8, 0, 0, backdropW, backdropH, 0);
        } else {
            GL11.glCopyTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, 0, 0, backdropW, backdropH);
        }
    }

    private static void compile() {
        int vertex = 0;
        int fragment = 0;
        int prog = 0;

        try {
            vertex = ShaderLoader.compileSource(
                    ShaderLoader.ShaderType.VERTEX, "coldplay/glass.vsh", VERTEX_SRC);
            fragment = ShaderLoader.compileSource(
                    ShaderLoader.ShaderType.FRAGMENT, "coldplay/glass.fsh", FRAGMENT_SRC);
            if (ShaderLinkHelper.getStaticShaderLinkHelper() == null) {
                ShaderLinkHelper.setNewStaticShaderLinkHelper();
            }
            ShaderLinkHelper linker = ShaderLinkHelper.getStaticShaderLinkHelper();
            prog = linker.createProgram();
            linker.linkProgram(prog, vertex, fragment, "coldplay/glass.vsh", "coldplay/glass.fsh");
            program = prog;
            prog = 0; // keep finally from deleting the linked program
        } catch (Exception e) {
            failed = true;
            logger.error("Glass shader unavailable", e);
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
}
