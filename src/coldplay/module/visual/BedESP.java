package coldplay.module.visual;

import coldplay.event.EventRender2D;
import coldplay.event.EventRender3D;
import coldplay.event.EventRenderMirror;
import coldplay.event.EventTarget;
import coldplay.event.EventUpdate;
import coldplay.gui.Glass;
import coldplay.gui.GlassShader;
import coldplay.gui.Theme;
import coldplay.module.Category;
import coldplay.module.Module;
import coldplay.setting.ColorSetting;
import coldplay.setting.ModeSetting;
import coldplay.setting.NumberSetting;
import coldplay.util.BedUtil;
import coldplay.util.ProjectionUtil;
import coldplay.util.RenderUtil;
import coldplay.util.ScreenProjector;
import coldplay.util.ScreenStrokes;
import coldplay.util.font.CustomFont;
import coldplay.util.font.FontRef;
import coldplay.util.font.Fonts;

import net.minecraft.block.Block;
import net.minecraft.block.BlockBed;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;

import org.lwjgl.opengl.GL11;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Beds have no tile entity in 1.8.9, so this is a throttled block scan. Fills and beams draw in the world, lines and cards on the HUD. */
public class BedESP extends Module {
    private static final String MODE_CARD = "Card";
    private static final String MODE_BEAM = "Beam";
    private static final String MODE_GLOW = "Glow";
    private static final int SCAN_INTERVAL = 20;
    private static final double BED_H = 9.0 / 16.0;
    private static final int DEFENSE_OUT = 3; // blocks around the bed counted as its defense
    private static final int DEFENSE_UP = 3;
    private static final String NAME = "Bed";

    // Sizes are GUI px; the design's board px times 0.75.
    private static final int GLOW_FILL = 153;
    private static final float GLOW_SPREAD = 1.15F;
    private static final int GLOW_LINE = 0xF2FFBEBE;
    private static final float HULL_W = 1.05F;
    private static final int CARD_FILL = 36;
    private static final float CARD_GLOW = 0.7F;
    private static final float UNDER_W = 2.25F;
    private static final int UNDER = 0x59000000;
    private static final float CARD_LINE_W = 1.125F;
    private static final int CARD_LINE = 0xFFFF7878;
    private static final int BEAM_FILL = 46;
    private static final int BEAM_LINE = 242;
    private static final double BEAM_H = 11.0;
    private static final double BEAM_HALO = 0.96;
    private static final double BEAM_CORE = 0.15;

    private static final double CARD_LIFT = 2.8; // blocks above the bed top, clear of a usual defense
    private static final float CARD_RAISE = 3.0F;
    private static final float CARD_W = 141.0F;
    private static final float CARD_R = 8.25F;
    // the design's padding plus its 1 px border
    private static final float CARD_PAD_T = 6.75F;
    private static final float CARD_PAD_X = 8.25F;
    private static final float CARD_PAD_B = 7.5F;
    private static final float TITLE_H = 12.0F;
    private static final float TITLE_GAP = 5.25F;
    private static final float ROW_GAP = 4.5F;
    private static final float COVER_H = 10.5F;
    private static final float COVER_GAP = 4.5F;
    private static final float SWATCH = 6.0F;
    private static final float SWATCH_R = 1.5F;
    private static final float SWATCH_SPACE = 3.0F;
    private static final int SWATCH_EDGE = 0x40000000;
    private static final int GLASS_SWATCH = 0x66FFFFFF;

    private static final float CHIP_H = 16.5F;
    private static final float CHIP_PAD_L = 4.5F;
    private static final float CHIP_PAD_R = 6.75F;
    private static final float CHIP_GAP = 4.5F;
    private static final float CHIP_DROP = 1.5F;
    private static final int DOT = 0xFFF0505A;
    private static final float ICON = 10.5F;
    private static final int TEXT = 0xFFF4F6F8;
    private static final int TEXT_DIM = 0x9EF4F6F8;
    private static final Glass CHIP_GLASS = new Glass(0x800E1015, 0x800E1015, 0x2EFFFFFF, 10.5F, 1.4F, 16.5F, 6.0F, 0.26F);
    private static final FontRef TITLE_FONT = new FontRef(Fonts.GEIST_SEMIBOLD, 9.375F);
    private static final FontRef LABEL_FONT = new FontRef(Fonts.GEIST_SEMIBOLD, 9.0F);
    private static final FontRef VALUE_FONT = new FontRef(Fonts.GEIST_MONO_MEDIUM, 8.25F);
    private static final FontRef COVER_FONT = new FontRef(Fonts.GEIST, 7.875F);
    private static final FontRef COUNT_FONT = new FontRef(Fonts.GEIST_MONO, 7.875F);

    private final ModeSetting mode = add(new ModeSetting("Mode", MODE_CARD, MODE_CARD, MODE_BEAM, MODE_GLOW)
            .describe("Card with distance and the two most used defense blocks, a Beam you can see from afar, or a Glow outline."));
    private final NumberSetting range = add(new NumberSetting("Range", 64.0, 16.0, 256.0, 16.0).describe("How far to scan for beds, in blocks."));
    private final ColorSetting color = add(new ColorSetting("Color", 0xFF3C3C).describe("Accent colour for beds."));

    private final List<Bed> beds = new ArrayList<Bed>();
    private final List<Shape> shapes = new ArrayList<Shape>();
    private final ScreenProjector projector = new ScreenProjector();
    private int scanCooldown;

    public BedESP() {
        super("BedESP", Category.VISUAL, "Highlights beds through walls.");
    }

    @Override
    protected void onDisable() {
        beds.clear();
        shapes.clear();
        scanCooldown = 0;
    }

    @EventTarget
    public void onUpdate(EventUpdate event) {
        if (!event.isPre()) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP player = mc.thePlayer;
        WorldClient world = mc.theWorld;
        if (player == null || world == null) {
            beds.clear();
            return;
        }
        if (--scanCooldown > 0) {
            return;
        }
        scanCooldown = SCAN_INTERVAL;
        beds.clear();
        double maxDistSq = range.get() * range.get();
        Set<BlockPos> feet = new HashSet<BlockPos>();
        for (BlockPos pos : BedUtil.findBedHalvesInRange(world, player, range.get().intValue(), maxDistSq)) {
            IBlockState state = world.getBlockState(pos);
            EnumFacing facing = state.getValue(BlockBed.FACING);
            BlockPos foot = state.getValue(BlockBed.PART) == BlockBed.EnumPartType.HEAD ? pos.offset(facing.getOpposite()) : pos;
            if (!feet.add(foot)) {
                continue;
            }
            // one box over both halves
            BlockPos head = foot.offset(facing);
            int x0 = Math.min(foot.getX(), head.getX()), x1 = Math.max(foot.getX(), head.getX());
            int z0 = Math.min(foot.getZ(), head.getZ()), z1 = Math.max(foot.getZ(), head.getZ());
            int y = foot.getY();
            AxisAlignedBB box = new AxisAlignedBB(x0, y, z0, x1 + 1, y + BED_H, z1 + 1);
            beds.add(new Bed(box, defense(world, new BlockPos(x0 - DEFENSE_OUT, y, z0 - DEFENSE_OUT),
                    new BlockPos(x1 + DEFENSE_OUT, y + DEFENSE_UP, z1 + DEFENSE_OUT))));
        }
    }

    /** The two blocks the defense uses most, by item name. */
    private static List<Cover> defense(WorldClient world, BlockPos from, BlockPos to) {
        Map<String, Cover> found = new HashMap<String, Cover>();
        for (BlockPos pos : BlockPos.getAllInBox(from, to)) {
            IBlockState state = world.getBlockState(pos);
            Block block = state.getBlock();
            if (block == Blocks.air || block == Blocks.bed) {
                continue;
            }
            String name = blockName(world, pos, block);
            Cover cover = found.get(name);
            if (cover == null) {
                // glass and a few others have no map colour
                int rgb = block.getMapColor(state).colorValue;
                cover = new Cover(name, rgb == 0 ? GLASS_SWATCH : 0xFF000000 | rgb);
                found.put(name, cover);
            }
            cover.count++;
        }
        List<Cover> list = new ArrayList<Cover>(found.values());
        list.sort((a, b) -> b.count - a.count);
        return list.subList(0, Math.min(2, list.size()));
    }

    private static String blockName(WorldClient world, BlockPos pos, Block block) {
        Item item = block.getItem(world, pos);
        if (item == null) {
            return block.getLocalizedName();
        }
        return new ItemStack(item, 1, block.getDamageValue(world, pos)).getDisplayName();
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
            // the mirror shows its texture flipped and frost reads the main frame, so no cards there
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
        if (beds.isEmpty()) {
            return;
        }
        String m = mode.get();
        projector.capture();
        drawFills(m);
        if (MODE_BEAM.equals(m)) {
            drawBeams();
        }
        for (Bed bed : beds) {
            Shape shape = new Shape(bed);
            AxisAlignedBB b = bed.box;
            double cx = (b.minX + b.maxX) / 2.0, cz = (b.minZ + b.maxZ) / 2.0;
            shape.hullN = projector.hull(b, shape.hull);
            shape.distance = projector.distance(cx, b.maxY, cz);
            if (MODE_CARD.equals(m)) {
                shape.anchor = projector.point(cx, b.maxY + CARD_LIFT, cz);
            } else if (MODE_BEAM.equals(m)) {
                shape.anchor = projector.point(cx, b.maxY + BEAM_H, cz);
            }
            shapes.add(shape);
        }
        // far cards first so near ones sit on top
        shapes.sort((a, c) -> Double.compare(c.distance, a.distance));
    }

    private void drawFills(String m) {
        Tessellator tessellator = Tessellator.getInstance();
        WorldRenderer wr = tessellator.getWorldRenderer();
        int r = color.red(), g = color.green(), b = color.blue();
        int a = MODE_GLOW.equals(m) ? GLOW_FILL : MODE_CARD.equals(m) ? CARD_FILL : BEAM_FILL;
        RenderUtil.beginWorldOverlay(1.0F);
        GlStateManager.enableCull(); // only the faces you see, like the design's front faces
        wr.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR);
        for (Bed bed : beds) {
            RenderUtil.appendFilledBox(wr, bed.box.expand(0.002, 0.002, 0.002), r, g, b, a);
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
        for (Bed bed : beds) {
            AxisAlignedBB box = bed.box;
            double cx = (box.minX + box.maxX) / 2.0, cz = (box.minZ + box.maxZ) / 2.0;
            double dx = cx - projector.viewerX(), dz = cz - projector.viewerZ();
            double len = Math.sqrt(dx * dx + dz * dz);
            if (len < 0.001) {
                continue;
            }
            double rx = -dz / len, rz = dx / len;
            double[] ys = {box.maxY, box.maxY + BEAM_H * 0.35, box.maxY + BEAM_H};
            int[] haloA = {153, 69, 0};
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

    // ---- HUD pass

    private void drawScreen(int scale, boolean hud) {
        if (shapes.isEmpty()) {
            return;
        }
        String m = mode.get();
        int accent = 0xFF000000 | color.get();
        for (Shape s : shapes) {
            if (MODE_GLOW.equals(m)) {
                ScreenStrokes.glowLoop(s.hull, s.hullN, scale, GLOW_SPREAD, accent, 1.0F);
                ScreenStrokes.loop(s.hull, s.hullN, scale, HULL_W, GLOW_LINE);
            } else if (MODE_CARD.equals(m)) {
                ScreenStrokes.glowLoop(s.hull, s.hullN, scale, CARD_GLOW, accent, 1.0F);
                ScreenStrokes.loop(s.hull, s.hullN, scale, UNDER_W, UNDER);
                ScreenStrokes.loop(s.hull, s.hullN, scale, CARD_LINE_W, CARD_LINE);
            } else {
                ScreenStrokes.loop(s.hull, s.hullN, scale, HULL_W, Theme.withAlpha(accent, BEAM_LINE));
            }
        }
        if (hud && !MODE_GLOW.equals(m)) {
            Fonts.load(scale); // lazy init, needs a live GL context
            if (Fonts.isLoaded()) {
                GlassShader.capture();
                if (MODE_CARD.equals(m)) {
                    drawCards(scale);
                } else {
                    drawChips(scale);
                }
            }
        }
        shapes.clear();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        GlStateManager.enableBlend();
    }

    private void drawCards(int scale) {
        CustomFont titleFont = TITLE_FONT.get();
        CustomFont valueFont = VALUE_FONT.get();
        CustomFont coverFont = COVER_FONT.get();
        CustomFont countFont = COUNT_FONT.get();
        for (Shape s : shapes) {
            if (s.anchor == null) {
                continue;
            }
            List<Cover> covers = s.bed.defense;
            // long block names widen the card instead of spilling out
            float row = 0.0F;
            for (Cover c : covers) {
                row += SWATCH + COVER_GAP + coverFont.getStringWidth(c.name + " ") + countFont.getStringWidth(Integer.toString(c.count))
                        + COVER_GAP + SWATCH_SPACE;
            }
            float w = Math.max(CARD_W, CARD_PAD_X * 2.0F + row - COVER_GAP - SWATCH_SPACE);
            float h = CARD_PAD_T + TITLE_H + ROW_GAP + COVER_H + CARD_PAD_B;
            float x = s.anchor.x / scale - w / 2.0F;
            float y = s.anchor.y / scale - CARD_RAISE - h;
            GlassShader.frost(x, y, w, h, CARD_R, Glass.SMOKE_PANEL);

            float left = x + CARD_PAD_X;
            float top = y + CARD_PAD_T;
            bedIcon(left, top + (TITLE_H - ICON) / 2.0F);
            String value = Math.round(s.distance) + "m";
            float titleY = top + (TITLE_H - titleFont.getHeight()) / 2.0F;
            titleFont.drawString(NAME, left + ICON + TITLE_GAP, titleY, TEXT);
            valueFont.drawString(value, x + w - CARD_PAD_X - valueFont.getStringWidth(value),
                    titleY + titleFont.getAscent() - valueFont.getAscent(), TEXT_DIM);

            float rowY = top + TITLE_H + ROW_GAP;
            float textY = rowY + (COVER_H - coverFont.getHeight()) / 2.0F;
            float countY = textY + coverFont.getAscent() - countFont.getAscent();
            if (covers.isEmpty()) {
                coverFont.drawString("No defense", left, textY, TEXT_DIM);
            }
            float cx = left;
            for (Cover c : covers) {
                float sy = rowY + (COVER_H - SWATCH) / 2.0F;
                GlassShader.rect(cx, sy, SWATCH, SWATCH, SWATCH_R, c.color, c.color);
                GlassShader.stroke(cx, sy, SWATCH, SWATCH, SWATCH_R, SWATCH_EDGE);
                cx += SWATCH + COVER_GAP;
                String label = c.name + " ";
                coverFont.drawString(label, cx, textY, TEXT_DIM);
                cx += coverFont.getStringWidth(label);
                String count = Integer.toString(c.count);
                countFont.drawString(count, cx, countY, TEXT);
                cx += countFont.getStringWidth(count) + COVER_GAP + SWATCH_SPACE;
            }
        }
    }

    private void drawChips(int scale) {
        CustomFont labelFont = LABEL_FONT.get();
        CustomFont valueFont = VALUE_FONT.get();
        float u = ICON / 16.0F;
        for (Shape s : shapes) {
            if (s.anchor == null) {
                continue;
            }
            String value = Math.round(s.distance) + "m";
            int labelW = labelFont.getStringWidth(NAME);
            int valueW = valueFont.getStringWidth(value);
            float w = CHIP_PAD_L + ICON + CHIP_GAP + labelW + CHIP_GAP + valueW + CHIP_PAD_R;
            float x = s.anchor.x / scale - w / 2.0F;
            float y = s.anchor.y / scale + CHIP_DROP - CHIP_H;
            GlassShader.frost(x, y, w, CHIP_H, CHIP_H / 2.0F, CHIP_GLASS);
            float iconX = x + CHIP_PAD_L, iconY = y + (CHIP_H - ICON) / 2.0F;
            GlassShader.ellipse(iconX + 8.0F * u, iconY + 8.0F * u, 4.5F * u, 4.5F * u, DOT, DOT, 0.0F);
            float labelX = iconX + ICON + CHIP_GAP;
            float labelY = y + (CHIP_H - labelFont.getHeight()) / 2.0F;
            labelFont.drawString(NAME, labelX, labelY, TEXT);
            valueFont.drawString(value, labelX + labelW + CHIP_GAP, labelY + labelFont.getAscent() - valueFont.getAscent(), TEXT_DIM);
        }
    }

    /** The design's isometric bed, drawn on a 16 unit grid. */
    private static void bedIcon(float x, float y) {
        float u = ICON / 16.0F;
        quad(x, y, u, new float[]{8, 5, 15, 8.5F, 8, 12, 1, 8.5F}, 0xFFC23A33, true);
        quad(x, y, u, new float[]{1, 8.5F, 8, 12, 8, 15, 1, 11.5F}, 0xFF9C2B25, true);
        quad(x, y, u, new float[]{8, 12, 15, 8.5F, 15, 11.5F, 8, 15}, 0xFF82231E, true);
        quad(x, y, u, new float[]{11.2F, 6.6F, 15, 8.5F, 13.1F, 9.45F, 9.3F, 7.55F}, 0xFFF2EFE6, false);
    }

    private static void quad(float x, float y, float u, float[] p, int fill, boolean edged) {
        // a thin same-colour stroke hides the seam between the two halves
        GlassShader.triangle(x + p[0] * u, y + p[1] * u, x + p[2] * u, y + p[3] * u, x + p[4] * u, y + p[5] * u, fill, fill, 0.3F);
        GlassShader.triangle(x + p[0] * u, y + p[1] * u, x + p[4] * u, y + p[5] * u, x + p[6] * u, y + p[7] * u, fill, fill, 0.3F);
        if (!edged) {
            return;
        }
        for (int i = 0; i < 4; i++) {
            int j = (i + 1) % 4;
            GlassShader.line(x + p[i * 2] * u, y + p[i * 2 + 1] * u, x + p[j * 2] * u, y + p[j * 2 + 1] * u, 0.6F * u, 0x59000000);
        }
    }

    private static final class Bed {
        final AxisAlignedBB box;
        final List<Cover> defense;

        Bed(AxisAlignedBB box, List<Cover> defense) {
            this.box = box;
            this.defense = defense;
        }
    }

    private static final class Cover {
        final String name;
        final int color;
        int count;

        Cover(String name, int color) {
            this.name = name;
            this.color = color;
        }
    }

    /** One bed's projection for the HUD pass, framebuffer px. */
    private static final class Shape {
        final Bed bed;
        final float[] hull = new float[16];
        int hullN;
        ProjectionUtil.Point anchor;
        double distance;

        Shape(Bed bed) {
            this.bed = bed;
        }
    }
}
