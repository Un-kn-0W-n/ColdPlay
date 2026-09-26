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
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.world.World;

import org.lwjgl.opengl.GL11;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Chests are scanned on tick; the tint draws in the world, edges and tags on the HUD. */
public class ChestESP extends Module {
    private static final String MODE_TAG = "Tag";

    // Sizes are GUI px; the design's board px times 0.75.
    private static final int FILL = 31;
    private static final float EDGE_W = 0.9F;
    private static final int EDGE_OPENED = 0x52FFFFFF;
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
    private static final long OPEN_WINDOW_MS = 3000L;

    private final ModeSetting mode = add(new ModeSetting("Mode", MODE_TAG, MODE_TAG)
            .describe("Tag with distance above each chest; opened chests dim."));
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
            boolean seen = opened.contains(pos) || (partner != null && opened.contains(partner));
            chests.add(new Chest(box, partner != null, seen));
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
        projector.capture();
        drawFills();
        for (Chest chest : chests) {
            Shape shape = new Shape(chest);
            AxisAlignedBB b = chest.box;
            shape.lineN = projector.visibleEdges(b, shape.lines, 0);
            double cx = (b.minX + b.maxX) / 2.0, cz = (b.minZ + b.maxZ) / 2.0;
            shape.anchor = projector.point(cx, b.maxY + TAG_LIFT, cz);
            shape.distance = projector.distance(cx, b.maxY, cz);
            shapes.add(shape);
        }
        // far tags first so near ones sit on top
        shapes.sort((a, c) -> Double.compare(c.distance, a.distance));
    }

    /** Depth tested, so each chest is tinted once and only where it can be seen; walls in front stay clean. */
    private void drawFills() {
        Tessellator tessellator = Tessellator.getInstance();
        WorldRenderer wr = tessellator.getWorldRenderer();
        int r = color.red(), g = color.green(), b = color.blue();
        RenderUtil.beginWorldOverlay(1.0F);
        GlStateManager.enableDepth();
        // the box sits 0.002 off the model, too close for the depth buffer at range
        GlStateManager.enablePolygonOffset();
        GlStateManager.doPolygonOffset(-1.0F, -1.0F);
        GlStateManager.enableCull();
        wr.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR);
        for (Chest chest : chests) {
            if (!chest.opened) {
                RenderUtil.appendFilledBox(wr, chest.box.expand(0.002, 0.002, 0.002), r, g, b, FILL);
            }
        }
        tessellator.draw();
        GlStateManager.disableCull();
        GlStateManager.disablePolygonOffset();
        RenderUtil.endWorldOverlay();
    }

    // ---- HUD pass

    private void drawScreen(int scale, boolean hud) {
        if (shapes.isEmpty()) {
            return;
        }
        int accent = 0xFF000000 | color.get();
        for (Shape s : shapes) {
            ScreenStrokes.lines(s.lines, s.lineN, scale, EDGE_W, s.chest.opened ? EDGE_OPENED : Theme.withAlpha(accent, 230));
        }
        if (hud) {
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
        final boolean pair;
        final boolean opened;

        Chest(AxisAlignedBB box, boolean pair, boolean opened) {
            this.box = box;
            this.pair = pair;
            this.opened = opened;
        }
    }

    /** One chest's projection for the HUD pass, framebuffer px. */
    private static final class Shape {
        final Chest chest;
        final float[] lines = new float[48];
        int lineN;
        ProjectionUtil.Point anchor;
        double distance;

        Shape(Chest chest) {
            this.chest = chest;
        }
    }
}
