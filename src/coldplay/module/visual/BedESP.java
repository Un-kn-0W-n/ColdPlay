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
import coldplay.util.BedUtil;
import coldplay.util.RenderUtil;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.init.Blocks;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;

import java.util.ArrayList;
import java.util.List;

/** Beds have no tile entity in 1.8.9, so this is a throttled block scan. */
public class BedESP extends Module {
    private static final int SCAN_INTERVAL = 20;

    private final NumberSetting range = add(new NumberSetting("Range", 64.0, 16.0, 256.0, 16.0).describe("How far to scan for beds, in blocks."));
    private final BooleanSetting filled = add(new BooleanSetting("Filled", true).describe("Draw translucent box faces."));
    private final BooleanSetting outline = add(new BooleanSetting("Outline", true).describe("Draw the box wireframe edges."));
    private final NumberSetting borderWidth = add(new NumberSetting("Border Width", 2.0, 1.0, 5.0, 1.0).describe("Outline thickness in pixels."));
    private final NumberSetting opacity = add(new NumberSetting("Opacity", 80.0, 0.0, 255.0, 1.0).describe("Fill opacity (0-255)."));
    private final ColorSetting color = add(new ColorSetting("Color", 0xFF3C3C).describe("Box fill colour for beds."));

    private final List<AxisAlignedBB> boxes = new ArrayList<AxisAlignedBB>();
    private int scanCooldown;

    public BedESP() {
        super("BedESP", Category.VISUAL, "Highlights beds through walls.");
        borderWidth.visibleWhen(outline::get).indent(1);
    }

    @Override
    protected void onDisable() {
        boxes.clear();
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
            boxes.clear();
            return;
        }
        if (--scanCooldown > 0) {
            return;
        }
        scanCooldown = SCAN_INTERVAL;
        boxes.clear();
        double maxDistSq = range.get() * range.get();
        for (BlockPos pos : BedUtil.findBedHalvesInRange(world, player, range.get().intValue(), maxDistSq)) {
            Blocks.bed.setBlockBoundsBasedOnState(world, pos);
            boxes.add(Blocks.bed.getSelectedBoundingBox(world, pos));
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
            RenderUtil.drawBoxes(boxes, color.get(), opacity.get().intValue(), 0,
                    filled.get(), outline.get(), borderWidth.get().floatValue());
        }
    }
}
