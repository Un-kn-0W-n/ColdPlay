package coldplay.module.visual;

import coldplay.event.EventOpenWindow;
import coldplay.event.EventPriority;
import coldplay.event.EventRender2D;
import coldplay.event.EventRender3D;
import coldplay.event.EventRenderMirror;
import coldplay.event.EventTarget;
import coldplay.event.EventUpdate;
import coldplay.event.EventUse;
import coldplay.gui.Glass;
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
import coldplay.util.font.CustomFont;
import coldplay.util.font.FontRef;
import coldplay.util.font.Fonts;

import net.minecraft.block.Block;
import net.minecraft.block.BlockChest;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityChest;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.world.World;

import org.lwjgl.opengl.GL11;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Chests are scanned on tick; fills and beams draw in the world, lines and tags on the HUD. */
public class ChestESP extends Module {
    private static final String MODE_GLOW = "Glow";
    private static final String MODE_MODEL = "Model";
    private static final String MODE_TAG = "Tag";
    private static final String MODE_BEAM = "Beam";

    // Sizes are GUI px; the design's board px times 0.75.
    private static final int GLOW_FILL = 130;
    private static final float HULL_W = 1.05F;
    private static final int HULL_LIGHT = 0xF2FFECA0;
    private static final float LID_GLOW = 0.7F;
    private static final float UNDER_W = 2.25F;
    private static final int UNDER = 0x61000000;
    private static final float LINE_W = 0.975F;
    private static final int LINE_LIGHT = 0xF2FFF0BE;
    private static final float EDGE_W = 0.9F;
    private static final int EDGE_OPENED = 0x52FFFFFF;
    private static final double SEAM = 10.0 / 16.0; // ModelChest base height
    private static final double TAG_LIFT = 0.35; // blocks above the lid
    private static final float CHIP_H = 16.5F;
    private static final float CHIP_PAD_L = 4.5F;
    private static final float CHIP_PAD_R = 6.75F;
    private static final float CHIP_GAP = 4.5F;
    private static final float ICON = 10.5F;
    private static final float OPENED_ALPHA = 0.55F;
    private static final int TEXT = 0xFFF4F6F8;
    private static final int TEXT_DIM = 0x9EF4F6F8;
    private static final Glass CHIP_GLASS = new Glass(0x800E1015, 0x800E1015, 0x2EFFFFFF, 10.5F, 1.4F, 16.5F, 6.0F, 0.26F);
    private static final FontRef LABEL_FONT = new FontRef(Fonts.GEIST_SEMIBOLD, 9.0F);
    private static final FontRef VALUE_FONT = new FontRef(Fonts.GEIST_MONO_MEDIUM, 8.25F);
    private static final double BEAM_H = 9.0;
    private static final double BEAM_HALO = 0.7;
    private static final double BEAM_CORE = 0.105;
    private static final long OPEN_WINDOW_MS = 3000L;

    private final ModeSetting mode = add(new ModeSetting("Mode", MODE_GLOW, MODE_GLOW, MODE_MODEL, MODE_TAG, MODE_BEAM)
            .describe("Glow outline, Model lines (lid, body, latch), Tag with distance that dims opened chests, or a Beam."));
    private final NumberSetting range = add(new NumberSetting("Range", 64.0, 16.0, 256.0, 16.0).describe("How far to scan for chests, in blocks."));
    private final ColorSetting color = add(new ColorSetting("Color", 0xC8AA00).describe("Accent colour for chests."));

    private final List<Chest> chests = new ArrayList<Chest>();
    private final List<Shape> shapes = new ArrayList<Shape>();
    private final ScreenProjector projector = new ScreenProjector();

    // opened this world; both halves of a double chest count
    private final Set<BlockPos> opened = new HashSet<BlockPos>();
    private BlockPos using;
    private long usingAt;
    private World trackedWorld;

    public ChestESP() {
        super("ChestESP", Category.VISUAL, "Highlights chests through walls.");
    }

    @Override
    protected void onDisable() {
        chests.clear();
        shapes.clear();
        opened.clear();
        using = null;
        trackedWorld = null;
    }

    @EventTarget(priority = EventPriority.STATE_TRACKING)
    public void onUse(EventUse event) {
        MovingObjectPosition hit = event.getTarget();
        WorldClient world = Minecraft.getMinecraft().theWorld;
        if (world != null && hit != null && hit.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK
                && world.getBlockState(hit.getBlockPos()).getBlock() instanceof BlockChest) {
            using = hit.getBlockPos();
            usingAt = System.currentTimeMillis();
        }
    }

    // above ChestStealer, which cancels the window in its silent mode
    @EventTarget(priority = EventPriority.STATE_TRACKING)
    public void onOpenWindow(EventOpenWindow event) {
        String id = event.getGuiId();
        if (using != null && System.currentTimeMillis() - usingAt < OPEN_WINDOW_MS
                && ("minecraft:chest".equals(id) || "minecraft:container".equals(id))) {
            opened.add(using);
            using = null;
        }
    }

    @EventTarget
    public void onUpdate(EventUpdate event) {
        if (!event.isPre()) {
            return;
        }
        chests.clear();
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP player = mc.thePlayer;
        WorldClient world = mc.theWorld;
        if (world != trackedWorld) {
            opened.clear();
            trackedWorld = world;
        }
        if (player == null || world == null) {
            return;
        }
        double maxDistSq = range.get() * range.get();
        for (TileEntity tileEntity : world.loadedTileEntityList) {
            if (!(tileEntity instanceof TileEntityChest)) {
                continue;
            }
            BlockPos pos = tileEntity.getPos();
            if (pos.distanceSq(player.posX, player.posY, player.posZ) > maxDistSq) {
                continue;
            }
            Block block = world.getBlockState(pos).getBlock();
            if (!(block instanceof BlockChest)) {
                continue;
            }
            // a double chest is drawn once, from its west or north half
            if (world.getBlockState(pos.west()).getBlock() == block || world.getBlockState(pos.north()).getBlock() == block) {
                continue;
            }
            block.setBlockBoundsBasedOnState(world, pos);
            AxisAlignedBB box = block.getSelectedBoundingBox(world, pos);
            BlockPos partner = null;
            if (world.getBlockState(pos.east()).getBlock() == block) {
                partner = pos.east();
            } else if (world.getBlockState(pos.south()).getBlock() == block) {
                partner = pos.south();
            }
            if (partner != null) {
                block.setBlockBoundsBasedOnState(world, partner);
                box = box.union(block.getSelectedBoundingBox(world, partner));
            }
            EnumFacing facing = world.getBlockState(pos).getValue(BlockChest.FACING);
            boolean seen = opened.contains(pos) || (partner != null && opened.contains(partner));
            chests.add(new Chest(box, facing, partner != null, seen));
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
            // the mirror shows its texture flipped and frost reads the main frame, so no tags there
            drawScreen(event.getScale(), false);
        }
    }

    @EventTarget
    public void onRender2D(EventRender2D event) {
        drawScreen(event.getResolution().getScaleFactor(), true);
    }

    // ---- world pass

    private void renderView() {
        shapes.clear();
        if (chests.isEmpty()) {
            return;
        }
        String m = mode.get();
        projector.capture();
        drawFills(m);
        if (MODE_BEAM.equals(m)) {
            drawBeams();
        }
        for (Chest chest : chests) {
            Shape shape = new Shape(chest);
            AxisAlignedBB b = chest.box;
            if (MODE_GLOW.equals(m) || MODE_BEAM.equals(m)) {
                shape.hullN = projector.hull(b, shape.hull);
            } else if (MODE_MODEL.equals(m)) {
                double seam = b.minY + SEAM;
                AxisAlignedBB body = new AxisAlignedBB(b.minX, b.minY, b.minZ, b.maxX, seam, b.maxZ);
                AxisAlignedBB lid = new AxisAlignedBB(b.minX, seam, b.minZ, b.maxX, b.maxY, b.maxZ);
                shape.lineN = projector.edges(body, true, shape.lines, 0);
                shape.lineN = projector.edges(lid, false, shape.lines, shape.lineN);
                shape.lineN = latch(chest, shape.lines, shape.lineN);
                shape.glowN = projector.edges(lid, true, shape.glow, 0);
            } else {
                shape.lineN = projector.edges(b, true, shape.lines, 0);
                double cx = (b.minX + b.maxX) / 2.0, cz = (b.minZ + b.maxZ) / 2.0;
                shape.anchor = projector.point(cx, b.maxY + TAG_LIFT, cz);
                shape.distance = projector.distance(cx, b.maxY, cz);
            }
            shapes.add(shape);
        }
        // far tags first so near ones sit on top
        shapes.sort((a, c) -> Double.compare(c.distance, a.distance));
    }

    private void drawFills(String m) {
        Tessellator tessellator = Tessellator.getInstance();
        WorldRenderer wr = tessellator.getWorldRenderer();
        int r = color.red(), g = color.green(), b = color.blue();
        RenderUtil.beginWorldOverlay(1.0F);
        GlStateManager.enableCull(); // only the faces you see, like the design's front faces
        wr.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR);
        for (Chest chest : chests) {
            AxisAlignedBB box = chest.box.expand(0.002, 0.002, 0.002);
            if (MODE_GLOW.equals(m)) {
                RenderUtil.appendFilledBox(wr, box, r, g, b, GLOW_FILL);
            } else if (MODE_MODEL.equals(m)) {
                double seam = chest.box.minY + SEAM;
                RenderUtil.appendFilledBox(wr, new AxisAlignedBB(box.minX, box.minY, box.minZ, box.maxX, seam, box.maxZ), 0x0E, 0x10, 0x15, 66);
                RenderUtil.appendFilledBox(wr, new AxisAlignedBB(box.minX, seam, box.minZ, box.maxX, box.maxY, box.maxZ), r, g, b, 77);
            } else if (MODE_TAG.equals(m)) {
                if (!chest.opened) {
                    RenderUtil.appendFilledBox(wr, box, r, g, b, 31);
                }
            } else {
                RenderUtil.appendFilledBox(wr, box, r, g, b, 36);
            }
        }
        tessellator.draw();
        GlStateManager.disableCull();
        RenderUtil.endWorldOverlay();
    }

    /** A soft column that fades out upward, and a thin bright core, both turned to face the camera. */
    private void drawBeams() {
        Tessellator tessellator = Tessellator.getInstance();
        WorldRenderer wr = tessellator.getWorldRenderer();
        int r = color.red(), g = color.green(), b = color.blue();
        RenderUtil.beginWorldOverlay(1.0F);
        GlStateManager.shadeModel(GL11.GL_SMOOTH);
        wr.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR);
        for (Chest chest : chests) {
            AxisAlignedBB box = chest.box;
            double cx = (box.minX + box.maxX) / 2.0, cz = (box.minZ + box.maxZ) / 2.0;
            double dx = cx - projector.viewerX(), dz = cz - projector.viewerZ();
            double len = Math.sqrt(dx * dx + dz * dz);
            if (len < 0.001) {
                continue;
            }
            double rx = -dz / len, rz = dx / len;
            double[] ys = {box.maxY, box.maxY + BEAM_H * 0.35, box.maxY + BEAM_H};
            int[] haloA = {140, 64, 0};
            double h = BEAM_HALO / 2.0;
            for (int i = 0; i < 2; i++) {
                for (int side = -1; side <= 1; side += 2) {
                    wr.pos(cx, ys[i], cz).color(r, g, b, haloA[i]).endVertex();
                    wr.pos(cx + rx * h * side, ys[i], cz + rz * h * side).color(r, g, b, 0).endVertex();
                    wr.pos(cx + rx * h * side, ys[i + 1], cz + rz * h * side).color(r, g, b, 0).endVertex();
                    wr.pos(cx, ys[i + 1], cz).color(r, g, b, haloA[i + 1]).endVertex();
                }
            }
            double c = BEAM_CORE / 2.0;
            double[] cy = {box.maxY, box.maxY + BEAM_H * 0.3, box.maxY + BEAM_H};
            int[][] coreC = {{255, 255, 255, 230}, {r, g, b, 204}, {r, g, b, 0}};
            for (int i = 0; i < 2; i++) {
                int[] lo = coreC[i], hi = coreC[i + 1];
                wr.pos(cx - rx * c, cy[i], cz - rz * c).color(lo[0], lo[1], lo[2], lo[3]).endVertex();
                wr.pos(cx + rx * c, cy[i], cz + rz * c).color(lo[0], lo[1], lo[2], lo[3]).endVertex();
                wr.pos(cx + rx * c, cy[i + 1], cz + rz * c).color(hi[0], hi[1], hi[2], hi[3]).endVertex();
                wr.pos(cx - rx * c, cy[i + 1], cz - rz * c).color(hi[0], hi[1], hi[2], hi[3]).endVertex();
            }
        }
        tessellator.draw();
        GlStateManager.shadeModel(GL11.GL_FLAT);
        RenderUtil.endWorldOverlay();
    }

    /** The ModelChest knob: 2 px wide, 4 tall, one pixel proud of the front face. */
    private int latch(Chest chest, float[] out, int n) {
        AxisAlignedBB b = chest.box;
        double y0 = b.minY + 7.0 / 16.0, y1 = b.minY + 11.0 / 16.0, half = 1.0 / 16.0, out1 = 1.0 / 16.0;
        double cx = (b.minX + b.maxX) / 2.0, cz = (b.minZ + b.maxZ) / 2.0;
        double[][] q;
        switch (chest.facing) {
            case NORTH:
                q = new double[][]{{cx - half, b.minZ - out1}, {cx + half, b.minZ - out1}};
                break;
            case SOUTH:
                q = new double[][]{{cx - half, b.maxZ + out1}, {cx + half, b.maxZ + out1}};
                break;
            case WEST:
                q = new double[][]{{b.minX - out1, cz - half}, {b.minX - out1, cz + half}};
                break;
            default:
                q = new double[][]{{b.maxX + out1, cz - half}, {b.maxX + out1, cz + half}};
                break;
        }
        n = projector.segment(q[0][0], y0, q[0][1], q[1][0], y0, q[1][1], out, n);
        n = projector.segment(q[1][0], y0, q[1][1], q[1][0], y1, q[1][1], out, n);
        n = projector.segment(q[1][0], y1, q[1][1], q[0][0], y1, q[0][1], out, n);
        return projector.segment(q[0][0], y1, q[0][1], q[0][0], y0, q[0][1], out, n);
    }

    // ---- HUD pass

    private void drawScreen(int scale, boolean hud) {
        if (shapes.isEmpty()) {
            return;
        }
        String m = mode.get();
        int accent = 0xFF000000 | color.get();
        for (Shape s : shapes) {
            if (MODE_GLOW.equals(m)) {
                ScreenStrokes.glowLoop(s.hull, s.hullN, scale, 1.0F, accent, 1.0F);
                ScreenStrokes.loop(s.hull, s.hullN, scale, HULL_W, HULL_LIGHT);
            } else if (MODE_MODEL.equals(m)) {
                ScreenStrokes.glowLines(s.glow, s.glowN, scale, LID_GLOW, accent, 1.0F);
                ScreenStrokes.lines(s.lines, s.lineN, scale, UNDER_W, UNDER);
                ScreenStrokes.lines(s.lines, s.lineN, scale, LINE_W, LINE_LIGHT);
            } else if (MODE_TAG.equals(m)) {
                ScreenStrokes.lines(s.lines, s.lineN, scale, EDGE_W, s.chest.opened ? EDGE_OPENED : Theme.withAlpha(accent, 230));
            } else {
                ScreenStrokes.loop(s.hull, s.hullN, scale, LINE_W, Theme.withAlpha(accent, 230));
            }
        }
        if (hud && MODE_TAG.equals(m)) {
            drawTags(scale);
        }
        shapes.clear();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        GlStateManager.enableBlend();
    }

    private void drawTags(int scale) {
        Fonts.load(scale); // lazy init, needs a live GL context
        if (!Fonts.isLoaded()) {
            return;
        }
        CustomFont labelFont = LABEL_FONT.get();
        CustomFont valueFont = VALUE_FONT.get();
        GlassShader.capture();
        for (Shape s : shapes) {
            if (s.anchor == null) {
                continue;
            }
            float a = s.chest.opened ? OPENED_ALPHA : 1.0F;
            String label = s.chest.opened ? "Opened" : s.chest.pair ? "Double chest" : "Chest";
            String value = Math.round(s.distance) + "m";
            int labelW = labelFont.getStringWidth(label);
            int valueW = valueFont.getStringWidth(value);
            float w = CHIP_PAD_L + ICON + CHIP_GAP + labelW + CHIP_GAP + valueW + CHIP_PAD_R;
            float x = s.anchor.x / scale - w / 2.0F;
            float y = s.anchor.y / scale - CHIP_H;
            GlassShader.frost(x, y, w, CHIP_H, CHIP_H / 2.0F, CHIP_GLASS, a);
            chestIcon(x + CHIP_PAD_L, y + (CHIP_H - ICON) / 2.0F, a);
            float labelX = x + CHIP_PAD_L + ICON + CHIP_GAP;
            float labelY = y + (CHIP_H - labelFont.getHeight()) / 2.0F;
            labelFont.drawString(label, labelX, labelY, Theme.applyAlpha(TEXT, a));
            valueFont.drawString(value, labelX + labelW + CHIP_GAP, labelY + labelFont.getAscent() - valueFont.getAscent(),
                    Theme.applyAlpha(TEXT_DIM, a));
        }
    }

    /** The design's isometric chest, drawn on a 16 unit grid. */
    private static void chestIcon(float x, float y, float a) {
        float u = ICON / 16.0F;
        quad(x, y, u, new float[]{8, 1.5F, 15, 5, 8, 8.5F, 1, 5}, 0xFFB98543, a);
        quad(x, y, u, new float[]{1, 5, 8, 8.5F, 8, 15, 1, 11.5F}, 0xFF8E5F25, a);
        quad(x, y, u, new float[]{8, 8.5F, 15, 5, 15, 11.5F, 8, 15}, 0xFF744C1B, a);
        GlassShader.polyline(x + 1 * u, y + 7.6F * u, x + 8 * u, y + 11.1F * u, x + 15 * u, y + 7.6F * u, 0.8F * u,
                Theme.applyAlpha(0xCC1E1204, a));
        quad(x, y, u, new float[]{3.6F, 8.1F, 5.2F, 8.9F, 5.2F, 11.1F, 3.6F, 10.3F}, 0xFFD9DCDF, a);
    }

    private static void quad(float x, float y, float u, float[] p, int fill, float a) {
        int c = Theme.applyAlpha(fill, a);
        // a thin same-colour stroke hides the seam between the two halves
        GlassShader.triangle(x + p[0] * u, y + p[1] * u, x + p[2] * u, y + p[3] * u, x + p[4] * u, y + p[5] * u, c, c, 0.3F);
        GlassShader.triangle(x + p[0] * u, y + p[1] * u, x + p[4] * u, y + p[5] * u, x + p[6] * u, y + p[7] * u, c, c, 0.3F);
        int edge = Theme.applyAlpha(0x66000000, a);
        for (int i = 0; i < 4; i++) {
            int j = (i + 1) % 4;
            GlassShader.line(x + p[i * 2] * u, y + p[i * 2 + 1] * u, x + p[j * 2] * u, y + p[j * 2 + 1] * u, 0.6F * u, edge);
        }
    }

    private static final class Chest {
        final AxisAlignedBB box;
        final EnumFacing facing;
        final boolean pair;
        final boolean opened;

        Chest(AxisAlignedBB box, EnumFacing facing, boolean pair, boolean opened) {
            this.box = box;
            this.facing = facing;
            this.pair = pair;
            this.opened = opened;
        }
    }

    /** One chest's projection for the HUD pass, framebuffer px. */
    private static final class Shape {
        final Chest chest;
        final float[] hull = new float[16];
        final float[] lines = new float[112];
        final float[] glow = new float[48];
        int hullN, lineN, glowN;
        ProjectionUtil.Point anchor;
        double distance;

        Shape(Chest chest) {
            this.chest = chest;
        }
    }
}
