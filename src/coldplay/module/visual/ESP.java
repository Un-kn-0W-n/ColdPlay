package coldplay.module.visual;

import coldplay.broker.ChamsRegistry;
import coldplay.broker.OutlineRegistry;
import coldplay.event.EventRender;
import coldplay.event.EventRender2D;
import coldplay.event.EventRender3D;
import coldplay.event.EventRenderMirror;
import coldplay.event.EventTarget;
import coldplay.gui.Glass;
import coldplay.gui.GlassShader;
import coldplay.gui.Theme;
import coldplay.setting.BooleanSetting;
import coldplay.setting.ModeSetting;
import coldplay.setting.NumberSetting;
import coldplay.util.HealthResolver;
import coldplay.util.ProjectionUtil;
import coldplay.util.RenderUtil;
import coldplay.util.font.CustomFont;
import coldplay.util.font.FontRef;
import coldplay.util.font.Fonts;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.model.ModelBase;
import net.minecraft.client.model.ModelBiped;
import net.minecraft.client.model.ModelBox;
import net.minecraft.client.model.ModelCow;
import net.minecraft.client.model.ModelPig;
import net.minecraft.client.model.ModelQuadruped;
import net.minecraft.client.model.ModelRenderer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.renderer.entity.RendererLivingEntity;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.EnumAction;
import net.minecraft.item.ItemStack;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.MathHelper;
import net.minecraft.util.Vec3;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Screen modes project in the world pass and draw on the HUD pass, Box 3D and Skeleton draw in the world,
 * and Outline, Glow and Chams hand the selection to their registries.
 */
public class ESP extends EntityVisual {
    private static final String MODE_2D = "2D";
    private static final String MODE_CORNERS = "Corners";
    private static final String MODE_HALO = "Halo";
    private static final String MODE_BOX = "Box 3D";
    private static final String MODE_SKELETON = "Skeleton";
    private static final String MODE_OUTLINE = "Outline";
    private static final String MODE_GLOW = "Glow";
    private static final String MODE_CHAMS = "Chams";

    private static final int TEXT = 0xFFF4F6F8;
    private static final int TEXT_DIM = 0xC7F4F6F8;
    private static final int BACKING = 0x99000000;
    private static final float LABEL_MIN_H = 45.0F; // GUI px; shorter boxes drop the name
    private static final FontRef NAME_FONT = new FontRef(Fonts.GEIST_SEMIBOLD, 8.25F);
    private static final FontRef MONO_FONT = new FontRef(Fonts.GEIST_MONO_MEDIUM, 7.5F);

    private final ModeSetting mode = add(new ModeSetting("Mode", MODE_2D, MODE_2D, MODE_CORNERS, MODE_HALO, MODE_BOX,
            MODE_SKELETON, MODE_OUTLINE, MODE_GLOW, MODE_CHAMS).describe("Visual style: 2D box, Corners, Halo health ring, Box 3D, Skeleton, model Outline, Glow (rim and bloom, hidden parts filled), or Chams (textured models, crisp colored rim and soft halo)."));
    private final NumberSetting thickness = add(new NumberSetting("Thickness", 2.0, 1.0, 5.0, 1.0).describe("Outline/border thickness in pixels."));
    private final BooleanSetting filled = add(new BooleanSetting("Filled", false).describe("Fill the 2D box interior."));
    private final NumberSetting opacity = add(new NumberSetting("Opacity", 70.0, 0.0, 255.0, 1.0).describe("Fill opacity (0-255)."));
    private final BooleanSetting labels = add(new BooleanSetting("Labels", true).describe("Name, health and distance next to each entity."));

    // gluProject scratch, reused across frames
    private final FloatBuffer modelview = BufferUtils.createFloatBuffer(16);
    private final FloatBuffer projection = BufferUtils.createFloatBuffer(16);
    private final IntBuffer viewport = BufferUtils.createIntBuffer(16);
    private final FloatBuffer screenCoords = BufferUtils.createFloatBuffer(3);

    // Raw framebuffer px; filled in the world pass, drawn in the HUD pass.
    private final List<ScreenBox> boxes2D = new ArrayList<ScreenBox>();

    public ESP() {
        super("ESP", "Highlights entities through walls, colored per type (players/mobs/animals).");
        // outline widths are baked into the shaders
        thickness.visibleWhen(() -> is(MODE_2D, MODE_CORNERS, MODE_BOX, MODE_SKELETON)).indent(1);
        filled.visibleWhen(() -> MODE_2D.equals(mode.get())).indent(1);
        opacity.visibleWhen(() -> MODE_2D.equals(mode.get())).indent(1);
        labels.visibleWhen(() -> is(MODE_CORNERS, MODE_HALO, MODE_BOX, MODE_SKELETON)).indent(1);
        addFilters();
        addColors();
    }

    private boolean is(String... modes) {
        for (String m : modes) {
            if (m.equals(mode.get())) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected void onDisable() {
        boxes2D.clear();
        targets.clear();
        // vanilla keeps reading the registries after unregister, so clear them here
        OutlineRegistry.getInstance().clear();
        ChamsRegistry.getInstance().clear(this);
        ChamsRegistry.getInstance().setGlow(false);
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
            // the mirror shows its texture flipped, so no text there
            drawScreen(event.getScale(), false);
        }
    }

    @EventTarget
    public void onRender2D(EventRender2D event) {
        drawScreen(event.getResolution().getScaleFactor(), true);
    }

    private void renderView(float partialTicks) {
        boxes2D.clear();
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP player = mc.thePlayer;
        if (player == null || mc.theWorld == null || targets.isEmpty()) {
            return;
        }
        if (is(MODE_BOX)) {
            drawBoxes3D();
        } else if (is(MODE_SKELETON)) {
            drawSkeletons(partialTicks);
        } else if (is(MODE_OUTLINE, MODE_GLOW) && !mc.renderGlobal.isEntityOutlineShaderAvailable()) {
            drawFallbackBoxes();
        }
        if (is(MODE_2D, MODE_CORNERS, MODE_HALO) || (labels.get() && is(MODE_BOX, MODE_SKELETON))) {
            projectBoxes(RenderUtil.interpolatedPosition(player, partialTicks));
        }
    }

    private void syncModelRegistries() {
        boolean chams = is(MODE_CHAMS);
        boolean glow = is(MODE_GLOW) && Minecraft.getMinecraft().renderGlobal.isEntityOutlineShaderAvailable();
        ChamsRegistry.getInstance().setGlow(glow);
        if (!(is(MODE_OUTLINE) || chams || glow) || targets.isEmpty()) {
            OutlineRegistry.getInstance().clear();
            ChamsRegistry.getInstance().clear(this);
            return;
        }
        Map<Entity, Integer> selection = new HashMap<Entity, Integer>();
        for (Target target : targets) {
            selection.put(target.entity, target.color);
        }
        OutlineRegistry.getInstance().update(selection);
        if (chams || glow) {
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
                EntityLivingBase living = (EntityLivingBase) target.entity;
                float health = HealthResolver.resolve(living);
                boxes2D.add(new ScreenBox(projected.left, projected.top, projected.right, projected.bottom,
                        target.color, living instanceof EntityPlayer, living.getName(), health,
                        healthFraction(living, health), target.distance));
            }
        }
    }

    private static float healthFraction(EntityLivingBase living, float health) {
        // scoreboard health can exceed max health
        return MathHelper.clamp_float(health / Math.max(1.0F, living.getMaxHealth()), 0.0F, 1.0F);
    }

    // ---- HUD pass

    private void drawScreen(int scale, boolean hud) {
        if (boxes2D.isEmpty()) {
            return;
        }
        boolean text = false;
        if (hud && labels.get()) {
            Fonts.load(scale); // lazy init, needs a live GL context
            text = Fonts.isLoaded();
        }
        if (is(MODE_2D)) {
            drawBoxes(scale);
        } else if (is(MODE_CORNERS)) {
            drawCorners(scale, text);
        } else if (is(MODE_HALO)) {
            drawHalo(scale, text);
        } else if (text) {
            drawLabels(scale);
        }
        boxes2D.clear();
    }

    private void drawBoxes(int scale) {
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
    }

    private void drawCorners(int scale, boolean text) {
        float line = thickness.get().floatValue() * 0.6F;
        WorldRenderer wr = RenderUtil.beginQuads();
        for (ScreenBox box : boxes2D) {
            float l = box.left / scale, t = box.top / scale, r = box.right / scale, b = box.bottom / scale;
            float len = Math.max(3.0F, 0.24F * Math.min(r - l, b - t));
            corners(wr, l, t, r, b, len, line, 0.75F, BACKING);
            corners(wr, l, t, r, b, len, line, 0.0F, 0xFF000000 | box.color);
            // health on the left, filled from the feet up
            float x = l - 4.5F;
            quad(wr, x - 0.75F, t - 0.75F, x + 2.25F, b + 0.75F, BACKING);
            quad(wr, x, b - (b - t) * box.fraction, x + 1.5F, b, Theme.healthColor(box.fraction));
        }
        RenderUtil.endQuads();
        if (!text) {
            return;
        }
        CustomFont mono = MONO_FONT.get();
        for (ScreenBox box : boxes2D) {
            float t = box.top / scale, b = box.bottom / scale;
            float cx = (box.left + box.right) / 2.0F / scale;
            if (b - t >= LABEL_MIN_H) {
                title(box.name, health(box), Theme.healthColor(box.fraction), cx, t - 3.75F);
            }
            mono.drawCenteredWithShadow(distance(box), cx, b + 3.0F, TEXT_DIM);
        }
    }

    private void drawHalo(int scale, boolean text) {
        for (ScreenBox box : boxes2D) {
            float x = box.left / scale - 2.25F, y = box.top / scale - 2.25F;
            float w = box.right / scale + 2.25F - x, h = box.bottom / scale + 2.25F - y;
            float r = Math.min(6.75F, 0.22F * Math.min(w, h));
            int track = box.player ? 0x33FFFFFF : 0x57000000 | box.color;
            GlassShader.arc(x, y, w, h, r, 3.0F, 0.0F, 1.0F, 0x47000000);
            GlassShader.arc(x, y, w, h, r, 1.5F, 0.0F, 1.0F, track);
            GlassShader.arc(x, y, w, h, r, 1.5F, 0.0F, box.fraction, Theme.healthColor(box.fraction));
        }
        if (!text) {
            return;
        }
        CustomFont name = NAME_FONT.get();
        CustomFont mono = MONO_FONT.get();
        GlassShader.capture(); // one backdrop for every chip
        for (ScreenBox box : boxes2D) {
            float y = box.top / scale - 2.25F, bottom = box.bottom / scale + 2.25F;
            float cx = (box.left + box.right) / 2.0F / scale;
            if (bottom - y >= LABEL_MIN_H) {
                String hp = health(box);
                float nameW = name.getStringWidth(box.name);
                float chipW = 4.5F + 4.5F + 3.75F + nameW + 3.75F + mono.getStringWidth(hp) + 5.25F;
                float chipH = 13.5F;
                float chipX = cx - chipW / 2.0F, chipY = y - 4.5F - chipH;
                int dot = 0xFF000000 | box.color;
                GlassShader.frost(chipX, chipY, chipW, chipH, 5.25F, Glass.SMOKE);
                GlassShader.rect(chipX + 4.5F, chipY + (chipH - 4.5F) / 2.0F, 4.5F, 4.5F, 2.25F, dot, dot);
                float tx = chipX + 4.5F + 4.5F + 3.75F;
                float ty = chipY + (chipH - name.getHeight()) / 2.0F;
                name.drawString(box.name, tx, ty, TEXT);
                mono.drawString(hp, tx + nameW + 3.75F, ty + name.getAscent() - mono.getAscent(),
                        Theme.healthColor(box.fraction));
            }
            mono.drawCenteredWithShadow(distance(box), cx, bottom + 3.0F, TEXT_DIM);
        }
    }

    /** Box 3D and Skeleton draw in the world; only their text is left for the HUD. */
    private void drawLabels(int scale) {
        boolean skeleton = is(MODE_SKELETON);
        CustomFont mono = MONO_FONT.get();
        for (ScreenBox box : boxes2D) {
            float t = box.top / scale, b = box.bottom / scale;
            float cx = (box.left + box.right) / 2.0F / scale;
            if (b - t >= LABEL_MIN_H) {
                title(box.name, skeleton ? health(box) : distance(box),
                        skeleton ? Theme.healthColor(box.fraction) : TEXT_DIM, cx, t - 3.0F);
            } else {
                mono.drawCenteredWithShadow(distance(box), cx, t - 3.0F - mono.getHeight(), TEXT_DIM);
            }
        }
    }

    /** Name and a mono value on one line, centered on {@code cx} and sitting on {@code bottom}. */
    private static void title(String text, String value, int valueColor, float cx, float bottom) {
        CustomFont name = NAME_FONT.get();
        CustomFont mono = MONO_FONT.get();
        float gap = 3.75F;
        float nameW = name.getStringWidth(text);
        float x = cx - (nameW + gap + mono.getStringWidth(value)) / 2.0F;
        float y = bottom - name.getHeight();
        name.drawStringWithShadow(text, x, y, TEXT);
        mono.drawStringWithShadow(value, x + nameW + gap, y + name.getAscent() - mono.getAscent(), valueColor);
    }

    private static String health(ScreenBox box) {
        return String.format("%.1f", box.health);
    }

    private static String distance(ScreenBox box) {
        return String.format(box.distance >= 20.0 ? "%.0fm" : "%.1fm", box.distance);
    }

    private static void corners(WorldRenderer wr, float l, float t, float r, float b,
                                float len, float line, float pad, int argb) {
        bracket(wr, l, t, 1, 1, len, line, pad, argb);
        bracket(wr, r, t, -1, 1, len, line, pad, argb);
        bracket(wr, r, b, -1, -1, len, line, pad, argb);
        bracket(wr, l, b, 1, -1, len, line, pad, argb);
    }

    /** Two arms from the corner along dx and dy; the second starts past the first so a translucent backing does not double up. */
    private static void bracket(WorldRenderer wr, float x, float y, int dx, int dy,
                                float len, float line, float pad, int argb) {
        float in = line / 2.0F + pad;
        quad(wr, x - dx * in, y - dy * in, x + dx * (len + pad), y + dy * in, argb);
        quad(wr, x - dx * in, y + dy * in, x + dx * in, y + dy * (len + pad), argb);
    }

    /** Float-precise rect into an open {@link RenderUtil#beginQuads()} buffer. */
    private static void quad(WorldRenderer wr, float x0, float y0, float x1, float y1, int argb) {
        float left = Math.min(x0, x1), right = Math.max(x0, x1);
        float top = Math.min(y0, y1), bottom = Math.max(y0, y1);
        int a = argb >>> 24, r = argb >> 16 & 0xFF, g = argb >> 8 & 0xFF, b = argb & 0xFF;
        wr.pos(left, bottom, 0.0D).color(r, g, b, a).endVertex();
        wr.pos(right, bottom, 0.0D).color(r, g, b, a).endVertex();
        wr.pos(right, top, 0.0D).color(r, g, b, a).endVertex();
        wr.pos(left, top, 0.0D).color(r, g, b, a).endVertex();
    }

    // ---- world pass

    private void drawBoxes3D() {
        Minecraft mc = Minecraft.getMinecraft();
        RenderManager manager = mc.getRenderManager();
        double camX = manager.viewerPosX;
        double camY = manager.viewerPosY + mc.getRenderViewEntity().getEyeHeight();
        double camZ = manager.viewerPosZ;
        float line = thickness.get().floatValue();
        Tessellator tessellator = Tessellator.getInstance();
        WorldRenderer wr = tessellator.getWorldRenderer();
        RenderUtil.beginWorldOverlay(line);

        // faint faces, plus the lower part filled up to the health fraction
        GlStateManager.enableCull(); // stop near/far faces double-blending
        wr.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR);
        for (Target target : targets) {
            AxisAlignedBB box = target.box;
            float fraction = healthFraction((EntityLivingBase) target.entity);
            int hc = Theme.healthColor(fraction);
            RenderUtil.appendFilledBox(wr, box, target.color >> 16 & 0xFF, target.color >> 8 & 0xFF, target.color & 0xFF, 15);
            AxisAlignedBB level = new AxisAlignedBB(box.minX, box.minY, box.minZ,
                    box.maxX, box.minY + (box.maxY - box.minY) * fraction, box.maxZ);
            RenderUtil.appendFilledBox(wr, level, hc >> 16 & 0xFF, hc >> 8 & 0xFF, hc & 0xFF, 61);
        }
        tessellator.draw();
        GlStateManager.disableCull();

        // footprint, seen from either side
        wr.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR);
        for (Target target : targets) {
            AxisAlignedBB box = target.box;
            int r = target.color >> 16 & 0xFF, g = target.color >> 8 & 0xFF, b = target.color & 0xFF;
            wr.pos(box.minX, box.minY, box.minZ).color(r, g, b, 51).endVertex();
            wr.pos(box.maxX, box.minY, box.minZ).color(r, g, b, 51).endVertex();
            wr.pos(box.maxX, box.minY, box.maxZ).color(r, g, b, 51).endVertex();
            wr.pos(box.minX, box.minY, box.maxZ).color(r, g, b, 51).endVertex();
        }
        tessellator.draw();

        // edges on the far side, dashed and dim
        GL11.glEnable(GL11.GL_LINE_STIPPLE);
        GL11.glLineStipple(2, (short) 0x3333);
        GL11.glLineWidth(1.0F);
        wr.begin(GL11.GL_LINES, DefaultVertexFormats.POSITION_COLOR);
        for (Target target : targets) {
            appendEdges(wr, target.box, camX, camY, camZ, false, target.color, 97);
        }
        tessellator.draw();
        GL11.glDisable(GL11.GL_LINE_STIPPLE);

        GL11.glLineWidth(line + 2.0F);
        wr.begin(GL11.GL_LINES, DefaultVertexFormats.POSITION_COLOR);
        for (Target target : targets) {
            appendEdges(wr, target.box, camX, camY, camZ, true, 0x000000, 115);
        }
        tessellator.draw();
        GL11.glLineWidth(line);
        wr.begin(GL11.GL_LINES, DefaultVertexFormats.POSITION_COLOR);
        for (Target target : targets) {
            appendEdges(wr, target.box, camX, camY, camZ, true, target.color, 255);
        }
        tessellator.draw();
        RenderUtil.endWorldOverlay();
    }

    private static float healthFraction(EntityLivingBase living) {
        return healthFraction(living, HealthResolver.resolve(living));
    }

    /** The box edges facing the camera ({@code front}) or facing away; a face faces the camera when the camera is outside its plane. */
    private static void appendEdges(WorldRenderer wr, AxisAlignedBB box, double camX, double camY, double camZ,
                                    boolean front, int rgb, int alpha) {
        boolean[] faces = {camX < box.minX, camX > box.maxX, camY < box.minY, camY > box.maxY,
                camZ < box.minZ, camZ > box.maxZ};
        int r = rgb >> 16 & 0xFF, g = rgb >> 8 & 0xFF, b = rgb & 0xFF;
        // corner bits: 4 = max x, 2 = max y, 1 = max z; an edge joins corners one bit apart
        for (int i = 0; i < 8; i++) {
            for (int bit = 1; bit <= 4; bit <<= 1) {
                if ((i & bit) != 0) {
                    continue;
                }
                boolean facing = false;
                for (int axis = 0; axis < 3; axis++) {
                    int axisBit = 4 >> axis;
                    if (axisBit != bit) {
                        facing |= faces[axis * 2 + ((i & axisBit) != 0 ? 1 : 0)];
                    }
                }
                if (facing == front) {
                    corner(wr, box, i, r, g, b, alpha);
                    corner(wr, box, i | bit, r, g, b, alpha);
                }
            }
        }
    }

    private static void corner(WorldRenderer wr, AxisAlignedBB box, int i, int r, int g, int b, int a) {
        wr.pos((i & 4) != 0 ? box.maxX : box.minX, (i & 2) != 0 ? box.maxY : box.minY,
                (i & 1) != 0 ? box.maxZ : box.minZ).color(r, g, b, a).endVertex();
    }

    private void drawSkeletons(float partialTicks) {
        RenderManager manager = Minecraft.getMinecraft().getRenderManager();
        float line = thickness.get().floatValue();
        RenderUtil.beginWorldOverlay(line);
        GL11.glEnable(GL11.GL_POINT_SMOOTH);
        for (Target target : targets) {
            EntityLivingBase living = (EntityLivingBase) target.entity;
            Object render = manager.getEntityRenderObject(living);
            int color = 0xFF000000 | target.color;
            if (!(render instanceof RendererLivingEntity)) {
                GL11.glLineWidth(line);
                RenderGlobal.drawOutlinedBoundingBox(target.box, color >> 16 & 0xFF, color >> 8 & 0xFF, color & 0xFF, 255);
                continue;
            }
            RendererLivingEntity renderer = (RendererLivingEntity) render;
            ModelBase model = renderer.getMainModel();
            float bodyYaw = pose(model, living, partialTicks);
            GlStateManager.pushMatrix();
            // the same model transform as RendererLivingEntity.doRender
            double y = target.box.minY;
            if (living instanceof EntityPlayer && living.isSneaking()) {
                y -= 0.125;
            }
            GlStateManager.translate(target.centerX, y, target.centerZ);
            GlStateManager.rotate(180.0F - bodyYaw, 0.0F, 1.0F, 0.0F);
            GlStateManager.scale(-1.0F, -1.0F, 1.0F);
            renderer.coldplayPreRender(living, partialTicks);
            GlStateManager.translate(0.0F, -1.5078125F, 0.0F);
            if (model instanceof ModelBiped && ((ModelBiped) model).isSneak) {
                GlStateManager.translate(0.0F, 0.2F, 0.0F);
            }
            GL11.glLineWidth(line + 2.0F);
            bones(model, living.isChild(), 0x80000000, false);
            GL11.glLineWidth(line);
            GL11.glPointSize(line + 2.0F);
            bones(model, living.isChild(), color, true);
            GlStateManager.popMatrix();
        }
        GL11.glPointSize(1.0F);
        GL11.glDisable(GL11.GL_POINT_SMOOTH);
        RenderUtil.endWorldOverlay();
    }

    /** Poses the shared model the way RendererLivingEntity.doRender would this frame; returns the body yaw. */
    private static float pose(ModelBase model, EntityLivingBase living, float partialTicks) {
        float bodyYaw = lerpYaw(living.prevRenderYawOffset, living.renderYawOffset, partialTicks);
        float headYaw = lerpYaw(living.prevRotationYawHead, living.rotationYawHead, partialTicks);
        float pitch = living.prevRotationPitch + (living.rotationPitch - living.prevRotationPitch) * partialTicks;
        float amount = Math.min(1.0F, living.prevLimbSwingAmount
                + (living.limbSwingAmount - living.prevLimbSwingAmount) * partialTicks);
        float swing = living.limbSwing - living.limbSwingAmount * (1.0F - partialTicks);
        model.swingProgress = living.getSwingProgress(partialTicks);
        model.isRiding = living.isRiding();
        model.isChild = living.isChild();
        if (model instanceof ModelBiped && living instanceof EntityPlayer) {
            // RenderPlayer.setModelVisibilities sets these before each render
            ModelBiped biped = (ModelBiped) model;
            EntityPlayer player = (EntityPlayer) living;
            ItemStack held = player.inventory.getCurrentItem();
            biped.isSneak = player.isSneaking();
            biped.heldItemLeft = 0;
            biped.aimedBow = false;
            biped.heldItemRight = held == null ? 0 : 1;
            if (held != null && player.getItemInUseCount() > 0) {
                EnumAction action = held.getItemUseAction();
                if (action == EnumAction.BLOCK) {
                    biped.heldItemRight = 3;
                } else if (action == EnumAction.BOW) {
                    biped.aimedBow = true;
                }
            }
        }
        model.setLivingAnimations(living, swing, amount, partialTicks);
        model.setRotationAngles(swing, amount, living.ticksExisted + partialTicks, headYaw - bodyYaw, pitch, 0.0625F, living);
        return bodyYaw;
    }

    private static float lerpYaw(float prev, float cur, float partialTicks) {
        return prev + MathHelper.wrapAngleTo180_float(cur - prev) * partialTicks;
    }

    private static void bones(ModelBase model, boolean child, int argb, boolean dots) {
        float s = 0.0625F;
        GlStateManager.pushMatrix();
        if (child) {
            // the body half of the vanilla baby transform; heads get their own below
            GlStateManager.scale(0.5F, 0.5F, 0.5F);
            GlStateManager.translate(0.0F, 24.0F * s, 0.0F);
        }
        if (model instanceof ModelBiped) {
            ModelBiped m = (ModelBiped) model;
            joint(m.bipedRightArm, m.bipedLeftArm, argb, dots);
            joint(m.bipedRightLeg, m.bipedLeftLeg, argb, dots);
            limb(m.bipedBody, argb, dots);
            limb(m.bipedRightArm, argb, dots);
            limb(m.bipedLeftArm, argb, dots);
            limb(m.bipedRightLeg, argb, dots);
            limb(m.bipedLeftLeg, argb, dots);
            GlStateManager.popMatrix();
            GlStateManager.pushMatrix();
            if (child) {
                GlStateManager.scale(0.75F, 0.75F, 0.75F);
                GlStateManager.translate(0.0F, 16.0F * s, 0.0F);
            }
            head(m.bipedHead, argb);
        } else if (model instanceof ModelQuadruped) {
            ModelQuadruped m = (ModelQuadruped) model;
            joint(m.leg1, m.leg2, argb, dots);
            joint(m.leg3, m.leg4, argb, dots);
            // spine between the hip midpoints, then a neck from the front hips to the head pivot
            float fx = (m.leg3.rotationPointX + m.leg4.rotationPointX) / 2.0F;
            float fy = (m.leg3.rotationPointY + m.leg4.rotationPointY) / 2.0F;
            float fz = (m.leg3.rotationPointZ + m.leg4.rotationPointZ) / 2.0F;
            line(fx, fy, fz, (m.leg1.rotationPointX + m.leg2.rotationPointX) / 2.0F,
                    (m.leg1.rotationPointY + m.leg2.rotationPointY) / 2.0F,
                    (m.leg1.rotationPointZ + m.leg2.rotationPointZ) / 2.0F, argb, dots);
            line(fx, fy, fz, m.head.rotationPointX, m.head.rotationPointY, m.head.rotationPointZ, argb, dots);
            limb(m.leg1, argb, dots);
            limb(m.leg2, argb, dots);
            limb(m.leg3, argb, dots);
            limb(m.leg4, argb, dots);
            GlStateManager.popMatrix();
            GlStateManager.pushMatrix();
            if (child) {
                // ModelQuadruped's childYOffset / childZOffset, as ModelPig and ModelCow set them
                GlStateManager.translate(0.0F, (m instanceof ModelPig ? 4.0F : 8.0F) * s, (m instanceof ModelCow ? 6.0F : 4.0F) * s);
            }
            head(m.head, argb);
        } else {
            parts(model, argb, dots);
        }
        GlStateManager.popMatrix();
    }

    /** Any other body plan: every part the model owns, children inside their parent's frame. */
    private static void parts(ModelBase model, int argb, boolean dots) {
        Set<ModelRenderer> children = Collections.newSetFromMap(new IdentityHashMap<ModelRenderer, Boolean>());
        for (ModelRenderer part : model.boxList) {
            if (part.childModels != null) {
                children.addAll(part.childModels);
            }
        }
        for (ModelRenderer part : model.boxList) {
            if (!children.contains(part)) {
                part(part, argb, dots);
            }
        }
    }

    /** Each box as a line along its long axis, or as a wireframe when it is about a cube (heads, slimes). */
    private static void part(ModelRenderer part, int argb, boolean dots) {
        if (part.isHidden || !part.showModel) {
            return;
        }
        GlStateManager.pushMatrix();
        part.postRender(0.0625F);
        for (ModelBox c : part.cubeList) {
            float dx = c.posX2 - c.posX1, dy = c.posY2 - c.posY1, dz = c.posZ2 - c.posZ1;
            float max = Math.max(dx, Math.max(dy, dz));
            float min = Math.min(dx, Math.min(dy, dz));
            if (max < 3.0F) {
                continue; // eyes, teeth and other trim
            }
            float cx = (c.posX1 + c.posX2) / 2.0F, cy = (c.posY1 + c.posY2) / 2.0F, cz = (c.posZ1 + c.posZ2) / 2.0F;
            if (max <= min * 1.35F) {
                cube(c, argb);
            } else if (max == dx) {
                line(c.posX1, cy, cz, c.posX2, cy, cz, argb, dots);
            } else if (max == dy) {
                line(cx, c.posY1, cz, cx, c.posY2, cz, argb, dots);
            } else {
                line(cx, cy, c.posZ1, cx, cy, c.posZ2, argb, dots);
            }
        }
        if (part.childModels != null) {
            for (ModelRenderer child : part.childModels) {
                part(child, argb, dots);
            }
        }
        GlStateManager.popMatrix();
    }

    /** From the part's pivot to the far end of its box, in the part's posed frame. */
    private static void limb(ModelRenderer part, int argb, boolean dots) {
        GlStateManager.pushMatrix();
        part.postRender(0.0625F);
        line(0.0F, 0.0F, 0.0F, 0.0F, part.cubeList.get(0).posY2, 0.0F, argb, dots);
        GlStateManager.popMatrix();
    }

    private static void joint(ModelRenderer a, ModelRenderer b, int argb, boolean dots) {
        line(a.rotationPointX, a.rotationPointY, a.rotationPointZ,
                b.rotationPointX, b.rotationPointY, b.rotationPointZ, argb, dots);
    }

    private static void head(ModelRenderer head, int argb) {
        GlStateManager.pushMatrix();
        head.postRender(0.0625F);
        cube(head.cubeList.get(0), argb);
        GlStateManager.popMatrix();
    }

    private static void cube(ModelBox c, int argb) {
        float s = 0.0625F;
        RenderGlobal.drawOutlinedBoundingBox(new AxisAlignedBB(c.posX1 * s, c.posY1 * s, c.posZ1 * s,
                c.posX2 * s, c.posY2 * s, c.posZ2 * s), argb >> 16 & 0xFF, argb >> 8 & 0xFF, argb & 0xFF, argb >>> 24);
    }

    /** A segment in model pixels, with round dots on both ends when {@code dots}. */
    private static void line(float x0, float y0, float z0, float x1, float y1, float z1, int argb, boolean dots) {
        float s = 0.0625F;
        int a = argb >>> 24, r = argb >> 16 & 0xFF, g = argb >> 8 & 0xFF, b = argb & 0xFF;
        Tessellator tessellator = Tessellator.getInstance();
        WorldRenderer wr = tessellator.getWorldRenderer();
        wr.begin(GL11.GL_LINES, DefaultVertexFormats.POSITION_COLOR);
        wr.pos(x0 * s, y0 * s, z0 * s).color(r, g, b, a).endVertex();
        wr.pos(x1 * s, y1 * s, z1 * s).color(r, g, b, a).endVertex();
        tessellator.draw();
        if (dots) {
            wr.begin(GL11.GL_POINTS, DefaultVertexFormats.POSITION_COLOR);
            wr.pos(x0 * s, y0 * s, z0 * s).color(r, g, b, a).endVertex();
            wr.pos(x1 * s, y1 * s, z1 * s).color(r, g, b, a).endVertex();
            tessellator.draw();
        }
    }

    private static final class ScreenBox {
        final float left, top, right, bottom;
        final int color;
        final boolean player;
        final String name;
        final float health;
        final float fraction;
        final double distance;

        ScreenBox(float left, float top, float right, float bottom, int color, boolean player,
                  String name, float health, float fraction, double distance) {
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
            this.color = color;
            this.player = player;
            this.name = name;
            this.health = health;
            this.fraction = fraction;
            this.distance = distance;
        }
    }
}
