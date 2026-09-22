package coldplay.module.visual;

import coldplay.event.EventBlockPlace;
import coldplay.event.EventRender3D;
import coldplay.event.EventRenderMirror;
import coldplay.event.EventTarget;
import coldplay.event.EventUpdate;
import coldplay.module.Category;
import coldplay.module.Module;
import coldplay.setting.ColorSetting;
import coldplay.setting.ModeSetting;
import coldplay.setting.NumberSetting;
import coldplay.util.RenderUtil;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;

import org.lwjgl.opengl.GL11;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/** Recently placed blocks get a box that fades out. */
public class BlockHighlight extends Module {
    private final ModeSetting mode = add(new ModeSetting("Mode", "Both", "Fill", "Outline", "Both").describe("Draw the highlight as translucent fill, wireframe outline, or both."));
    private final ColorSetting color = add(new ColorSetting("Color", 0x3C78FF).describe("Colour for placed-block highlights."));
    private final NumberSetting borderWidth = add(new NumberSetting("Border Width", 2.0, 1.0, 5.0, 1.0).describe("Outline thickness in pixels."));
    private final NumberSetting opacity = add(new NumberSetting("Opacity", 80.0, 0.0, 255.0, 1.0).describe("Fill opacity (0-255)."));
    private final NumberSetting fadeDuration = add(new NumberSetting("Fade Duration", 1000.0, 100.0, 5000.0, 100.0).describe("How long a placed block stays highlighted, in milliseconds."));
    private final NumberSetting maxBlocks = add(new NumberSetting("Max Blocks", 64.0, 8.0, 256.0, 8.0).describe("Most recently placed blocks tracked at once; oldest are dropped."));

    /** Position to placed-at millis, oldest first. */
    private final LinkedHashMap<BlockPos, Long> placedBlocks = new LinkedHashMap<BlockPos, Long>();

    public BlockHighlight() {
        super("BlockHighlight", Category.VISUAL, "Highlights recently placed blocks with a fading box.");
        borderWidth.visibleWhen(() -> !"Fill".equals(mode.get())).indent(1);
        opacity.visibleWhen(() -> !"Outline".equals(mode.get())).indent(1);
    }

    @Override
    protected void onDisable() {
        placedBlocks.clear();
    }

    @EventTarget
    public void onBlockPlace(EventBlockPlace event) {
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

    /** Runs on tick so the render pass never touches chunks. */
    @EventTarget
    public void onUpdate(EventUpdate event) {
        if (!event.isPre()) {
            return;
        }
        WorldClient world = Minecraft.getMinecraft().theWorld;
        if (world == null) {
            placedBlocks.clear();
            return;
        }
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
        if (placedBlocks.isEmpty()) {
            return;
        }
        RenderUtil.beginWorldOverlay(borderWidth.get().floatValue());
        if (!"Outline".equals(mode.get())) {
            GlStateManager.enableCull(); // stop near/far faces double-blending
            drawBoxes(true);
            GlStateManager.disableCull();
        }
        if (!"Fill".equals(mode.get())) {
            drawBoxes(false);
        }
        RenderUtil.endWorldOverlay();
    }

    /** Appends every box to one buffer as fills (GL_QUADS) or edges (GL_LINES), alpha faded by age. */
    private void drawBoxes(boolean fill) {
        Tessellator tessellator = Tessellator.getInstance();
        WorldRenderer wr = tessellator.getWorldRenderer();
        wr.begin(fill ? GL11.GL_QUADS : GL11.GL_LINES, DefaultVertexFormats.POSITION_COLOR);
        int alpha = fill ? opacity.get().intValue() : 255;
        int r = color.red();
        int g = color.green();
        int b = color.blue();
        long now = System.currentTimeMillis();
        long duration = fadeDuration.get().longValue();
        for (Map.Entry<BlockPos, Long> entry : placedBlocks.entrySet()) {
            float fade = 1.0f - (float) Math.min(duration, now - entry.getValue()) / (float) duration;
            BlockPos pos = entry.getKey();
            AxisAlignedBB box = new AxisAlignedBB(pos, pos.add(1, 1, 1));
            if (fill) {
                RenderUtil.appendFilledBox(wr, box, r, g, b, (int) (alpha * fade));
            } else {
                RenderUtil.appendOutlineBox(wr, box, r, g, b, (int) (alpha * fade));
            }
        }
        tessellator.draw();
    }
}
