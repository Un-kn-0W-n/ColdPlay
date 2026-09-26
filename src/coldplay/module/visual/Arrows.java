package coldplay.module.visual;

import coldplay.event.EventRender;
import coldplay.event.EventRender2D;
import coldplay.event.EventRender3D;
import coldplay.event.EventRenderMirror;
import coldplay.event.EventTarget;
import coldplay.setting.ModeSetting;
import coldplay.setting.NumberSetting;
import coldplay.util.ProjectionUtil;
import coldplay.util.RenderUtil;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.renderer.GlStateManager;
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

/** Off-screen entities get a direction arrow on a ring around the crosshair. */
public class Arrows extends EntityVisual {
    private final ModeSetting mode = add(new ModeSetting("Mode", "Triangle", "Triangle").describe("Marker style."));
    private final NumberSetting size = add(new NumberSetting("Size", 6.0, 3.0, 16.0, 1.0).describe("Off-screen arrow size, in pixels."));
    private final NumberSetting radius = add(new NumberSetting("Radius", 30.0, 10.0, 120.0, 1.0).describe("Arrow distance from the crosshair, in pixels."));

    // gluProject scratch, reused across frames
    private final FloatBuffer modelview = BufferUtils.createFloatBuffer(16);
    private final FloatBuffer projection = BufferUtils.createFloatBuffer(16);
    private final IntBuffer viewport = BufferUtils.createIntBuffer(16);
    private final FloatBuffer screenCoords = BufferUtils.createFloatBuffer(3);

    // filled in the world pass, drawn in the HUD pass
    private final List<ScreenArrow> arrows2D = new ArrayList<ScreenArrow>();

    public Arrows() {
        super("Arrows", "Points to off-screen entities with arrows around the crosshair.");
        addFilters();
    }

    @Override
    protected void onDisable() {
        arrows2D.clear();
        targets.clear();
    }

    @EventTarget
    public void onRender(EventRender event) {
        collectTargets(event.getPartialTicks());
    }

    @EventTarget
    public void onRender3D(EventRender3D event) {
        EntityPlayerSP player = Minecraft.getMinecraft().thePlayer;
        if (player != null) {
            renderView(event.getPartialTicks(),
                    (float) RenderUtil.interp(player.prevRotationYaw, player.rotationYaw, event.getPartialTicks()));
        }
    }

    @EventTarget
    public void onRenderMirror(EventRenderMirror event) {
        if (event.getStage() == EventRenderMirror.Stage.WORLD) {
            renderView(event.getPartialTicks(), event.getYaw());
        } else {
            drawArrows(event.getWidth() / (float) event.getScale(), event.getHeight() / (float) event.getScale(), true);
        }
    }

    @EventTarget
    public void onRender2D(EventRender2D event) {
        drawArrows(event.getResolution().getScaledWidth(), event.getResolution().getScaledHeight(), false);
    }

    private void renderView(float partialTicks, float cameraYaw) {
        arrows2D.clear();
        EntityPlayerSP player = Minecraft.getMinecraft().thePlayer;
        // glGet* stalls the pipeline, so skip the capture when idle
        if (player == null || targets.isEmpty()) {
            return;
        }
        Vec3 viewer = RenderUtil.interpolatedPosition(player, partialTicks);
        ProjectionUtil.captureMatrices(modelview, projection, viewport);
        for (Target target : targets) {
            ProjectionUtil.AabbProjection projected = ProjectionUtil.projectAabb(target.box,
                    viewer.xCoord, viewer.yCoord, viewer.zCoord,
                    modelview, projection, viewport, screenCoords);
            if (!projected.overlapsViewport(target.box, viewer.xCoord, viewer.zCoord)) {
                int color = target.friendColor != 0 ? target.friendColor : distanceColor(target.distance);
                arrows2D.add(new ScreenArrow(bearingAngle(cameraYaw, viewer.xCoord, viewer.zCoord,
                        target.centerX, target.centerZ), color));
            }
        }
    }

    /** Yaw-only bearing in y-down screen radians; ignoring pitch keeps the ring stable. */
    static float bearingAngle(float cameraYaw, double viewerX, double viewerZ, double tx, double tz) {
        float wantYaw = (float) (Math.toDegrees(Math.atan2(tz - viewerZ, tx - viewerX)) - 90.0);
        float yawDiff = MathHelper.wrapAngleTo180_float(wantYaw - cameraYaw);
        // front -> -PI/2 (top), right -> 0, behind -> +PI/2 (bottom), left -> PI
        return (float) (Math.toRadians(yawDiff) - Math.PI / 2.0);
    }

    /** Light red within 5 blocks, black past 15, linear in between. */
    private static int distanceColor(double dist) {
        double t = MathHelper.clamp_double((15.0 - dist) / 10.0, 0.0, 1.0);
        return RenderUtil.rgb((int) (255 * t), (int) (100 * t), (int) (100 * t));
    }

    private void drawArrows(float width, float height, boolean mirror) {
        if (arrows2D.isEmpty()) {
            return;
        }
        float cx = width / 2.0F;
        float cy = height / 2.0F;
        double arrowSize = size.get();
        double ringRadius = mirror ? mirrorArrowRadius(radius.get(), arrowSize, width, height) : radius.get();

        // Extend arrows past open containers so the panel cannot hide their arrowheads.
        float rectLeft = 0, rectTop = 0, rectRight = 0, rectBottom = 0;
        boolean avoidGui = false;
        if (!mirror && Minecraft.getMinecraft().currentScreen instanceof GuiContainer) {
            GuiContainer gui = (GuiContainer) Minecraft.getMinecraft().currentScreen;
            final float pad = 8;
            rectLeft = gui.guiLeft - pad;
            rectTop = gui.guiTop - pad;
            rectRight = gui.guiLeft + gui.xSize + pad;
            rectBottom = gui.guiTop + gui.ySize + pad;
            avoidGui = cx > rectLeft && cx < rectRight && cy > rectTop && cy < rectBottom;
        }

        GlStateManager.enableBlend();
        GlStateManager.disableTexture2D();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        GlStateManager.disableCull(); // arrowhead winding flips with the ring angle
        Tessellator tessellator = Tessellator.getInstance();
        WorldRenderer wr = tessellator.getWorldRenderer();
        wr.begin(4, DefaultVertexFormats.POSITION_COLOR); // 4 = GL_TRIANGLES
        for (ScreenArrow arrow : arrows2D) {
            double effRadius = ringRadius;
            if (avoidGui) {
                // Ray from the (inside-rect) centre exits the padded rect at min(tx, ty).
                double cos = Math.cos(arrow.angle);
                double sin = Math.sin(arrow.angle);
                double tx = cos > 1e-6 ? (rectRight - cx) / cos : cos < -1e-6 ? (rectLeft - cx) / cos : Double.MAX_VALUE;
                double ty = sin > 1e-6 ? (rectBottom - cy) / sin : sin < -1e-6 ? (rectTop - cy) / sin : Double.MAX_VALUE;
                effRadius = Math.max(ringRadius, Math.min(tx, ty) + arrowSize); // +size so the whole arrowhead clears
            }
            appendArrow(wr, cx, cy, effRadius, arrowSize, arrow.angle, arrow.color);
        }
        tessellator.draw();
        GlStateManager.enableCull();
        GlStateManager.enableTexture2D();
        GlStateManager.disableBlend();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        arrows2D.clear();
    }

    static double mirrorArrowRadius(double requested, double size, float width, float height) {
        return Math.max(0, Math.min(requested, Math.min(width, height) / 2.0 - size - 1));
    }

    private void appendArrow(WorldRenderer wr, float cx, float cy, double radius, double size, float angle, int color) {
        double cos = Math.cos(angle);
        double sin = Math.sin(angle);
        double px = cx + cos * radius; // ring point this arrow sits on
        double py = cy + sin * radius;
        double tipX = px + cos * size;
        double tipY = py + sin * size;
        double baseX = px - cos * (size * 0.5);
        double baseY = py - sin * (size * 0.5);
        double half = size * 0.6; // base half-width, along the perpendicular (-sin, cos)
        double leftX = baseX - sin * half;
        double leftY = baseY + cos * half;
        double rightX = baseX + sin * half;
        double rightY = baseY - cos * half;
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;
        wr.pos(tipX, tipY, 0.0).color(r, g, b, 255).endVertex();
        wr.pos(leftX, leftY, 0.0).color(r, g, b, 255).endVertex();
        wr.pos(rightX, rightY, 0.0).color(r, g, b, 255).endVertex();
    }

    /** An off-screen target's crosshair-ring bearing, in radians. */
    private static final class ScreenArrow {
        final float angle;
        final int color;

        ScreenArrow(float angle, int color) {
            this.angle = angle;
            this.color = color;
        }
    }
}
