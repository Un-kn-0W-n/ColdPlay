package coldplay.module.visual;

import coldplay.event.EventBlockPlace;
import coldplay.event.EventRenderMirror;
import coldplay.event.EventRender2D;
import coldplay.event.EventRender3D;
import coldplay.event.EventTarget;
import coldplay.event.EventUpdate;
import coldplay.module.Category;
import coldplay.module.Module;
import coldplay.setting.BooleanSetting;
import coldplay.setting.ColorSetting;
import coldplay.setting.ModeSetting;
import coldplay.setting.NumberSetting;
import coldplay.util.BedUtil;
import coldplay.util.ProjectionUtil;
import coldplay.util.RenderUtil;
import coldplay.util.font.CustomFont;
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
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityChest;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Blocks are scanned on tick and drawn in 3D; dropped items are projected per frame and drawn on the HUD. */
public class BlockESP extends Module {
    private final NumberSetting range = add(new NumberSetting("Range", 64.0, 16.0, 256.0, 16.0).describe("How far to scan for blocks, in blocks."));
    private final BooleanSetting filled = add(new BooleanSetting("Filled", true).describe("Draw translucent box faces."));
    private final BooleanSetting outline = add(new BooleanSetting("Outline", true).describe("Draw the box wireframe edges."));
    private final NumberSetting borderWidth = add(new NumberSetting("Border Width", 2.0, 1.0, 5.0, 1.0).describe("Outline thickness in pixels."));
    private final NumberSetting opacity = add(new NumberSetting("Opacity", 80.0, 0.0, 255.0, 1.0).describe("Fill opacity (0-255)."));

    private final BooleanSetting chests = add(new BooleanSetting("Chests", true).describe("Highlight chests."));
    private final ColorSetting color = add(new ColorSetting("Color", 0xC8AA00).describe("Box colour for chests."));

    private final BooleanSetting bedEsp = add(new BooleanSetting("Beds", false).describe("Highlight beds."));
    private final ColorSetting bedColor = add(new ColorSetting("Bed Color", 0xFF3C3C).describe("Box fill colour for beds."));

    private final BooleanSetting blockHighlight = add(new BooleanSetting("Block Highlight", false).describe("Highlight recently placed blocks with a fading box."));
    private final ModeSetting highlightMode = add(new ModeSetting("Highlight Mode", "Both", "Fill", "Outline", "Both").describe("Draw the highlight as translucent fill, wireframe outline, or both."));
    private final ColorSetting highlightColor = add(new ColorSetting("Highlight Color", 0x3C78FF).describe("Colour for placed-block highlights."));
    private final NumberSetting fadeDuration = add(new NumberSetting("Fade Duration", 1000.0, 100.0, 5000.0, 100.0).describe("How long a placed block stays highlighted, in milliseconds."));
    private final NumberSetting maxBlocks = add(new NumberSetting("Max Blocks", 64.0, 8.0, 256.0, 8.0).describe("Most recently placed blocks tracked at once; oldest are dropped."));

    private final BooleanSetting items = add(new BooleanSetting("Items", false).describe("Highlight dropped items."));
    private final ColorSetting itemColor = add(new ColorSetting("Item Color", 0x00FFC8).describe("Box colour for dropped items."));
    private final BooleanSetting itemNameTag = add(new BooleanSetting("NameTag", true).describe("Show the stack count above each dropped item."));

    private static final int BED_SCAN_INTERVAL = 20;
    // Same-type drops within this half-edge (blocks) share one summed count tag.
    private static final double TAG_CLUSTER_RADIUS = 1.5;

    private final List<AxisAlignedBB> chestBoxes = new ArrayList<AxisAlignedBB>();
    private final List<AxisAlignedBB> bedBoxes = new ArrayList<AxisAlignedBB>();
    private int bedScanCooldown;
    /** Position to placed-at millis, oldest first. */
    private final LinkedHashMap<BlockPos, Long> placedBlocks = new LinkedHashMap<BlockPos, Long>();

    private final FloatBuffer modelview = BufferUtils.createFloatBuffer(16);
    private final FloatBuffer projection = BufferUtils.createFloatBuffer(16);
    private final IntBuffer viewport = BufferUtils.createIntBuffer(16);
    private final FloatBuffer screenCoords = BufferUtils.createFloatBuffer(3);
    // Raw framebuffer px; filled in the world pass, drawn in the HUD pass.
    private final List<ProjectionUtil.AabbProjection> itemBoxes2D = new ArrayList<ProjectionUtil.AabbProjection>();
    private final List<TagCluster> itemTags2D = new ArrayList<TagCluster>();

    public BlockESP() {
        super("BlockESP", Category.VISUAL, "Highlights blocks of interest with see-through boxes.");
        borderWidth.visibleWhen(outline::get).indent(1);
        color.visibleWhen(chests::get).indent(1);
        bedColor.visibleWhen(bedEsp::get).indent(1);
        highlightMode.visibleWhen(blockHighlight::get).indent(1);
        highlightColor.visibleWhen(blockHighlight::get).indent(1);
        fadeDuration.visibleWhen(blockHighlight::get).indent(1);
        maxBlocks.visibleWhen(blockHighlight::get).indent(1);
        itemColor.visibleWhen(items::get).indent(1);
        itemNameTag.visibleWhen(items::get).indent(1);
    }

    @Override
    protected void onDisable() {
        chestBoxes.clear();
        bedBoxes.clear();
        placedBlocks.clear();
        bedScanCooldown = 0;
        itemBoxes2D.clear();
        itemTags2D.clear();
    }

    @EventTarget
    public void onUpdate(EventUpdate event) {
        if (!event.isPre()) {
            return;
        }
        chestBoxes.clear();
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP player = mc.thePlayer;
        WorldClient world = mc.theWorld;
        if (player == null || world == null) {
            bedBoxes.clear();
            placedBlocks.clear();
            return;
        }
        double maxDistSq = range.get() * range.get();

        if (chests.get()) {
            rescanChests(world, player, maxDistSq);
        }

        if (!bedEsp.get()) {
            bedBoxes.clear();
            bedScanCooldown = 0;
        } else if (--bedScanCooldown <= 0) {
            bedScanCooldown = BED_SCAN_INTERVAL;
            rescanBeds(world, player, maxDistSq);
        }

        // the render pass does not re-check the setting, so clear here
        if (!blockHighlight.get()) {
            placedBlocks.clear();
        } else {
            prunePlacedBlocks(world);
        }
    }

    /** Runs on tick so the render pass never touches chunks. */
    private void prunePlacedBlocks(WorldClient world) {
        long now = System.currentTimeMillis();
        long duration = fadeDuration.get().longValue();
        Iterator<Map.Entry<BlockPos, Long>> it = placedBlocks.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<BlockPos, Long> entry = it.next();
            if (now - entry.getValue() >= duration
                    || world.getBlockState(entry.getKey()).getBlock().getMaterial().isReplaceable()) {
                it.remove();
            }
        }
    }

    private void rescanChests(WorldClient world, EntityPlayerSP player, double maxDistSq) {
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
            // selected bounds handle inset sides and double chests
            block.setBlockBoundsBasedOnState(world, pos);
            chestBoxes.add(block.getSelectedBoundingBox(world, pos));
        }
    }

    @EventTarget
    public void onBlockPlace(EventBlockPlace event) {
        if (!blockHighlight.get()) {
            return;
        }
        // remove first so a re-placed pos also moves to the back of the eviction order
        placedBlocks.remove(event.getPos());
        placedBlocks.put(event.getPos(), System.currentTimeMillis());
        int max = maxBlocks.get().intValue();
        Iterator<BlockPos> eldest = placedBlocks.keySet().iterator();
        while (placedBlocks.size() > max) {
            eldest.next();
            eldest.remove();
        }
    }

    /** Beds have no tile entity in 1.8.9, so this is a throttled block scan. */
    private void rescanBeds(WorldClient world, EntityPlayerSP player, double maxDistSq) {
        bedBoxes.clear();
        for (BlockPos pos : BedUtil.findBedHalvesInRange(world, player, range.get().intValue(), maxDistSq)) {
            Blocks.bed.setBlockBoundsBasedOnState(world, pos);
            bedBoxes.add(Blocks.bed.getSelectedBoundingBox(world, pos));
        }
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
            renderOverlay(event.getScale());
        }
    }

    private void renderView(float partialTicks) {
        collectItems(partialTicks);
        if (chestBoxes.isEmpty() && bedBoxes.isEmpty() && placedBlocks.isEmpty()) {
            return;
        }

        RenderUtil.beginWorldOverlay(borderWidth.get().floatValue());
        boolean placed = !placedBlocks.isEmpty();
        boolean fillHighlight = placed && !"Outline".equals(highlightMode.get());
        boolean outlineHighlight = placed && !"Fill".equals(highlightMode.get());
        if (filled.get() || fillHighlight) {
            GlStateManager.enableCull(); // stop near/far faces double-blending
            drawBoxes(true, filled.get(), fillHighlight);
            GlStateManager.disableCull();
        }
        if (outline.get() || outlineHighlight) {
            drawBoxes(false, outline.get(), outlineHighlight);
        }
        RenderUtil.endWorldOverlay();
    }

    /** Projects items while the world camera matrices are current; the HUD pass draws them. */
    private void collectItems(float partialTicks) {
        itemBoxes2D.clear();
        itemTags2D.clear();
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP player = mc.thePlayer;
        WorldClient world = mc.theWorld;
        if (!items.get() || player == null || world == null) {
            return;
        }
        boolean tags = itemNameTag.get();
        double maxDistSq = range.get() * range.get();
        boolean captured = false;
        double viewerX = 0.0, viewerY = 0.0, viewerZ = 0.0;
        for (Entity entity : world.loadedEntityList) {
            if (!(entity instanceof EntityItem) || entity.isDead) {
                continue;
            }
            EntityItem item = (EntityItem) entity;
            ItemStack stack = item.getEntityItem();
            // empty stack = malformed entity, not a real drop
            if (stack.stackSize <= 0 || player.getDistanceSqToEntity(item) > maxDistSq) {
                continue;
            }
            if (!captured) {
                // glGet* stalls the pipeline, so capture lazily
                captured = true;
                ProjectionUtil.captureMatrices(modelview, projection, viewport);
                viewerX = RenderUtil.interp(player.lastTickPosX, player.posX, partialTicks);
                viewerY = RenderUtil.interp(player.lastTickPosY, player.posY, partialTicks);
                viewerZ = RenderUtil.interp(player.lastTickPosZ, player.posZ, partialTicks);
            }
            double x = RenderUtil.interp(item.lastTickPosX, item.posX, partialTicks);
            double y = RenderUtil.interp(item.lastTickPosY, item.posY, partialTicks);
            double z = RenderUtil.interp(item.lastTickPosZ, item.posZ, partialTicks);
            double half = item.width / 2.0;
            AxisAlignedBB box = new AxisAlignedBB(x - half, y, z - half, x + half, y + item.height, z + half);
            ProjectionUtil.AabbProjection projected = ProjectionUtil.projectAabb(box,
                    viewerX, viewerY, viewerZ, modelview, projection, viewport, screenCoords);
            if (projected.hasCompleteBounds()) {
                itemBoxes2D.add(projected);
            }
            if (tags) {
                TagCluster joined = null;
                for (TagCluster cluster : itemTags2D) {
                    if (cluster.item == stack.getItem() && cluster.damage == stack.getItemDamage()
                            && Math.abs(x - cluster.x) <= TAG_CLUSTER_RADIUS
                            && Math.abs(y - cluster.y) <= TAG_CLUSTER_RADIUS
                            && Math.abs(z - cluster.z) <= TAG_CLUSTER_RADIUS) {
                        joined = cluster;
                        break;
                    }
                }
                if (joined != null) {
                    joined.count += stack.stackSize;
                } else {
                    itemTags2D.add(new TagCluster(stack.getItem(), stack.getItemDamage(), x, y, z, stack.stackSize));
                }
            }
        }
        for (TagCluster cluster : itemTags2D) {
            // 0.6 = item height plus RenderEntityItem's hover bob
            cluster.anchor = ProjectionUtil.projectPoint(cluster.x, cluster.y + 0.6, cluster.z,
                    viewerX, viewerY, viewerZ, modelview, projection, viewport, screenCoords);
        }
    }

    @EventTarget
    public void onRender2D(EventRender2D event) {
        renderOverlay(event.getResolution().getScaleFactor());
    }

    private void renderOverlay(int scale) {
        // gluProject gives raw framebuffer px; the HUD ortho is in scaled-resolution units
        if (items.get()) {
            drawItemBoxes(scale);
            drawItemTags(scale);
        }
        itemBoxes2D.clear();
        itemTags2D.clear();
    }

    private void drawItemBoxes(int scale) {
        int thick = Math.max(1, borderWidth.get().intValue());
        int fill = RenderUtil.rgba(itemColor.red(), itemColor.green(), itemColor.blue(), opacity.get().intValue());
        int line = RenderUtil.rgb(itemColor.red(), itemColor.green(), itemColor.blue());
        for (ProjectionUtil.AabbProjection box : itemBoxes2D) {
            int left = Math.round(box.left / scale);
            int top = Math.round(box.top / scale);
            int right = Math.round(box.right / scale);
            int bottom = Math.round(box.bottom / scale);
            if (filled.get()) {
                RenderUtil.rectBounds(left, top, right, bottom, fill);
            }
            if (outline.get()) {
                RenderUtil.outline(left, top, right, bottom, thick, line);
            }
        }
    }

    private void drawItemTags(int scale) {
        if (itemTags2D.isEmpty()) {
            return;
        }
        Fonts.load(scale);
        if (!Fonts.isLoaded()) {
            return;
        }
        CustomFont font = Fonts.list;
        for (TagCluster tag : itemTags2D) {
            if (tag.anchor != null) {
                font.drawCenteredWithShadow(String.valueOf(tag.count),
                        tag.anchor.x / scale, tag.anchor.y / scale - font.getHeight(), 0xFFFFFFFF);
            }
        }
    }

    /** Appends every enabled box to one buffer as fills (GL_QUADS) or edges (GL_LINES). */
    private void drawBoxes(boolean fill, boolean owners, boolean highlight) {
        Tessellator tessellator = Tessellator.getInstance();
        WorldRenderer wr = tessellator.getWorldRenderer();
        wr.begin(fill ? GL11.GL_QUADS : GL11.GL_LINES, DefaultVertexFormats.POSITION_COLOR);
        int alpha = fill ? opacity.get().intValue() : 255;
        if (owners) {
            for (AxisAlignedBB box : chestBoxes) {
                appendBox(wr, fill, box, color.get(), alpha);
            }
            for (AxisAlignedBB box : bedBoxes) {
                appendBox(wr, fill, box, fill ? bedColor.get() : 0, alpha);
            }
        }
        if (highlight) {
            long now = System.currentTimeMillis();
            long duration = fadeDuration.get().longValue();
            for (Map.Entry<BlockPos, Long> entry : placedBlocks.entrySet()) {
                float fade = 1.0f - (float) Math.min(duration, now - entry.getValue()) / (float) duration;
                BlockPos pos = entry.getKey();
                appendBox(wr, fill, new AxisAlignedBB(pos, pos.add(1, 1, 1)), highlightColor.get(), (int) (alpha * fade));
            }
        }
        tessellator.draw();
    }

    private static void appendBox(WorldRenderer wr, boolean fill, AxisAlignedBB box, int rgb, int alpha) {
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        if (fill) {
            RenderUtil.appendFilledBox(wr, box, r, g, b, alpha);
        } else {
            RenderUtil.appendOutlineBox(wr, box, r, g, b, alpha);
        }
    }

    /** Same-type drops near one anchor, summed into one count tag. */
    private static final class TagCluster {
        final Item item;
        final int damage;
        final double x;
        final double y;
        final double z;
        int count;
        ProjectionUtil.Point anchor; // raw framebuffer px, null when off-screen

        TagCluster(Item item, int damage, double x, double y, double z, int count) {
            this.item = item;
            this.damage = damage;
            this.x = x;
            this.y = y;
            this.z = z;
            this.count = count;
        }
    }
}
