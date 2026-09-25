package coldplay.module.visual;

import coldplay.event.EventRender;
import coldplay.event.EventRender2D;
import coldplay.event.EventRender3D;
import coldplay.event.EventRenderMirror;
import coldplay.event.EventTarget;
import coldplay.gui.GlassShader;
import coldplay.setting.ModeSetting;
import coldplay.setting.NumberSetting;
import coldplay.util.ProjectionUtil;
import coldplay.util.RenderUtil;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.util.MathHelper;
import net.minecraft.util.Vec3;

import org.lwjgl.BufferUtils;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Lines from the crosshair to each entity. Line draws them in the world; the other modes project in the
 * world pass and draw shaded lines on the HUD. Bobbing is forced off so they stay on the crosshair.
 */
public class Tracers extends EntityVisual {
    private static final String MODE_LINE = "Line";
    private static final String MODE_FADE = "Fade";
    private static final String MODE_FEET = "Feet";
    private static final String MODE_GLASS = "Glass";

    private static final float FADE_GAP = 18.0F; // GUI px left clear around the crosshair
    private static final float GLASS_GAP = 14.0F;

    private final ModeSetting mode = add(new ModeSetting("Mode", MODE_LINE, MODE_LINE, MODE_FADE, MODE_FEET, MODE_GLASS)
            .describe("Line style: plain world Line, Fade (fades in from the crosshair), Feet (from the bottom of the screen to the feet), or Glass (a smoked glass tube)."));
    private final NumberSetting width = add(new NumberSetting("Width", 1.5, 0.5, 5.0, 0.5).describe("Tracer line width in pixels."));

    // gluProject scratch, reused across frames
    private final FloatBuffer modelview = BufferUtils.createFloatBuffer(16);
    private final FloatBuffer projection = BufferUtils.createFloatBuffer(16);
    private final IntBuffer viewport = BufferUtils.createIntBuffer(16);
    private final FloatBuffer screenCoords = BufferUtils.createFloatBuffer(3);
    private final float[] segment = new float[4];

    // framebuffer px; filled in the world pass, drawn in the HUD pass
    private final List<ScreenLine> lines2D = new ArrayList<ScreenLine>();

    private boolean bobbingForced;
    private boolean prevViewBobbing;

    public Tracers() {
        super("Tracers", "Draws lines to entities. Forces View Bobbing off while enabled.");
        width.visibleWhen(() -> MODE_LINE.equals(mode.get())).indent(1);
        addFilters();
        addColors();
    }

    @Override
    protected void onDisable() {
        targets.clear();
        lines2D.clear();
        if (bobbingForced) { // onRender will not run again to restore it
            Minecraft.getMinecraft().gameSettings.viewBobbing = prevViewBobbing;
            bobbingForced = false;
        }
    }

    @EventTarget
    public void onRender(EventRender event) {
        Minecraft mc = Minecraft.getMinecraft();
        if (!bobbingForced) {
            prevViewBobbing = mc.gameSettings.viewBobbing;
            bobbingForced = true;
        }
        mc.gameSettings.viewBobbing = false; // re-assert every frame so the vanilla menu can't win
        collectTargets(event.getPartialTicks());
    }

    @EventTarget
    public void onRender3D(EventRender3D event) {
        renderView(event.getPartialTicks());
    }

    @EventTarget
    public void onRenderMirror(EventRenderMirror event) {
        if (event.getStage() == EventRenderMirror.Stage.WORLD) {
            renderView(event.getPartialTicks());
        } else {
            drawLines(event.getWidth() / (float) event.getScale(), event.getHeight() / (float) event.getScale(), event.getScale());
        }
    }

    @EventTarget
    public void onRender2D(EventRender2D event) {
        drawLines(event.getResolution().getScaledWidth(), event.getResolution().getScaledHeight(),
                event.getResolution().getScaleFactor());
    }

    private void renderView(float partialTicks) {
        lines2D.clear();
        EntityPlayerSP player = Minecraft.getMinecraft().thePlayer;
        if (player == null || targets.isEmpty()) {
            return;
        }
        Vec3 viewer = RenderUtil.interpolatedPosition(player, partialTicks);
        if (MODE_LINE.equals(mode.get())) {
            drawWorldLines(viewer, viewer.yCoord + player.getEyeHeight());
        } else {
            project(viewer, player.getEyeHeight());
        }
    }

    private void drawWorldLines(Vec3 viewer, double eyeY) {
        RenderUtil.beginWorldOverlay((float) Math.max(0.5, width.get().doubleValue()));
        Tessellator tessellator = Tessellator.getInstance();
        WorldRenderer wr = tessellator.getWorldRenderer();
        for (Target target : targets) {
            int r = (target.color >> 16) & 0xFF;
            int g = (target.color >> 8) & 0xFF;
            int b = target.color & 0xFF;
            wr.begin(1, DefaultVertexFormats.POSITION_COLOR);
            wr.pos(viewer.xCoord, eyeY, viewer.zCoord).color(r, g, b, 255).endVertex();
            wr.pos(target.centerX, target.centerY, target.centerZ).color(r, g, b, 255).endVertex();
            tessellator.draw();
        }
        RenderUtil.endWorldOverlay();
    }

    /** Where each line ends on screen: the body center, or for Feet the feet, which only count while the entity is in view. */
    private void project(Vec3 viewer, float eyeHeight) {
        ProjectionUtil.captureMatrices(modelview, projection, viewport);
        boolean feet = MODE_FEET.equals(mode.get());
        for (Target target : targets) {
            double x = target.centerX - viewer.xCoord, z = target.centerZ - viewer.zCoord;
            if (feet) {
                ProjectionUtil.Point point = ProjectionUtil.projectPoint(target.centerX, target.box.minY, target.centerZ,
                        viewer.xCoord, viewer.yCoord, viewer.zCoord, modelview, projection, viewport, screenCoords);
                if (point != null && ProjectionUtil.projectAabb(target.box, viewer.xCoord, viewer.yCoord, viewer.zCoord,
                        modelview, projection, viewport, screenCoords).overlapsViewport(target.box, viewer.xCoord, viewer.zCoord)) {
                    lines2D.add(new ScreenLine(point.x, point.y, true, target));
                }
                continue;
            }
            ProjectionUtil.Point center = ProjectionUtil.projectPoint(target.centerX, target.centerY, target.centerZ,
                    viewer.xCoord, viewer.yCoord, viewer.zCoord, modelview, projection, viewport, screenCoords);
            if (center != null && center.x >= 0 && center.x <= viewport.get(2) && center.y >= 0 && center.y <= viewport.get(3)) {
                lines2D.add(new ScreenLine(center.x, center.y, true, target));
            } else if (ProjectionUtil.projectSegment(0.0, eyeHeight, 0.0, x, target.centerY - viewer.yCoord, z,
                    modelview, projection, viewport, segment)) {
                // behind the camera, the line ends where it crosses the near plane
                lines2D.add(new ScreenLine(segment[2], segment[3], false, target));
            }
        }
    }

    private void drawLines(float width, float height, int scale) {
        if (lines2D.isEmpty()) {
            return;
        }
        String current = mode.get();
        float cx = width / 2.0F, cy = height / 2.0F;
        float originY = MODE_FEET.equals(current) ? height : cy;
        for (ScreenLine line : lines2D) {
            float x = line.x / scale, y = line.y / scale;
            if (x < 0 || x > width || y < 0 || y > height) {
                float[] edge = clipToScreen(cx, originY, x, y, width, height);
                x = edge[0];
                y = edge[1];
            }
            if (MODE_FADE.equals(current)) {
                fade(cx, cy, x, y, line);
            } else if (MODE_FEET.equals(current)) {
                feet(cx, height, x, y, line);
            } else if (MODE_GLASS.equals(current)) {
                glass(cx, cy, x, y, line);
            }
        }
        lines2D.clear();
    }

    /** Fades in from nothing at the gap to full color at a dot on the body. */
    private static void fade(float cx, float cy, float x, float y, ScreenLine line) {
        float dx = x - cx, dy = y - cy;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len > FADE_GAP + 2.0F) {
            float sx = cx + dx / len * FADE_GAP, sy = cy + dy / len * FADE_GAP;
            GlassShader.line(sx, sy, x, y, 1.25F, argb(line.color, 0.0F), argb(line.color, 0.8F), 0.55F, argb(line.color, 1.0F));
        }
        if (line.onScreen) {
            GlassShader.ellipse(x, y, 2.25F, 2.25F, argb(line.color, 1.0F), 0x80000000, 1.0F);
        }
    }

    /** From the bottom center of the screen to a ground ring at the feet, sized by distance. */
    private static void feet(float cx, float bottom, float x, float y, ScreenLine line) {
        GlassShader.line(cx, bottom, x, y, 1.25F, argb(line.color, 0.12F), argb(line.color, 0.535F), 0.5F,
                argb(line.color, 0.95F));
        float s = (float) MathHelper.clamp_double(9.0 / line.distance, 0.45, 1.6);
        GlassShader.ellipse(x, y, 9.0F * s, 2.6F * s, argb(line.color, 0.18F), argb(line.color, 1.0F), 1.0F);
    }

    /** A smoked glass tube with a colored core and a light sheen on its upper edge, ending in a glass ring. */
    private static void glass(float cx, float cy, float x, float y, ScreenLine line) {
        float dx = x - cx, dy = y - cy;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len - GLASS_GAP <= 1.0F) {
            if (line.onScreen) {
                GlassShader.ellipse(x, y, 2.0F, 2.0F, argb(line.color, 1.0F), 0x73000000, 1.0F);
            }
            return;
        }
        float ux = dx / len, uy = dy / len;
        float sx = cx + ux * GLASS_GAP, sy = cy + uy * GLASS_GAP;
        float ex = line.onScreen ? x - ux * 6.0F : x, ey = line.onScreen ? y - uy * 6.0F : y;
        GlassShader.line(sx, sy, ex, ey, 7.0F, 0x21FFFFFF);
        GlassShader.line(sx, sy, ex, ey, 5.5F, 0x800E1015);
        GlassShader.line(sx, sy, ex, ey, 1.3F, argb(line.color, 0.95F));
        float ox = -uy * 1.6F, oy = ux * 1.6F;
        if (oy > 0.0F) {
            ox = -ox;
            oy = -oy;
        }
        GlassShader.line(sx + ox, sy + oy, ex + ox, ey + oy, 0.5F, 0x59FFFFFF);
        if (line.onScreen) {
            GlassShader.ellipse(x, y, 6.0F, 6.0F, 0x8C0E1015, 0x24FFFFFF, 2.5F);
            GlassShader.ellipse(x, y, 6.0F, 6.0F, 0, argb(line.color, 1.0F), 1.25F);
            GlassShader.ellipse(x, y, 1.8F, 1.8F, argb(line.color, 1.0F), 0, 0.0F);
        }
    }

    /** Where the line from (x0, y0) toward (x1, y1) leaves the screen, or (x1, y1) when it stays inside. */
    static float[] clipToScreen(float x0, float y0, float x1, float y1, float width, float height) {
        float dx = x1 - x0, dy = y1 - y0;
        float t = 1.0F;
        if (dx > 1e-6F) {
            t = Math.min(t, (width - x0) / dx);
        } else if (dx < -1e-6F) {
            t = Math.min(t, -x0 / dx);
        }
        if (dy > 1e-6F) {
            t = Math.min(t, (height - y0) / dy);
        } else if (dy < -1e-6F) {
            t = Math.min(t, -y0 / dy);
        }
        return new float[] {x0 + dx * t, y0 + dy * t};
    }

    private static int argb(int rgb, float alpha) {
        return Math.round(alpha * 255.0F) << 24 | (rgb & 0xFFFFFF);
    }

    /** One tracer's far end, and whether it is the entity itself or only where the line leaves the view. */
    private static final class ScreenLine {
        final float x;
        final float y;
        final boolean onScreen;
        final int color;
        final double distance;

        ScreenLine(float x, float y, boolean onScreen, Target target) {
            this.x = x;
            this.y = y;
            this.onScreen = onScreen;
            this.color = target.color;
            this.distance = target.distance;
        }
    }
}
