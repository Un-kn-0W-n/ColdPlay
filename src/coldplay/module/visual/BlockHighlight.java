package coldplay.module.visual;

import coldplay.event.EventBlockPlace;
import coldplay.event.EventRender2D;
import coldplay.event.EventRender3D;
import coldplay.event.EventRenderMirror;
import coldplay.event.EventTarget;
import coldplay.event.EventUpdate;
import coldplay.gui.GlassShader;
import coldplay.gui.Theme;
import coldplay.module.Category;
import coldplay.module.Module;
import coldplay.setting.ColorSetting;
import coldplay.setting.ModeSetting;
import coldplay.setting.NumberSetting;
import coldplay.util.ProjectionUtil;
import coldplay.util.RenderUtil;
import coldplay.util.ScreenProjector;
import coldplay.util.ScreenStrokes;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.util.MathHelper;

import org.lwjgl.opengl.GL11;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Recently placed blocks get a highlight that fades out; fills draw in the world, lines on the HUD. */
public class BlockHighlight extends Module {
    private static final String MODE_TRAIL = "Trail";
    private static final String MODE_SWEEP = "Sweep";
    private static final String MODE_CORNERS = "Corners";

    // Sizes are GUI px, the design's board px times 0.75. Times are seconds.
    private static final double PAD = 0.002;
    private static final double LINK_SQ = 3.0; // the trail only joins blocks that touch, edge or corner included
    private static final float TRAIL_GROW = 0.1F;
    private static final float TRAIL_FADE = 0.2F; // of the fade duration
    private static final float TRAIL_FILL = 0.16F;
    private static final float TRAIL_GLOW = 0.45F;
    private static final float TRAIL_GLOW_A = 0.95F;
    private static final float TRAIL_W = 1.65F;
    private static final double[] DIAMOND_X = {0.0, 0.13, 0.0, -0.13};
    private static final double[] DIAMOND_Z = {-0.13, 0.0, 0.13, 0.0};
    private static final float DIAMOND_W = 1.125F;
    private static final float SWEEP_RISE = 0.22F;
    private static final float SWEEP_FILL = 0.24F;
    private static final float RING_GLOW = 0.45F;
    private static final float RING_W = 1.35F;
    private static final int RING = 0xF2FFFFFF;
    private static final float EDGE_W = 0.9F;
    private static final float EDGE_A = 0.85F;
    private static final float CORNER_EASE = 0.25F;
    private static final float CORNER_FADE = 0.3F; // of the fade duration
    private static final float ARM_FROM = 0.5F; // of each edge, so the arms start as the whole box
    private static final float ARM_TO = 0.18F;
    private static final float CORNER_FILL = 0.14F;
    private static final float CORNER_GLOW = 0.4F;
    private static final float CORNER_GLOW_A = 0.85F;
    private static final float UNDER_W = 2.7F;
    private static final float UNDER_A = 0.35F;
    private static final float LINE_W = 1.5F;
    // ScreenStrokes' glow stack, stroked through strokeArms so the arms of a corner join
    private static final float[] GLOW_W = {14.0F, 10.0F, 6.0F, 3.0F};
    private static final float[] GLOW_A = {0.08F, 0.13F, 0.2F, 0.29F};

    private final ModeSetting mode = add(new ModeSetting("Mode", MODE_TRAIL, MODE_TRAIL, MODE_SWEEP, MODE_CORNERS)
            .describe("Trail: a line through the blocks in placement order. Sweep: a ring rises and fills each block. "
                    + "Corners: the box pulls back into corner brackets."));
    private final ColorSetting color = add(new ColorSetting("Color", 0x3C78FF).describe("Colour for placed-block highlights."));
    private final NumberSetting fadeDuration = add(new NumberSetting("Fade Duration", 1000.0, 100.0, 5000.0, 100.0).describe("How long a placed block stays highlighted, in milliseconds."));
    private final NumberSetting maxBlocks = add(new NumberSetting("Max Blocks", 64.0, 8.0, 256.0, 8.0).describe("Most recently placed blocks tracked at once; oldest are dropped."));

    // oldest first
    private final LinkedHashMap<BlockPos, Placed> placedBlocks = new LinkedHashMap<BlockPos, Placed>();
    private final List<Shape> shapes = new ArrayList<Shape>();
    private final ScreenProjector projector = new ScreenProjector();
    private BlockPos last;

    public BlockHighlight() {
        super("BlockHighlight", Category.VISUAL, "Highlights recently placed blocks and fades them out.");
    }

    @Override
    protected void onDisable() {
        placedBlocks.clear();
        shapes.clear();
        last = null;
    }

    @EventTarget
    public void onBlockPlace(EventBlockPlace event) {
        BlockPos pos = event.getPos();
        BlockPos from = last != null && !last.equals(pos) && last.distanceSq(pos) <= LINK_SQ ? last : null;
        last = pos;
        // remove first so a re-placed pos also moves to the back of the eviction order
        placedBlocks.remove(pos);
        placedBlocks.put(pos, new Placed(System.currentTimeMillis(), from));
        int max = maxBlocks.get().intValue();
        Iterator<BlockPos> eldest = placedBlocks.keySet().iterator();
        while (placedBlocks.size() > max) {
            eldest.next();
            eldest.remove();
        }
    }

    /** Runs on tick so the render pass never touches chunks. */
    @EventTarget
    public void onUpdate(EventUpdate event) {
        if (!event.isPre()) {
            return;
        }
        WorldClient world = Minecraft.getMinecraft().theWorld;
        if (world == null) {
            placedBlocks.clear();
            last = null;
            return;
        }
        long now = System.currentTimeMillis();
        long duration = fadeDuration.get().longValue();
        Iterator<Map.Entry<BlockPos, Placed>> it = placedBlocks.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<BlockPos, Placed> entry = it.next();
            if (now - entry.getValue().at >= duration
                    || world.getBlockState(entry.getKey()).getBlock().getMaterial().isReplaceable()) {
                it.remove();
            }
        }
    }

    @EventTarget
    public void onRender3D(EventRender3D event) {
        renderView();
    }

    @EventTarget
    public void onRenderMirror(EventRenderMirror event) {
        if (event.getStage() == EventRenderMirror.Stage.WORLD) {
            renderView();
        } else {
            drawScreen(event.getScale());
        }
    }

    @EventTarget
    public void onRender2D(EventRender2D event) {
        drawScreen(event.getResolution().getScaleFactor());
    }

    // ---- world pass

    /** Every fill goes into one culled quad buffer; lines are projected for the HUD pass. */
    private void renderView() {
        shapes.clear();
        if (placedBlocks.isEmpty()) {
            return;
        }
        String m = mode.get();
        float duration = fadeDuration.get().floatValue() / 1000.0F;
        long now = System.currentTimeMillis();
        projector.capture();
        Tessellator tessellator = Tessellator.getInstance();
        WorldRenderer wr = tessellator.getWorldRenderer();
        RenderUtil.beginWorldOverlay(1.0F);
        GlStateManager.enableCull(); // only the faces you see, like the design's front faces
        wr.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR);
        for (Map.Entry<BlockPos, Placed> entry : placedBlocks.entrySet()) {
            float age = (now - entry.getValue().at) / 1000.0F;
            if (age >= duration) {
                continue; // pruned on the next tick
            }
            BlockPos pos = entry.getKey();
            if (MODE_TRAIL.equals(m)) {
                shapes.add(trail(wr, pos, entry.getValue().from, age, duration));
            } else if (MODE_SWEEP.equals(m)) {
                shapes.add(sweep(wr, pos, age, duration));
            } else {
                shapes.add(corners(wr, pos, age, duration));
            }
        }
        tessellator.draw();
        GlStateManager.disableCull();
        RenderUtil.endWorldOverlay();
    }

    /** A line grows in from the block placed before, then a diamond marks the top center. */
    private Shape trail(WorldRenderer wr, BlockPos pos, BlockPos from, float age, float duration) {
        Shape s = new Shape(1.0F - smooth(TRAIL_FADE * duration, duration, age));
        AxisAlignedBB b = box(pos);
        RenderUtil.appendFilledBox(wr, new AxisAlignedBB(b.minX, b.maxY, b.minZ, b.maxX, b.maxY, b.maxZ),
                color.red(), color.green(), color.blue(), alpha(TRAIL_FILL * s.f));
        double x = pos.getX() + 0.5, y = b.maxY, z = pos.getZ() + 0.5;
        float grow = Math.min(1.0F, age / TRAIL_GROW);
        if (from != null) {
            double fx = from.getX() + 0.5, fy = from.getY() + 1.0 + PAD, fz = from.getZ() + 0.5;
            s.lineN = projector.segment(fx, fy, fz, fx + (x - fx) * grow, fy + (y - fy) * grow, fz + (z - fz) * grow,
                    s.lines, 0);
        }
        if (grow >= 1.0F) {
            s.diamond = diamond(x, y, z, s.points);
        }
        return s;
    }

    /** A ring rises through the block filling the faces below it, then the fill fades. */
    private Shape sweep(WorldRenderer wr, BlockPos pos, float age, float duration) {
        float rise = Math.min(SWEEP_RISE, duration / 2.0F); // so a short fade still gets its rise
        float k = Math.min(1.0F, age / rise);
        Shape s = new Shape(1.0F - smooth(rise, duration, age));
        AxisAlignedBB b = box(pos);
        double level = b.minY + (b.maxY - b.minY) * easeOut(k);
        RenderUtil.appendFilledBox(wr, new AxisAlignedBB(b.minX, b.minY, b.minZ, b.maxX, level, b.maxZ),
                color.red(), color.green(), color.blue(), alpha(SWEEP_FILL * s.f));
        if (k < 1.0F) {
            s.ringN = projector.segment(b.minX, level, b.minZ, b.maxX, level, b.minZ, s.points, 0);
            s.ringN = projector.segment(b.maxX, level, b.minZ, b.maxX, level, b.maxZ, s.points, s.ringN);
            s.ringN = projector.segment(b.maxX, level, b.maxZ, b.minX, level, b.maxZ, s.points, s.ringN);
            s.ringN = projector.segment(b.minX, level, b.maxZ, b.minX, level, b.minZ, s.points, s.ringN);
        }
        s.lineN = projector.edges(b, true, s.lines, 0);
        return s;
    }

    /** The box pulls back into corner brackets over its darkened front faces. */
    private Shape corners(WorldRenderer wr, BlockPos pos, float age, float duration) {
        Shape s = new Shape(1.0F - smooth(CORNER_FADE * duration, duration, age));
        AxisAlignedBB b = box(pos);
        RenderUtil.appendFilledBox(wr, b, 0x0E, 0x10, 0x15, alpha(CORNER_FILL * s.f));
        float reach = ARM_FROM - (ARM_FROM - ARM_TO) * easeOut(Math.min(1.0F, age / CORNER_EASE));
        projectArms(b, reach, s.lines, s.shown);
        return s;
    }

    /** The diamond's corners as x,y pairs; false when one is off the view. */
    private boolean diamond(double x, double y, double z, float[] out) {
        for (int i = 0; i < 4; i++) {
            ProjectionUtil.Point p = projector.point(x + DIAMOND_X[i], y, z + DIAMOND_Z[i]);
            if (p == null) {
                return false;
            }
            out[i * 2] = p.x;
            out[i * 2 + 1] = p.y;
        }
        return true;
    }

    /** Three arms per corner, each {@code reach} of the way along its edge, stored at arm * 4. */
    private void projectArms(AxisAlignedBB box, float reach, float[] out, boolean[] shown) {
        for (int i = 0; i < 8; i++) {
            double x = (i & 1) == 0 ? box.minX : box.maxX;
            double y = (i & 2) == 0 ? box.minY : box.maxY;
            double z = (i & 4) == 0 ? box.minZ : box.maxZ;
            for (int k = 0; k < 3; k++) {
                double ex = k == 0 ? x + ((i & 1) == 0 ? 1 : -1) * (box.maxX - box.minX) * reach : x;
                double ey = k == 1 ? y + ((i & 2) == 0 ? 1 : -1) * (box.maxY - box.minY) * reach : y;
                double ez = k == 2 ? z + ((i & 4) == 0 ? 1 : -1) * (box.maxZ - box.minZ) * reach : z;
                int arm = i * 3 + k;
                shown[arm] = projector.segment(x, y, z, ex, ey, ez, out, arm * 4) > arm * 4;
            }
        }
    }

    // ---- HUD pass

    private void drawScreen(int scale) {
        if (shapes.isEmpty()) {
            return;
        }
        String m = mode.get();
        int accent = color.get();
        // every glow first, the design blurs them on a layer under the lines
        for (Shape s : shapes) {
            if (MODE_TRAIL.equals(m)) {
                ScreenStrokes.glowLines(s.lines, s.lineN, scale, TRAIL_GLOW, accent, TRAIL_GLOW_A * s.f);
            } else if (MODE_SWEEP.equals(m)) {
                ScreenStrokes.glowLines(s.points, s.ringN, scale, RING_GLOW, accent, 1.0F);
            } else {
                for (int i = 0; i < GLOW_W.length; i++) {
                    strokeArms(s.lines, s.shown, scale, GLOW_W[i] * CORNER_GLOW,
                            Theme.withAlpha(accent, alpha(GLOW_A[i] * CORNER_GLOW_A * s.f)));
                }
            }
        }
        for (Shape s : shapes) {
            if (MODE_TRAIL.equals(m)) {
                ScreenStrokes.lines(s.lines, s.lineN, scale, TRAIL_W, Theme.withAlpha(accent, alpha(s.f)));
                if (s.diamond) {
                    drawDiamond(s.points, scale, Theme.withAlpha(0xFFFFFF, alpha(s.f)), Theme.withAlpha(accent, alpha(s.f)));
                }
            } else if (MODE_SWEEP.equals(m)) {
                ScreenStrokes.lines(s.points, s.ringN, scale, RING_W, RING);
                ScreenStrokes.lines(s.lines, s.lineN, scale, EDGE_W, Theme.withAlpha(accent, alpha(EDGE_A * s.f)));
            } else {
                strokeArms(s.lines, s.shown, scale, UNDER_W, Theme.withAlpha(0, alpha(UNDER_A * s.f)));
                strokeArms(s.lines, s.shown, scale, LINE_W, Theme.withAlpha(accent, alpha(s.f)));
            }
        }
        shapes.clear();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        GlStateManager.enableBlend();
    }

    private static void drawDiamond(float[] p, float scale, int fill, int edge) {
        float ax = p[0] / scale, ay = p[1] / scale, bx = p[2] / scale, by = p[3] / scale;
        float cx = p[4] / scale, cy = p[5] / scale, dx = p[6] / scale, dy = p[7] / scale;
        // a thin same-colour stroke hides the seam between the two halves
        GlassShader.triangle(ax, ay, bx, by, cx, cy, fill, fill, 0.3F);
        GlassShader.triangle(ax, ay, cx, cy, dx, dy, fill, fill, 0.3F);
        GlassShader.polyline(ax, ay, bx, by, cx, cy, DIAMOND_W, edge);
        GlassShader.polyline(cx, cy, dx, dy, ax, ay, DIAMOND_W, edge);
    }

    /** One stroke of every corner. Two arms share a joined polyline so a translucent stroke does not double up at the corner. */
    private static void strokeArms(float[] arms, boolean[] shown, float scale, float width, int color) {
        for (int i = 0; i < 8; i++) {
            int a = i * 3, b = a + 1, c = a + 2;
            if (shown[a] && shown[b] && arms[a * 4] == arms[b * 4] && arms[a * 4 + 1] == arms[b * 4 + 1]) {
                GlassShader.polyline(arms[a * 4 + 2] / scale, arms[a * 4 + 3] / scale, arms[a * 4] / scale,
                        arms[a * 4 + 1] / scale, arms[b * 4 + 2] / scale, arms[b * 4 + 3] / scale, width, color);
            } else {
                strokeArm(arms, shown, a, scale, width, color);
                strokeArm(arms, shown, b, scale, width, color);
            }
            strokeArm(arms, shown, c, scale, width, color);
        }
    }

    private static void strokeArm(float[] arms, boolean[] shown, int arm, float scale, float width, int color) {
        if (shown[arm]) {
            int o = arm * 4;
            GlassShader.line(arms[o] / scale, arms[o + 1] / scale, arms[o + 2] / scale, arms[o + 3] / scale, width, color);
        }
    }

    private static AxisAlignedBB box(BlockPos pos) {
        return new AxisAlignedBB(pos, pos.add(1, 1, 1)).expand(PAD, PAD, PAD);
    }

    private static float smooth(float e0, float e1, float x) {
        float k = MathHelper.clamp_float((x - e0) / (e1 - e0), 0.0F, 1.0F);
        return k * k * (3.0F - 2.0F * k);
    }

    private static float easeOut(float k) {
        float u = 1.0F - k;
        return 1.0F - u * u * u;
    }

    private static int alpha(float a) {
        return Math.round(255.0F * a);
    }

    private static final class Placed {
        final long at;
        final BlockPos from; // the block placed just before, when it touches this one

        Placed(long at, BlockPos from) {
            this.at = at;
            this.from = from;
        }
    }

    /** One block's projection for the HUD pass, framebuffer px. */
    private static final class Shape {
        final float f;
        final float[] lines = new float[96]; // trail segment, sweep edges or corner arms
        final float[] points = new float[16]; // trail diamond or sweep ring
        final boolean[] shown = new boolean[24];
        int lineN, ringN;
        boolean diamond;

        Shape(float f) {
            this.f = f;
        }
    }
}
