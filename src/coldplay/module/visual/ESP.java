package coldplay.module.visual;

import coldplay.broker.ChamsRegistry;
import coldplay.broker.OutlineRegistry;
import coldplay.event.EventRender;
import coldplay.event.EventRender2D;
import coldplay.event.EventRender3D;
import coldplay.event.EventRenderMirror;
import coldplay.event.EventTarget;
import coldplay.setting.BooleanSetting;
import coldplay.setting.ModeSetting;
import coldplay.setting.NumberSetting;
import coldplay.util.ProjectionUtil;
import coldplay.util.RenderUtil;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.entity.Entity;
import net.minecraft.util.Vec3;

import org.lwjgl.BufferUtils;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 2D boxes project in the world pass and draw on the HUD pass; Outline and Chams hand the selection to their registries. */
public class ESP extends EntityVisual {
    private static final String MODE_2D = "2D";
    private static final String MODE_OUTLINE = "Outline";
    private static final String MODE_CHAMS = "Chams";

    private final ModeSetting mode = add(new ModeSetting("Mode", MODE_2D, MODE_2D, MODE_OUTLINE, MODE_CHAMS).describe("Visual style: 2D box, model Outline, or Chams (textured models, crisp colored rim and soft halo)."));
    private final NumberSetting thickness = add(new NumberSetting("Thickness", 2.0, 1.0, 5.0, 1.0).describe("Outline/border thickness in pixels."));
    private final BooleanSetting filled = add(new BooleanSetting("Filled", false).describe("Fill the 2D box interior."));
    private final NumberSetting opacity = add(new NumberSetting("Opacity", 70.0, 0.0, 255.0, 1.0).describe("Fill opacity (0-255)."));

    // gluProject scratch, reused across frames
    private final FloatBuffer modelview = BufferUtils.createFloatBuffer(16);
    private final FloatBuffer projection = BufferUtils.createFloatBuffer(16);
    private final IntBuffer viewport = BufferUtils.createIntBuffer(16);
    private final FloatBuffer screenCoords = BufferUtils.createFloatBuffer(3);

    // Raw framebuffer px; filled in the world pass, drawn in the HUD pass.
    private final List<ScreenBox> boxes2D = new ArrayList<ScreenBox>();

    public ESP() {
        super("ESP", "Highlights entities through walls, colored per type (players/mobs/animals).");
        // outline width is baked into the shader, so Thickness is 2D-only
        thickness.visibleWhen(() -> MODE_2D.equals(mode.get())).indent(1);
        filled.visibleWhen(() -> MODE_2D.equals(mode.get())).indent(1);
        opacity.visibleWhen(() -> MODE_2D.equals(mode.get())).indent(1);
        addFilters();
        addColors();
    }

    @Override
    protected void onDisable() {
        boxes2D.clear();
        targets.clear();
        // vanilla keeps reading the registries after unregister, so clear them here
        OutlineRegistry.getInstance().clear();
        ChamsRegistry.getInstance().clear(this);
    }

    @EventTarget
    public void onRender(EventRender event) {
        collectTargets(event.getPartialTicks());
        // publish before either camera's model pass
        syncModelRegistries();
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
            drawBoxes(event.getScale());
        }
    }

    @EventTarget
    public void onRender2D(EventRender2D event) {
        drawBoxes(event.getResolution().getScaleFactor());
    }

    private void renderView(float partialTicks) {
        boxes2D.clear();
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP player = mc.thePlayer;
        if (player == null || mc.theWorld == null || targets.isEmpty()) {
            return;
        }
        if (MODE_2D.equals(mode.get())) {
            projectBoxes(RenderUtil.interpolatedPosition(player, partialTicks));
        } else if (MODE_OUTLINE.equals(mode.get()) && !mc.renderGlobal.isEntityOutlineShaderAvailable()) {
            drawFallbackBoxes();
        }
    }

    private void syncModelRegistries() {
        boolean outline = MODE_OUTLINE.equals(mode.get());
        boolean chams = MODE_CHAMS.equals(mode.get());
        if (!(outline || chams) || targets.isEmpty()) {
            OutlineRegistry.getInstance().clear();
            ChamsRegistry.getInstance().clear(this);
            return;
        }
        Map<Entity, Integer> selection = new HashMap<Entity, Integer>();
        for (Target target : targets) {
            selection.put(target.entity, target.color);
        }
        OutlineRegistry.getInstance().update(selection);
        if (chams) {
            ChamsRegistry.getInstance().update(this, selection.keySet());
        } else {
            ChamsRegistry.getInstance().clear(this);
        }
    }

    private void drawFallbackBoxes() {
        RenderUtil.beginWorldOverlay(thickness.get().floatValue());
        for (Target target : targets) {
            RenderGlobal.drawOutlinedBoundingBox(target.box,
                    (target.color >> 16) & 0xFF, (target.color >> 8) & 0xFF,
                    target.color & 0xFF, 255);
        }
        RenderUtil.endWorldOverlay();
    }

    private void projectBoxes(Vec3 viewer) {
        ProjectionUtil.captureMatrices(modelview, projection, viewport);
        for (Target target : targets) {
            ProjectionUtil.AabbProjection projected = ProjectionUtil.projectAabb(target.box,
                    viewer.xCoord, viewer.yCoord, viewer.zCoord,
                    modelview, projection, viewport, screenCoords);
            if (projected.hasCompleteBounds()) {
                boxes2D.add(new ScreenBox(projected.left, projected.top,
                        projected.right, projected.bottom, target.color));
            }
        }
    }

    private void drawBoxes(int scale) {
        if (boxes2D.isEmpty()) {
            return;
        }
        int thick = Math.max(1, thickness.get().intValue());
        int fillAlpha = opacity.get().intValue() << 24;
        boolean drawFill = filled.get();

        for (ScreenBox box : boxes2D) {
            // gluProject gives raw framebuffer pixels; the HUD ortho works in scaled-resolution units.
            int left = Math.round(box.left / scale);
            int top = Math.round(box.top / scale);
            int right = Math.round(box.right / scale);
            int bottom = Math.round(box.bottom / scale);
            if (drawFill) {
                RenderUtil.rectBounds(left, top, right, bottom, fillAlpha | (box.color & 0xFFFFFF));
            }
            RenderUtil.outline(left, top, right, bottom, thick, 0xFF000000 | box.color);
        }
        boxes2D.clear();
    }

    private static final class ScreenBox {
        final float left, top, right, bottom;
        final int color;

        ScreenBox(float left, float top, float right, float bottom, int color) {
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
            this.color = color;
        }
    }
}
