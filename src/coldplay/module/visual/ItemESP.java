package coldplay.module.visual;

import coldplay.event.EventRender2D;
import coldplay.event.EventRender3D;
import coldplay.event.EventRenderMirror;
import coldplay.event.EventTarget;
import coldplay.module.Category;
import coldplay.module.Module;
import coldplay.setting.BooleanSetting;
import coldplay.setting.ColorSetting;
import coldplay.setting.NumberSetting;
import coldplay.util.ProjectionUtil;
import coldplay.util.RenderUtil;
import coldplay.util.font.CustomFont;
import coldplay.util.font.Fonts;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.AxisAlignedBB;

import org.lwjgl.BufferUtils;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;

/** Dropped items are projected per frame in the world pass and drawn on the HUD. */
public class ItemESP extends Module {
    // Same-type drops within this half-edge (blocks) share one summed count tag.
    private static final double TAG_CLUSTER_RADIUS = 1.5;

    private final NumberSetting range = add(new NumberSetting("Range", 64.0, 16.0, 256.0, 16.0).describe("How far to show dropped items, in blocks."));
    private final BooleanSetting filled = add(new BooleanSetting("Filled", true).describe("Fill the box interior."));
    private final BooleanSetting outline = add(new BooleanSetting("Outline", true).describe("Draw the box border."));
    private final NumberSetting borderWidth = add(new NumberSetting("Border Width", 2.0, 1.0, 5.0, 1.0).describe("Outline thickness in pixels."));
    private final NumberSetting opacity = add(new NumberSetting("Opacity", 80.0, 0.0, 255.0, 1.0).describe("Fill opacity (0-255)."));
    private final ColorSetting color = add(new ColorSetting("Color", 0x00FFC8).describe("Box colour for dropped items."));
    private final BooleanSetting nameTag = add(new BooleanSetting("NameTag", true).describe("Show the stack count above each dropped item."));

    private final FloatBuffer modelview = BufferUtils.createFloatBuffer(16);
    private final FloatBuffer projection = BufferUtils.createFloatBuffer(16);
    private final IntBuffer viewport = BufferUtils.createIntBuffer(16);
    private final FloatBuffer screenCoords = BufferUtils.createFloatBuffer(3);
    // Raw framebuffer px; filled in the world pass, drawn in the HUD pass.
    private final List<ProjectionUtil.AabbProjection> boxes2D = new ArrayList<ProjectionUtil.AabbProjection>();
    private final List<TagCluster> tags2D = new ArrayList<TagCluster>();

    public ItemESP() {
        super("ItemESP", Category.VISUAL, "Highlights dropped items through walls.");
        borderWidth.visibleWhen(outline::get).indent(1);
    }

    @Override
    protected void onDisable() {
        boxes2D.clear();
        tags2D.clear();
    }

    @EventTarget
    public void onRender3D(EventRender3D event) {
        collectItems(event.getPartialTicks());
    }

    @EventTarget
    public void onRenderMirror(EventRenderMirror event) {
        if (event.getStage() == EventRenderMirror.Stage.WORLD) {
            collectItems(event.getPartialTicks());
        } else {
            renderOverlay(event.getScale());
        }
    }

    @EventTarget
    public void onRender2D(EventRender2D event) {
        renderOverlay(event.getResolution().getScaleFactor());
    }

    /** Projects items while the world camera matrices are current; the HUD pass draws them. */
    private void collectItems(float partialTicks) {
        boxes2D.clear();
        tags2D.clear();
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP player = mc.thePlayer;
        WorldClient world = mc.theWorld;
        if (player == null || world == null) {
            return;
        }
        boolean tags = nameTag.get();
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
                boxes2D.add(projected);
            }
            if (tags) {
                TagCluster joined = null;
                for (TagCluster cluster : tags2D) {
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
                    tags2D.add(new TagCluster(stack.getItem(), stack.getItemDamage(), x, y, z, stack.stackSize));
                }
            }
        }
        for (TagCluster cluster : tags2D) {
            // 0.6 = item height plus RenderEntityItem's hover bob
            cluster.anchor = ProjectionUtil.projectPoint(cluster.x, cluster.y + 0.6, cluster.z,
                    viewerX, viewerY, viewerZ, modelview, projection, viewport, screenCoords);
        }
    }

    private void renderOverlay(int scale) {
        // gluProject gives raw framebuffer px; the HUD ortho is in scaled-resolution units
        drawBoxes(scale);
        drawTags(scale);
        boxes2D.clear();
        tags2D.clear();
    }

    private void drawBoxes(int scale) {
        int thick = Math.max(1, borderWidth.get().intValue());
        int fill = RenderUtil.rgba(color.red(), color.green(), color.blue(), opacity.get().intValue());
        int line = RenderUtil.rgb(color.red(), color.green(), color.blue());
        for (ProjectionUtil.AabbProjection box : boxes2D) {
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

    private void drawTags(int scale) {
        if (tags2D.isEmpty()) {
            return;
        }
        Fonts.load(scale);
        if (!Fonts.isLoaded()) {
            return;
        }
        CustomFont font = Fonts.list;
        for (TagCluster tag : tags2D) {
            if (tag.anchor != null) {
                font.drawCenteredWithShadow(String.valueOf(tag.count),
                        tag.anchor.x / scale, tag.anchor.y / scale - font.getHeight(), 0xFFFFFFFF);
            }
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
