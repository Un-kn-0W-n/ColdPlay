package coldplay.module.visual;

import coldplay.event.EventRender3D;
import coldplay.event.EventRenderMirror;
import coldplay.event.EventTarget;
import coldplay.event.EventUpdate;
import coldplay.module.Category;
import coldplay.module.Module;
import coldplay.setting.BooleanSetting;
import coldplay.setting.ColorSetting;
import coldplay.setting.NumberSetting;
import coldplay.util.RenderUtil;

import net.minecraft.block.Block;
import net.minecraft.block.BlockChest;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityChest;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;

import java.util.ArrayList;
import java.util.List;

/** Chests are scanned on tick so the render pass never touches chunks. */
public class ChestESP extends Module {
    private final NumberSetting range = add(new NumberSetting("Range", 64.0, 16.0, 256.0, 16.0).describe("How far to scan for chests, in blocks."));
    private final BooleanSetting filled = add(new BooleanSetting("Filled", true).describe("Draw translucent box faces."));
    private final BooleanSetting outline = add(new BooleanSetting("Outline", true).describe("Draw the box wireframe edges."));
    private final NumberSetting borderWidth = add(new NumberSetting("Border Width", 2.0, 1.0, 5.0, 1.0).describe("Outline thickness in pixels."));
    private final NumberSetting opacity = add(new NumberSetting("Opacity", 80.0, 0.0, 255.0, 1.0).describe("Fill opacity (0-255)."));
    private final ColorSetting color = add(new ColorSetting("Color", 0xC8AA00).describe("Box colour for chests."));

    private final List<AxisAlignedBB> boxes = new ArrayList<AxisAlignedBB>();

    public ChestESP() {
        super("ChestESP", Category.VISUAL, "Highlights chests through walls.");
        borderWidth.visibleWhen(outline::get).indent(1);
    }

    @Override
    protected void onDisable() {
        boxes.clear();
    }

    @EventTarget
    public void onUpdate(EventUpdate event) {
        if (!event.isPre()) {
            return;
        }
        boxes.clear();
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP player = mc.thePlayer;
        WorldClient world = mc.theWorld;
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
            // selected bounds handle inset sides and double chests
            block.setBlockBoundsBasedOnState(world, pos);
            boxes.add(block.getSelectedBoundingBox(world, pos));
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
        }
    }

    private void renderView() {
        if (!boxes.isEmpty()) {
            RenderUtil.drawBoxes(boxes, color.get(), opacity.get().intValue(), color.get(),
                    filled.get(), outline.get(), borderWidth.get().floatValue());
        }
    }
}
