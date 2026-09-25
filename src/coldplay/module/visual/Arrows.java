package coldplay.module.visual;

import coldplay.event.EventRender;
import coldplay.event.EventRender2D;
import coldplay.event.EventRender3D;
import coldplay.event.EventRenderMirror;
import coldplay.event.EventTarget;
import coldplay.gui.GlassShader;
import coldplay.setting.BooleanSetting;
import coldplay.setting.ModeSetting;
import coldplay.setting.NumberSetting;
import coldplay.util.ProjectionUtil;
import coldplay.util.RenderUtil;
import coldplay.util.font.CustomFont;
import coldplay.util.font.FontRef;
import coldplay.util.font.Fonts;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.model.ModelBase;
import net.minecraft.client.model.ModelBiped;
import net.minecraft.client.model.ModelQuadruped;
import net.minecraft.client.model.ModelRenderer;
import net.minecraft.client.model.PositionTextureVertex;
import net.minecraft.client.model.TexturedQuad;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.entity.Render;
import net.minecraft.client.renderer.entity.RendererLivingEntity;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.entity.Entity;
import net.minecraft.util.MathHelper;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.Vec3;

import org.lwjgl.BufferUtils;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Off-screen entities get a marker on a ring around the crosshair. */
public class Arrows extends EntityVisual {
    private static final String MODE_TRIANGLE = "Triangle";
    private static final String MODE_CHEVRON = "Chevron";
    private static final String MODE_HALO = "Halo";
    private static final String MODE_HEADS = "Heads";

    // GUI px
    private static final float CHEVRON_RING = 36.0F;
    private static final float HALO_RING = 44.0F;
    private static final float HALO_LANE = 4.5F;
    private static final float HEADS_RING = 60.0F;
    private static final double NEAR = 5.0; // blocks; closer targets get a second mark
    private static final int SHADE = 0x4D000000;
    private static final int LABEL = 0xD1F4F6F8;
    private static final int LABEL_OUTLINE = 0x8C000000;
    private static final FontRef HALO_FONT = new FontRef(Fonts.GEIST_MONO_MEDIUM, 8.5F);
    private static final FontRef HEADS_FONT = new FontRef(Fonts.GEIST_MONO_MEDIUM, 8.0F);

    private final ModeSetting mode = add(new ModeSetting("Mode", MODE_TRIANGLE, MODE_TRIANGLE, MODE_CHEVRON, MODE_HALO, MODE_HEADS)
            .describe("Marker style: Triangle, Chevron, Halo (arcs on a ring, longer when closer), or Heads (the entity's face)."));
    private final NumberSetting size = add(new NumberSetting("Size", 6.0, 3.0, 16.0, 1.0).describe("Off-screen arrow size, in pixels."));
    private final NumberSetting radius = add(new NumberSetting("Radius", 30.0, 10.0, 120.0, 1.0).describe("Arrow distance from the crosshair, in pixels."));
    private final BooleanSetting labels = add(new BooleanSetting("Labels", true).describe("Distance next to each marker."));

    // gluProject scratch, reused across frames
    private final FloatBuffer modelview = BufferUtils.createFloatBuffer(16);
    private final FloatBuffer projection = BufferUtils.createFloatBuffer(16);
    private final IntBuffer viewport = BufferUtils.createIntBuffer(16);
    private final FloatBuffer screenCoords = BufferUtils.createFloatBuffer(3);

    // filled in the world pass, drawn in the HUD pass
    private final List<ScreenArrow> arrows2D = new ArrayList<ScreenArrow>();

    public Arrows() {
        super("Arrows", "Points to off-screen entities with markers around the crosshair.");
        size.visibleWhen(() -> MODE_TRIANGLE.equals(mode.get())).indent(1);
        radius.visibleWhen(() -> MODE_TRIANGLE.equals(mode.get())).indent(1);
        labels.visibleWhen(() -> MODE_HALO.equals(mode.get()) || MODE_HEADS.equals(mode.get())).indent(1);
        addFilters();
        addColors();
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
            // the mirror shows its texture flipped, so no text there
            drawArrows(event.getWidth() / (float) event.getScale(), event.getHeight() / (float) event.getScale(), true, false);
        }
    }

    @EventTarget
    public void onRender2D(EventRender2D event) {
        boolean text = false;
        if (labels.get() && !arrows2D.isEmpty() && (MODE_HALO.equals(mode.get()) || MODE_HEADS.equals(mode.get()))) {
            Fonts.load(event.getResolution().getScaleFactor()); // lazy init, needs a live GL context
            text = Fonts.isLoaded();
        }
        drawArrows(event.getResolution().getScaledWidth(), event.getResolution().getScaledHeight(), false, text);
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
                arrows2D.add(new ScreenArrow(bearingAngle(cameraYaw, viewer.xCoord, viewer.zCoord,
                        target.centerX, target.centerZ), target, target.distance));
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

    /** 1 at 4 blocks and under, 0 at 32 and past. */
    static float closeness(double distance) {
        return (float) MathHelper.clamp_double((32.0 - distance) / 28.0, 0.0, 1.0);
    }

    private void drawArrows(float width, float height, boolean mirror, boolean text) {
        if (arrows2D.isEmpty()) {
            return;
        }
        float cx = width / 2.0F;
        float cy = height / 2.0F;
        String current = mode.get();
        if (MODE_CHEVRON.equals(current)) {
            drawChevrons(cx, cy, mirror ? (float) mirrorArrowRadius(CHEVRON_RING, 11.0, width, height) : CHEVRON_RING);
        } else if (MODE_HALO.equals(current)) {
            drawHalo(cx, cy, mirror ? (float) mirrorArrowRadius(HALO_RING, 13.0, width, height) : HALO_RING, text);
        } else if (MODE_HEADS.equals(current)) {
            drawHeads(cx, cy, mirror ? (float) mirrorArrowRadius(HEADS_RING, 14.0, width, height) : HEADS_RING, text);
        } else {
            drawTriangles(cx, cy, width, height, mirror);
        }
        arrows2D.clear();
    }

    private void drawTriangles(float cx, float cy, float width, float height, boolean mirror) {
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
            int color = arrow.friendColor != 0 ? arrow.friendColor : distanceColor(arrow.distance);
            appendArrow(wr, cx, cy, effRadius, arrowSize, arrow.angle, color);
        }
        tessellator.draw();
        GlStateManager.enableCull();
        GlStateManager.enableTexture2D();
        GlStateManager.disableBlend();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
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

    /** Open chevrons that shrink and fade with distance; a second one stacks on anything close. */
    private void drawChevrons(float cx, float cy, float ring) {
        for (ScreenArrow arrow : arrows2D) {
            float near = closeness(arrow.distance);
            float k = 0.72F + 0.28F * near;
            float alpha = 0.45F + 0.55F * near;
            chevron(cx, cy, arrow.angle, ring, k, arrow.color, alpha);
            if (arrow.distance < NEAR) {
                chevron(cx, cy, arrow.angle, ring + 4.5F * k, k, arrow.color, alpha);
            }
        }
    }

    private static void chevron(float cx, float cy, float angle, float ring, float k, int rgb, float alpha) {
        float cos = (float) Math.cos(angle), sin = (float) Math.sin(angle);
        float tipX = cx + cos * (ring + 4.0F * k), tipY = cy + sin * (ring + 4.0F * k);
        float arm = 5.0F * k;
        float baseX = tipX - cos * arm, baseY = tipY - sin * arm;
        float leftX = baseX - sin * arm, leftY = baseY + cos * arm;
        float rightX = baseX + sin * arm, rightY = baseY - cos * arm;
        GlassShader.polyline(leftX, leftY, tipX, tipY, rightX, rightY, 4.0F, argb(0x000000, 0.32F * alpha));
        GlassShader.polyline(leftX, leftY, tipX, tipY, rightX, rightY, 2.0F, argb(rgb, alpha));
    }

    /**
     * A faint ring with one lit arc per target, centered on its bearing and longer the closer it is.
     * Nearest first, and an arc that would overlap one already drawn moves out a lane.
     */
    private void drawHalo(float cx, float cy, float ring, boolean text) {
        circle(cx, cy, ring, 3.0F, 0.0F, 1.0F, 0x38000000);
        circle(cx, cy, ring, 1.5F, 0.0F, 1.0F, 0x33FFFFFF);
        List<ScreenArrow> byDistance = new ArrayList<ScreenArrow>(arrows2D);
        Collections.sort(byDistance, new Comparator<ScreenArrow>() {
            @Override
            public int compare(ScreenArrow a, ScreenArrow b) {
                return Double.compare(a.distance, b.distance);
            }
        });
        List<float[]> placed = new ArrayList<float[]>(); // {lane, angle, span}
        for (ScreenArrow arrow : byDistance) {
            float span = haloSpan(arrow.distance);
            int lane = haloLane(placed, arrow.angle, span);
            placed.add(new float[] {lane, arrow.angle, span});
            float r = ring + lane * HALO_LANE;
            float alpha = 0.55F + 0.45F * closeness(arrow.distance);
            float from = (float) ((arrow.angle - span / 2.0F + Math.PI / 2.0) / (Math.PI * 2.0));
            from -= (float) Math.floor(from);
            float to = from + (float) (span / (Math.PI * 2.0));
            if (arrow.distance < NEAR) {
                circle(cx, cy, r, 7.0F, from, to, argb(arrow.color, 0.28F));
            }
            circle(cx, cy, r, 4.0F, from, to, SHADE);
            circle(cx, cy, r, 2.25F, from, to, argb(arrow.color, alpha));
            pointer(cx, cy, arrow.angle, r + 2.5F, r + 7.0F, 3.0F, argb(arrow.color, alpha));
            if (text) {
                label(HALO_FONT, 8.5F, distance(arrow.distance), cx + (float) Math.cos(arrow.angle) * (r + 17.0F),
                        cy + (float) Math.sin(arrow.angle) * (r + 17.0F));
            }
        }
    }

    /** Arc length in radians: 10 degrees at 32 blocks, growing to 46 up close. */
    static float haloSpan(double distance) {
        return (float) Math.toRadians(10.0 + 36.0 * MathHelper.clamp_double((32.0 - distance) / 32.0, 0.0, 1.0));
    }

    /** The innermost lane where an arc at {@code angle} keeps clear of every arc already placed there. */
    static int haloLane(List<float[]> placed, float angle, float span) {
        int lane = 0;
        boolean clash = true;
        while (clash) {
            clash = false;
            for (float[] other : placed) {
                float apart = Math.abs(MathHelper.wrapAngleTo180_float((float) Math.toDegrees(angle - other[1])));
                if ((int) other[0] == lane && Math.toRadians(apart) <= (span + other[2]) / 2.0 + 0.05) {
                    clash = true;
                    lane++;
                    break;
                }
            }
        }
        return lane;
    }

    /** Each target's face in a dark disc on the ring, with a wedge pointing at it. */
    private void drawHeads(float cx, float cy, float ring, boolean text) {
        for (ScreenArrow arrow : arrows2D) {
            float cos = (float) Math.cos(arrow.angle), sin = (float) Math.sin(arrow.angle);
            float x = cx + cos * ring, y = cy + sin * ring;
            float alpha = 0.5F + 0.5F * closeness(arrow.distance);
            if (arrow.distance < NEAR) {
                GlassShader.ellipse(x, y, 13.5F, 13.5F, 0, 0x59FF3C3C, 3.0F);
            }
            pointer(cx, cy, arrow.angle, ring + 7.5F, ring + 14.0F, 4.2F, argb(arrow.color, alpha));
            GlassShader.ellipse(x, y, 10.0F, 10.0F, 0x9E0E1015, 0, 0.0F);
            face(arrow.entity, x, y, 11.0F);
            GlassShader.ellipse(x, y, 10.0F, 10.0F, 0, argb(arrow.color, alpha), 1.5F);
            if (text) {
                // inside the ring, so it never covers the wedge
                label(HEADS_FONT, 8.0F, distance(arrow.distance), cx + cos * (ring - 19.0F), cy + sin * (ring - 19.0F));
            }
        }
    }

    /** The front of the model's head, and a player's hat layer over it, fitted in a {@code size} square. */
    private static void face(Entity entity, float x, float y, float size) {
        Render<Entity> render = Minecraft.getMinecraft().getRenderManager().getEntityRenderObject(entity);
        if (!(render instanceof RendererLivingEntity)) {
            return;
        }
        ModelBase model = ((RendererLivingEntity<?>) render).getMainModel();
        ModelRenderer head = model instanceof ModelBiped ? ((ModelBiped) model).bipedHead
                : model instanceof ModelQuadruped ? ((ModelQuadruped) model).head
                : model.boxList.isEmpty() ? null : model.boxList.get(0);
        ResourceLocation texture = render.coldplayTexture(entity);
        if (head == null || head.cubeList.isEmpty() || texture == null) {
            return;
        }
        Minecraft.getMinecraft().getTextureManager().bindTexture(texture);
        facePart(head, x, y, size);
        if (model instanceof ModelBiped) {
            facePart(((ModelBiped) model).bipedHeadwear, x, y, size);
        }
    }

    private static void facePart(ModelRenderer part, float x, float y, float size) {
        TexturedQuad front = part.cubeList.isEmpty() ? null : part.cubeList.get(0).coldplayFront();
        if (front == null) {
            return;
        }
        PositionTextureVertex topLeft = front.vertexPositions[1];
        PositionTextureVertex bottomRight = front.vertexPositions[3];
        float texelsW = Math.abs(bottomRight.texturePositionX - topLeft.texturePositionX) * part.textureWidth;
        float texelsH = Math.abs(bottomRight.texturePositionY - topLeft.texturePositionY) * part.textureHeight;
        float w = texelsW >= texelsH ? size : size * texelsW / texelsH;
        float h = texelsH >= texelsW ? size : size * texelsH / texelsW;
        GlassShader.image(x - w / 2.0F, y - h / 2.0F, w, h, 0.0F, topLeft.texturePositionX, topLeft.texturePositionY,
                bottomRight.texturePositionX, bottomRight.texturePositionY, 0xFFFFFFFF);
    }

    private static void circle(float cx, float cy, float r, float width, float from, float to, int color) {
        GlassShader.arc(cx - r, cy - r, r * 2.0F, r * 2.0F, r, width, from, to, color);
    }

    /** A wedge on the bearing, its base {@code base} and tip {@code tip} GUI px out from the center. */
    private static void pointer(float cx, float cy, float angle, float base, float tip, float half, int fill) {
        float cos = (float) Math.cos(angle), sin = (float) Math.sin(angle);
        float bx = cx + cos * base, by = cy + sin * base;
        GlassShader.triangle(cx + cos * tip, cy + sin * tip, bx - sin * half, by + cos * half,
                bx + sin * half, by - cos * half, fill, SHADE, 0.75F);
    }

    /** Centered on (x, y) the way SVG's middle baseline sits, which is half an x-height above the baseline. */
    private static void label(FontRef ref, float size, String text, float x, float y) {
        CustomFont font = ref.get();
        font.drawStringWithOutline(text, x - font.getStringWidth(text) / 2.0F, y + 0.265F * size - font.getAscent(),
                LABEL, LABEL_OUTLINE, 1.2F);
    }

    static String distance(double distance) {
        return distance < 10.0 ? String.format("%.1fm", distance) : String.format("%dm", (long) Math.rint(distance));
    }

    private static int argb(int rgb, float alpha) {
        return Math.round(alpha * 255.0F) << 24 | (rgb & 0xFFFFFF);
    }

    /** An off-screen target's crosshair-ring bearing, in radians. */
    private static final class ScreenArrow {
        final float angle;
        final int color;
        final int friendColor;
        final double distance;
        final Entity entity;

        ScreenArrow(float angle, Target target, double distance) {
            this.angle = angle;
            this.color = target.color;
            this.friendColor = target.friendColor;
            this.distance = distance;
            this.entity = target.entity;
        }
    }
}
