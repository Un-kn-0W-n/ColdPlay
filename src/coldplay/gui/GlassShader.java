package coldplay.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
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
import org.lwjgl.opengl.GL30;

/** Anti-aliased rounded shapes for glass panels, with a soft shadow and a blurred copy of the frame behind them. */
public final class GlassShader {

    private static final Logger logger = LogManager.getLogger();

    private static final int FLAT = 0;
    private static final int BLUR = 1;
    private static final int IMAGE = 2;
    private static final int ARC = 3;
    private static final int POLYLINE = 4;
    private static final int TRIANGLE = 5;
    private static final int ELLIPSE = 6;

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
            + "uniform float rimFade;\n"
            + "uniform float shadow;\n"
            + "uniform float shadowOffset;\n"
            + "uniform float shadowAlpha;\n"
            + "uniform int mode;\n"
            + "uniform vec4 uv;\n"
            + "uniform vec2 screen;\n"
            + "uniform float blurStep;\n"
            + "uniform float lod;\n"
            + "uniform float saturation;\n"
            + "uniform vec3 arc;\n"
            + "uniform vec2 pointA;\n"
            + "uniform vec2 pointB;\n"
            + "uniform vec2 pointC;\n"
            + "uniform vec4 color2;\n"
            + "uniform float mid;\n"
            + "uniform float opacity;\n"
            + "uniform sampler2D image;\n"
            + "varying vec2 local;\n"
            + "float box(vec2 p) {\n"
            + "    vec2 q = abs(p - size * 0.5) - size * 0.5 + radius;\n"
            + "    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - radius;\n"
            + "}\n"
            // distance to the segment ab; t is how far along it the nearest point lies
            + "float segment(vec2 p, vec2 a, vec2 b, out float t) {\n"
            + "    vec2 pa = p - a, ba = b - a;\n"
            + "    t = clamp(dot(pa, ba) / max(dot(ba, ba), 0.000001), 0.0, 1.0);\n"
            + "    return length(pa - ba * t);\n"
            + "}\n"
            + "float triangle(vec2 p, vec2 a, vec2 b, vec2 c) {\n"
            + "    vec2 e0 = b - a, e1 = c - b, e2 = a - c;\n"
            + "    vec2 v0 = p - a, v1 = p - b, v2 = p - c;\n"
            + "    vec2 q0 = v0 - e0 * clamp(dot(v0, e0) / dot(e0, e0), 0.0, 1.0);\n"
            + "    vec2 q1 = v1 - e1 * clamp(dot(v1, e1) / dot(e1, e1), 0.0, 1.0);\n"
            + "    vec2 q2 = v2 - e2 * clamp(dot(v2, e2) / dot(e2, e2), 0.0, 1.0);\n"
            + "    float s = sign(e0.x * e2.y - e0.y * e2.x);\n"
            + "    vec2 d = min(min(vec2(dot(q0, q0), s * (v0.x * e0.y - v0.y * e0.x)),\n"
            + "            vec2(dot(q1, q1), s * (v1.x * e1.y - v1.y * e1.x))),\n"
            + "            vec2(dot(q2, q2), s * (v2.x * e2.y - v2.y * e2.x)));\n"
            + "    return -sqrt(d.x) * sign(d.y);\n"
            + "}\n"
            // close to the true distance near the edge, which is all the anti-aliasing needs
            + "float ellipse(vec2 p, vec2 r) {\n"
            + "    float k0 = length(p / r);\n"
            + "    return k0 * (k0 - 1.0) / max(length(p / (r * r)), 0.00001);\n"
            + "}\n"
            // distance along the outline, clockwise from the top center; e is the straight half length of each side
            + "float along(vec2 p, vec2 e, float r) {\n"
            + "    float q = 1.5707963 * r;\n"
            + "    if (abs(p.x) > e.x && abs(p.y) > e.y) {\n"
            + "        if (p.x > 0.0 && p.y < 0.0) return e.x + r * atan(p.x - e.x, -p.y - e.y);\n"
            + "        if (p.x > 0.0) return e.x + q + 2.0 * e.y + r * atan(p.y - e.y, p.x - e.x);\n"
            + "        if (p.y > 0.0) return 3.0 * e.x + 2.0 * q + 2.0 * e.y + r * atan(-p.x - e.x, p.y - e.y);\n"
            + "        return 3.0 * e.x + 3.0 * q + 4.0 * e.y + r * atan(-p.y - e.y, -p.x - e.x);\n"
            + "    }\n"
            + "    if (abs(p.x) > e.x) {\n"
            + "        return p.x > 0.0 ? e.x + q + e.y + p.y : 3.0 * e.x + 3.0 * q + 3.0 * e.y - p.y;\n"
            + "    }\n"
            + "    if (p.y > 0.0) return 2.0 * e.x + 2.0 * q + 2.0 * e.y - p.x;\n"
            + "    return p.x >= 0.0 ? p.x : 4.0 * (e.x + q + e.y) + p.x;\n"
            + "}\n"
            + "vec3 backdrop() {\n"
            + "    vec3 sum = vec3(0.0);\n"
            + "    float total = 0.0;\n"
            + "    for (int x = -4; x <= 4; x++) {\n"
            + "        for (int y = -4; y <= 4; y++) {\n"
            + "            vec2 k = vec2(float(x), float(y));\n"
            + "            float w = exp(-dot(k, k) / 18.0);\n"
            // the bias reads a mip level already averaged over about one tap spacing
            + "            sum += texture2D(image, (gl_FragCoord.xy + k * blurStep) / screen, lod).rgb * w;\n"
            + "            total += w;\n"
            + "        }\n"
            + "    }\n"
            + "    vec3 c = sum / total;\n"
            + "    return mix(vec3(dot(c, vec3(0.2126, 0.7152, 0.0722))), c, saturation);\n"
            + "}\n"
            + "void main() {\n"
            + "    float d = box(local);\n"
            + "    float aa = fwidth(d);\n"
            + "    if (mode == 3) {\n"
            + "        vec2 e = size * 0.5 - radius;\n"
            + "        float len = 4.0 * (e.x + e.y + 1.5707963 * radius);\n"
            + "        float s = along(local - size * 0.5, e, radius) / len;\n"
            // past either end, measure to the end point so the caps come out round; to may pass 1 and wrap
            + "        float ds = mod(s - arc.y, 1.0);\n"
            + "        float gap = ds > arc.z - arc.y ? min(1.0 - ds, ds - (arc.z - arc.y)) * len : 0.0;\n"
            + "        float line = clamp(0.5 - (length(vec2(gap, d)) - arc.x) / aa, 0.0, 1.0);\n"
            + "        gl_FragColor = vec4(color0.rgb, color0.a * line);\n"
            + "        return;\n"
            + "    }\n"
            + "    if (mode == 4) {\n"
            + "        float t;\n"
            + "        float unused;\n"
            + "        float e = min(segment(local, pointA, pointB, t), segment(local, pointB, pointC, unused)) - arc.x;\n"
            + "        vec4 c = t < mid ? mix(color0, color2, t / max(mid, 0.0001))\n"
            + "                : mix(color2, color1, (t - mid) / max(1.0 - mid, 0.0001));\n"
            + "        gl_FragColor = vec4(c.rgb, c.a * clamp(0.5 - e / fwidth(e), 0.0, 1.0));\n"
            + "        return;\n"
            + "    }\n"
            + "    if (mode >= 5) {\n"
            + "        float e = mode == 5 ? triangle(local, pointA, pointB, pointC) : ellipse(local - size * 0.5, size * 0.5);\n"
            + "        float ae = fwidth(e);\n"
            // the outline is centered on the edge and drawn over the fill
            + "        float fillA = color0.a * clamp(0.5 - e / ae, 0.0, 1.0);\n"
            + "        float strokeA = arc.x > 0.0 ? color1.a * clamp(0.5 - (abs(e) - arc.x) / ae, 0.0, 1.0) : 0.0;\n"
            + "        float a = strokeA + fillA * (1.0 - strokeA);\n"
            + "        gl_FragColor = vec4((color1.rgb * strokeA + color0.rgb * fillA * (1.0 - strokeA)) / max(a, 0.0001), a);\n"
            + "        return;\n"
            + "    }\n"
            + "    float cover = clamp(0.5 - d / aa, 0.0, 1.0);\n"
            + "    float inner = clamp(0.5 - (d + aa) / aa, 0.0, 1.0);\n"
            + "    vec2 t = local / size;\n"
            + "    vec4 fill = mix(color0, color1, clamp(dot(t, gradient), 0.0, 1.0));\n"
            + "    if (mode == 1) {\n"
            + "        fill = vec4(mix(backdrop(), fill.rgb, fill.a), opacity);\n"
            + "    } else if (mode == 2) {\n"
            + "        fill *= texture2D(image, mix(uv.xy, uv.zw, clamp(t, 0.0, 0.999)));\n"
            + "    }\n"
            // one device pixel rim, brighter along the top like light on glass
            + "    float rim = border.a * (1.0 - inner) * (1.0 - rimFade * clamp(t.y, 0.0, 1.0));\n"
            + "    fill = vec4(mix(fill.rgb, border.rgb, rim), fill.a + (1.0 - fill.a) * rim);\n"
            + "    float body = fill.a * cover;\n"
            + "    float shade = 0.0;\n"
            + "    if (shadow > 0.0) {\n"
            + "        float s = clamp(1.0 - box(local - vec2(0.0, shadowOffset)) / shadow, 0.0, 1.0);\n"
            + "        shade = shadowAlpha * s * s;\n"
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

    /** Blurs what is already drawn under the panel and tints it with the glass. */
    public static void panel(float x, float y, float w, float h, float r, Glass glass) {
        capture();
        frost(x, y, w, h, r, glass);
    }

    /** Copies the frame once for a run of {@link #frost} panels that do not overlap. */
    public static void capture() {
        copyBackdrop();
    }

    /** {@link #panel} over the frame taken by the last {@link #capture()}. */
    public static void frost(float x, float y, float w, float h, float r, Glass glass) {
        frost(x, y, w, h, r, glass, 1.0F);
    }

    /** {@link #frost} faded to {@code opacity}, backdrop, rim and shadow alike. */
    public static void frost(float x, float y, float w, float h, float r, Glass glass, float opacity) {
        if (use()) {
            GlStateManager.bindTexture(backdrop); // text drawn since the capture rebinds unit 0
            blurUniforms(glass);
            GL20.glUniform1f(uniform("opacity"), opacity);
            int rim = Math.round((glass.rim >>> 24) * opacity) << 24 | glass.rim & 0x00FFFFFF;
            draw(x, y, w, h, r, glass.top, glass.bottom, true, rim, 0.55F,
                    glass.shadow, glass.shadowOffset, glass.shadowAlpha * opacity, BLUR);
        }
    }

    /** Flat fill fading from {@code left} to {@code right}. */
    public static void rect(float x, float y, float w, float h, float r, int left, int right) {
        if (w > 0.0F && use()) {
            draw(x, y, w, h, r, left, right, false, 0, 0.0F, 0.0F, 0.0F, 0.0F, FLAT);
        }
    }

    /** Flat fill with a soft drop shadow. */
    public static void fill(float x, float y, float w, float h, float r, int color,
                            float shadow, float shadowOffset, float shadowAlpha) {
        if (w > 0.0F && use()) {
            draw(x, y, w, h, r, color, color, false, 0, 0.0F, shadow, shadowOffset, shadowAlpha, FLAT);
        }
    }

    /** One device pixel outline, even all the way round. */
    public static void stroke(float x, float y, float w, float h, float r, int color) {
        if (w > 0.0F && use()) {
            draw(x, y, w, h, r, color & 0x00FFFFFF, color & 0x00FFFFFF, false, color, 0.0F, 0.0F, 0.0F, 0.0F, FLAT);
        }
    }

    /**
     * A {@code width} line centered on the rounded outline, covering {@code from} to {@code to}
     * of the way round clockwise from the top center, with round ends. {@code to} may pass 1 to wrap past the top.
     */
    public static void arc(float x, float y, float w, float h, float r, float width, float from, float to, int color) {
        if (to > from && use()) {
            GL20.glUniform3f(uniform("arc"), width / 2.0F, from, to);
            // shadow is unused in this mode, it only pads the quad for the line's outer half
            draw(x, y, w, h, r, color, color, false, 0, 0.0F, width, 0.0F, 0.0F, ARC);
        }
    }

    /** A {@code width} line from a to b with round ends, in one color. */
    public static void line(float ax, float ay, float bx, float by, float width, int color) {
        polyline(ax, ay, bx, by, bx, by, width, color, color, 0.5F, color);
    }

    /** A {@code width} line from a to b with round ends, fading from {@code start} through {@code middle} at {@code mid} to {@code end}. */
    public static void line(float ax, float ay, float bx, float by, float width, int start, int middle, float mid, int end) {
        polyline(ax, ay, bx, by, bx, by, width, start, middle, mid, end);
    }

    /** Two joined segments, a to b to c, with round ends and a round join that does not double up. */
    public static void polyline(float ax, float ay, float bx, float by, float cx, float cy, float width, int color) {
        polyline(ax, ay, bx, by, cx, cy, width, color, color, 0.5F, color);
    }

    private static void polyline(float ax, float ay, float bx, float by, float cx, float cy, float width,
                                 int start, int middle, float mid, int end) {
        if (use()) {
            float x = Math.min(ax, Math.min(bx, cx)), y = Math.min(ay, Math.min(by, cy));
            points(ax - x, ay - y, bx - x, by - y, cx - x, cy - y);
            color("color2", middle);
            GL20.glUniform1f(uniform("mid"), mid);
            GL20.glUniform3f(uniform("arc"), width / 2.0F, 0.0F, 0.0F);
            draw(x, y, Math.max(ax, Math.max(bx, cx)) - x, Math.max(ay, Math.max(by, cy)) - y, 0.0F,
                    start, end, false, 0, 0.0F, width / 2.0F, 0.0F, 0.0F, POLYLINE);
        }
    }

    /** A filled triangle with a {@code strokeWidth} outline centered on its edges; pass 0 for no outline. */
    public static void triangle(float ax, float ay, float bx, float by, float cx, float cy,
                                int fill, int stroke, float strokeWidth) {
        if (use()) {
            float x = Math.min(ax, Math.min(bx, cx)), y = Math.min(ay, Math.min(by, cy));
            points(ax - x, ay - y, bx - x, by - y, cx - x, cy - y);
            GL20.glUniform3f(uniform("arc"), strokeWidth / 2.0F, 0.0F, 0.0F);
            draw(x, y, Math.max(ax, Math.max(bx, cx)) - x, Math.max(ay, Math.max(by, cy)) - y, 0.0F,
                    fill, stroke, false, 0, 0.0F, strokeWidth / 2.0F, 0.0F, 0.0F, TRIANGLE);
        }
    }

    /** A filled ellipse with a {@code strokeWidth} outline centered on its edge; pass 0 for no outline. */
    public static void ellipse(float cx, float cy, float rx, float ry, int fill, int stroke, float strokeWidth) {
        if (use()) {
            GL20.glUniform3f(uniform("arc"), strokeWidth / 2.0F, 0.0F, 0.0F);
            draw(cx - rx, cy - ry, rx * 2.0F, ry * 2.0F, 0.0F, fill, stroke, false, 0, 0.0F,
                    strokeWidth / 2.0F, 0.0F, 0.0F, ELLIPSE);
        }
    }

    private static void points(float ax, float ay, float bx, float by, float cx, float cy) {
        GL20.glUniform2f(uniform("pointA"), ax, ay);
        GL20.glUniform2f(uniform("pointB"), bx, by);
        GL20.glUniform2f(uniform("pointC"), cx, cy);
    }

    /** The (u0, v0)-(u1, v1) region of the bound texture, tinted. */
    public static void image(float x, float y, float w, float h, float r,
                             float u0, float v0, float u1, float v1, int tint) {
        if (use()) {
            GL20.glUniform4f(uniform("uv"), u0, v0, u1, v1);
            draw(x, y, w, h, r, tint, tint, false, 0, 0.0F, 0.0F, 0.0F, 0.0F, IMAGE);
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

    /** Taps spaced a third of the sigma apart, read from the mip level that averages about one spacing. */
    private static void blurUniforms(Glass glass) {
        int scale = new ScaledResolution(Minecraft.getMinecraft()).getScaleFactor();
        float step = Math.max(1.0F, glass.blur * scale / 3.0F);
        GL20.glUniform1f(uniform("blurStep"), step);
        GL20.glUniform1f(uniform("lod"), (float) (Math.log(step) / Math.log(2.0)));
        GL20.glUniform1f(uniform("saturation"), glass.saturation);
    }

    private static void draw(float x, float y, float w, float h, float r, int c0, int c1, boolean vertical,
                             int rim, float rimFade, float shadow, float shadowOffset, float shadowAlpha, int mode) {
        Minecraft mc = Minecraft.getMinecraft();
        GL20.glUniform2f(uniform("origin"), x, y);
        GL20.glUniform2f(uniform("size"), w, h);
        GL20.glUniform1f(uniform("radius"), Math.min(r, Math.min(w, h) / 2.0F));
        color("color0", c0);
        color("color1", c1);
        GL20.glUniform2f(uniform("gradient"), vertical ? 0.0F : 1.0F, vertical ? 1.0F : 0.0F);
        color("border", rim);
        GL20.glUniform1f(uniform("rimFade"), rimFade);
        GL20.glUniform1f(uniform("shadow"), shadow);
        GL20.glUniform1f(uniform("shadowOffset"), shadowOffset);
        GL20.glUniform1f(uniform("shadowAlpha"), shadowAlpha);
        GL20.glUniform1i(uniform("mode"), mode);
        GL20.glUniform2f(uniform("screen"), mc.displayWidth, mc.displayHeight);

        GlStateManager.enableBlend();
        GlStateManager.disableAlpha(); // the shadow fades below the alpha test cutoff
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        float m = shadow + Math.abs(shadowOffset) + 1.0F; // room for the shadow and the edge fade
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
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR_MIPMAP_LINEAR);
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
        GL30.glGenerateMipmap(GL11.GL_TEXTURE_2D);
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
