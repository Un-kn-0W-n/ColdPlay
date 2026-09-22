package coldplay.module.visual;

import coldplay.event.EventRender;
import coldplay.event.EventRender3D;
import coldplay.event.EventRenderMirror;
import coldplay.event.EventTarget;
import coldplay.setting.NumberSetting;
import coldplay.util.RenderUtil;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.util.Vec3;

/** World-space lines from the eye to each entity. Bobbing is forced off so they stay on the crosshair. */
public class Tracers extends EntityVisual {
    private final NumberSetting width = add(new NumberSetting("Width", 1.5, 0.5, 5.0, 0.5).describe("Tracer line width in pixels."));

    private boolean bobbingForced;
    private boolean prevViewBobbing;

    public Tracers() {
        super("Tracers", "Draws lines to entities. Forces View Bobbing off while enabled.");
        addFilters();
        addColors();
    }

    @Override
    protected void onDisable() {
        targets.clear();
        if (bobbingForced) { // onRender will not run again to restore it
            Minecraft.getMinecraft().gameSettings.viewBobbing = prevViewBobbing;
            bobbingForced = false;
        }
    }

    @EventTarget
    public void onRender(EventRender event) {
        Minecraft mc = Minecraft.getMinecraft();
        if (!bobbingForced) {
            prevViewBobbing = mc.gameSettings.viewBobbing;
            bobbingForced = true;
        }
        mc.gameSettings.viewBobbing = false; // re-assert every frame so the vanilla menu can't win
        collectTargets(event.getPartialTicks());
    }

    @EventTarget
    public void onRender3D(EventRender3D event) {
        renderView(event.getPartialTicks());
    }

    @EventTarget
    public void onRenderMirror(EventRenderMirror event) {
        if (event.getStage() == EventRenderMirror.Stage.WORLD) {
            renderView(event.getPartialTicks());
        }
    }

    private void renderView(float partialTicks) {
        EntityPlayerSP player = Minecraft.getMinecraft().thePlayer;
        if (player == null || targets.isEmpty()) {
            return;
        }
        Vec3 viewer = RenderUtil.interpolatedPosition(player, partialTicks);
        double eyeY = viewer.yCoord + player.getEyeHeight();
        RenderUtil.beginWorldOverlay((float) Math.max(0.5, width.get().doubleValue()));
        Tessellator tessellator = Tessellator.getInstance();
        WorldRenderer wr = tessellator.getWorldRenderer();
        for (Target target : targets) {
            int r = (target.color >> 16) & 0xFF;
            int g = (target.color >> 8) & 0xFF;
            int b = target.color & 0xFF;
            wr.begin(1, DefaultVertexFormats.POSITION_COLOR);
            wr.pos(viewer.xCoord, eyeY, viewer.zCoord).color(r, g, b, 255).endVertex();
            wr.pos(target.centerX, target.centerY, target.centerZ).color(r, g, b, 255).endVertex();
            tessellator.draw();
        }
        RenderUtil.endWorldOverlay();
    }
}
