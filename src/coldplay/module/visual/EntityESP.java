package coldplay.module.visual;

import coldplay.event.EventRender;
import coldplay.event.EventRenderMirror;
import coldplay.event.EventRender2D;
import coldplay.event.EventRender3D;
import coldplay.event.EventTarget;
import coldplay.module.Category;
import coldplay.module.Module;
import coldplay.setting.BooleanSetting;
import coldplay.setting.ColorSetting;
import coldplay.setting.HeaderSetting;
import coldplay.setting.ModeSetting;
import coldplay.setting.NumberSetting;
import coldplay.friend.FriendManager;
import coldplay.broker.ChamsRegistry;
import coldplay.util.EntityTargets;
import coldplay.util.HealthResolver;
import coldplay.broker.OutlineRegistry;
import coldplay.util.ProjectionUtil;
import coldplay.util.RenderUtil;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.MathHelper;
import net.minecraft.util.Vec3;

import org.lwjgl.BufferUtils;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * One entity snapshot per frame feeds every mode: boxes, arrows and name tags project during the world
 * pass and draw on the HUD pass, while Outline/Chams just hand the selection to their render brokers.
 * Outline falls back to plain boxes when the shader pipeline isn't available.
 */
public class EntityESP extends Module {
    private static final String MODE_2D = "2D";
    private static final String MODE_OUTLINE = "Outline";
    private static final String MODE_CHAMS = "Chams";
    private static final String TRACER_LINES = "Lines";
    private static final String TRACER_ARROWS = "Arrows";

    private final BooleanSetting esp = add(new BooleanSetting("ESP", true).describe("Draw a box/outline on each entity."));
    private final ModeSetting mode = add(new ModeSetting("Mode", MODE_2D, MODE_2D, MODE_OUTLINE, MODE_CHAMS).describe("Visual style: 2D box, model Outline, or Chams (textured models, crisp colored rim and soft halo)."));
    private final NumberSetting thickness = add(new NumberSetting("Thickness", 2.0, 1.0, 5.0, 1.0).describe("Outline/border thickness in pixels."));
    private final BooleanSetting filled = add(new BooleanSetting("Filled", false).describe("Fill the 2D box interior."));
    private final NumberSetting opacity = add(new NumberSetting("Opacity", 70.0, 0.0, 255.0, 1.0).describe("Fill opacity (0-255)."));

    private final BooleanSetting tracers = add(new BooleanSetting("Tracers", false).describe("Point to entities. Lines forces View Bobbing off while enabled; Arrows does not."));
    private final ModeSetting tracerMode = add(new ModeSetting("Tracer Mode", TRACER_LINES, TRACER_LINES, TRACER_ARROWS).describe("Lines: world lines to entities. Arrows: off-screen direction arrows around the crosshair."));
    private final NumberSetting tracerWidth = add(new NumberSetting("Tracer Width", 1.5, 0.5, 5.0, 0.5).describe("Tracer line width in pixels."));
    private final NumberSetting arrowSize = add(new NumberSetting("Arrow Size", 6.0, 3.0, 16.0, 1.0).describe("Off-screen arrow size, in pixels."));
    private final NumberSetting arrowRadius = add(new NumberSetting("Arrow Radius", 30.0, 10.0, 120.0, 1.0).describe("Arrow distance from the crosshair, in pixels."));

    private final BooleanSetting nameTags = add(new BooleanSetting("NameTags", false).describe("Held item, armor and a real-health bar above each entity."));

    private final HeaderSetting filtersHeader = add(new HeaderSetting("Filters"));
    private final BooleanSetting players = add(new BooleanSetting("Players", true).describe("Show other players."));
    private final BooleanSetting mobs = add(new BooleanSetting("Mobs", true).describe("Show hostile mobs."));
    private final BooleanSetting animals = add(new BooleanSetting("Animals", true).describe("Show passive animals."));
    private final BooleanSetting invisible = add(new BooleanSetting("Invisible", false).describe("Also show invisible entities."));
    private final NumberSetting range = add(new NumberSetting("Range", 64.0, 8.0, 256.0, 8.0).describe("Max distance to highlight entities, in blocks."));

    private final HeaderSetting colorsHeader = add(new HeaderSetting("Colors"));
    private final ColorSetting playerColor = add(new ColorSetting("Player Color", 0xFFFFFF).describe("Colour used for players."));
    private final ColorSetting mobColor = add(new ColorSetting("Mob Color", 0xFF3C3C).describe("Colour used for hostile mobs."));
    private final ColorSetting animalColor = add(new ColorSetting("Animal Color", 0x50DC64).describe("Colour used for passive animals."));

    // Camera capture + gluProject scratch, kept around so projection doesn't allocate every frame.
    private final FloatBuffer modelview = BufferUtils.createFloatBuffer(16);
    private final FloatBuffer projection = BufferUtils.createFloatBuffer(16);
    private final IntBuffer viewport = BufferUtils.createIntBuffer(16);
    private final FloatBuffer screenCoords = BufferUtils.createFloatBuffer(3);

    // Screen-space queues in raw framebuffer pixels: filled in the world pass, drained on the HUD pass.
    private final List<ScreenBox> boxes2D = new ArrayList<ScreenBox>();
    private final List<ScreenArrow> arrows2D = new ArrayList<ScreenArrow>();
    private final List<ScreenNameTag> nameTags2D = new ArrayList<ScreenNameTag>();
    private final List<Target> frameTargets = new ArrayList<Target>();

    // View Bobbing is a vanilla setting, not a module, so save/restore it on the edges ourselves.
    private boolean bobbingForced;
    private boolean prevViewBobbing;

    public EntityESP() {
        super("EntityESP", Category.VISUAL, "Highlights entities through walls, colored per type (players/mobs/animals).");
        mode.visibleWhen(esp::get).indent(1);
        // Outline width is baked into the shader chain, so Thickness is 2D-only like Filled/Opacity.
        thickness.visibleWhen(() -> esp.get() && MODE_2D.equals(mode.get())).indent(1);
        filled.visibleWhen(() -> esp.get() && MODE_2D.equals(mode.get())).indent(1);
        opacity.visibleWhen(() -> esp.get() && MODE_2D.equals(mode.get())).indent(1);
        tracerMode.visibleWhen(tracers::get).indent(1);
        tracerWidth.visibleWhen(() -> tracers.get() && TRACER_LINES.equals(tracerMode.get())).indent(1);
        arrowSize.visibleWhen(() -> tracers.get() && TRACER_ARROWS.equals(tracerMode.get())).indent(1);
        arrowRadius.visibleWhen(() -> tracers.get() && TRACER_ARROWS.equals(tracerMode.get())).indent(1);
    }

    @Override
    protected void onDisable() {
        boxes2D.clear();
        arrows2D.clear();
        nameTags2D.clear();
        frameTargets.clear();
        // Vanilla keeps reading the brokers after we leave the bus, so clear them by hand.
        OutlineRegistry.getInstance().clear();
        ChamsRegistry.getInstance().clear(this);
        if (bobbingForced) { // off the bus now, so onRender won't restore it
            Minecraft.getMinecraft().gameSettings.viewBobbing = prevViewBobbing;
            bobbingForced = false;
        }
    }

    @EventTarget
    public void onRender(EventRender event) {
        Minecraft mc = Minecraft.getMinecraft();
        updateViewBobbing(mc);
        frameTargets.clear();
        if (mc.thePlayer != null && mc.theWorld != null) {
            collectTargets(mc.thePlayer, mc.theWorld, event.getPartialTicks());
        }
        // Both cameras consume one selection, published before either native model pass.
        syncModelRegistries();
    }

    @EventTarget
    public void onRender3D(EventRender3D event) {
        EntityPlayerSP player = Minecraft.getMinecraft().thePlayer;
        if (player != null) renderView(event.getPartialTicks(),
                (float) RenderUtil.interp(player.prevRotationYaw, player.rotationYaw, event.getPartialTicks()));
    }

    @EventTarget
    public void onRenderMirror(EventRenderMirror event) {
        if (event.getStage() == EventRenderMirror.Stage.WORLD) {
            renderView(event.getPartialTicks(), event.getYaw());
        } else {
            drawEspBoxes(event.getScale());
            drawArrows(event.getWidth() / (float) event.getScale(), event.getHeight() / (float) event.getScale(), true);
            drawNameTags(event.getScale());
        }
    }

    private void renderView(float partialTicks, float cameraYaw) {
        boxes2D.clear();
        arrows2D.clear();
        nameTags2D.clear();
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP player = mc.thePlayer;
        if (player == null || mc.theWorld == null) return;
        Vec3 viewer = RenderUtil.interpolatedPosition(player, partialTicks);

        projectTargets(cameraYaw, viewer);
        // Direct box drawing is only needed when Outline can't run its shader.
        if (esp.get() && MODE_OUTLINE.equals(mode.get()) && !mc.renderGlobal.isEntityOutlineShaderAvailable()) {
            drawFallbackBoxes();
        }
        if (tracers.get() && TRACER_LINES.equals(tracerMode.get())) {
            renderTracers(player, viewer);
        }
    }

    private void collectTargets(EntityPlayerSP player, WorldClient world, float partialTicks) {
        double maxDistSq = range.get() * range.get();
        for (Entity entity : world.loadedEntityList) {
            double distanceSq = player.getDistanceSqToEntity(entity);
            // type/range gate first — the name string allocates and the friend lookup locks, per entity per frame
            if (!EntityTargets.isLivingTarget(player, entity, invisible.get()) || distanceSq > maxDistSq) {
                continue;
            }
            int friendColor = entity instanceof EntityPlayer
                    ? FriendManager.getInstance().getColor(entity.getName()) : 0;
            int color = colorFor(entity, friendColor);
            if (color == 0) {
                continue;
            }
            Vec3 pos = RenderUtil.interpolatedPosition(entity, partialTicks);
            double halfWidth = entity.width / 2.0;
            AxisAlignedBB box = new AxisAlignedBB(pos.xCoord - halfWidth, pos.yCoord, pos.zCoord - halfWidth,
                    pos.xCoord + halfWidth, pos.yCoord + entity.height, pos.zCoord + halfWidth);
            frameTargets.add(new Target(entity, color, friendColor, box, pos.xCoord,
                    pos.yCoord + entity.height / 2.0, pos.zCoord, Math.sqrt(distanceSq)));
        }
    }

    /** Applies this module's toggles and colours to an entity that already passed the type/range gate. */
    private int colorFor(Entity entity, int friendColor) {
        if (entity instanceof EntityPlayer) { // covers player-shaped NPCs too
            if (!players.get()) {
                return 0;
            }
            return friendColor != 0 ? friendColor : playerColor.get();
        }
        if (EntityTargets.isMob(entity)) {
            return mobs.get() ? mobColor.get() : 0;
        }
        if (EntityTargets.isAnimal(entity)) {
            return animals.get() ? animalColor.get() : 0;
        }
        return 0; // villagers and the rest
    }

    /** Feeds Outline/Chams from the exact same selection the direct render modes use. */
    private void syncModelRegistries() {
        boolean outline = esp.get() && MODE_OUTLINE.equals(mode.get());
        boolean chams = esp.get() && MODE_CHAMS.equals(mode.get());
        if (!(outline || chams) || frameTargets.isEmpty()) {
            OutlineRegistry.getInstance().clear();
            ChamsRegistry.getInstance().clear(this);
            return;
        }
        Map<Entity, Integer> selection = new HashMap<Entity, Integer>();
        for (Target target : frameTargets) {
            selection.put(target.entity, target.color);
        }
        OutlineRegistry.getInstance().update(selection);
        if (chams) {
            ChamsRegistry.getInstance().update(this, selection.keySet());
        } else {
            ChamsRegistry.getInstance().clear(this);
        }
    }

    /** Disable bobbing for world-space Lines so they stay at the crosshair; Arrows need no override. */
    private void updateViewBobbing(Minecraft mc) {
        if (tracers.get() && TRACER_LINES.equals(tracerMode.get())) {
            if (!bobbingForced) {
                prevViewBobbing = mc.gameSettings.viewBobbing; // capture the real value on the rising edge
                bobbingForced = true;
            }
            mc.gameSettings.viewBobbing = false; // re-assert every frame so the vanilla menu can't win
        } else if (bobbingForced) {
            mc.gameSettings.viewBobbing = prevViewBobbing;
            bobbingForced = false;
        }
    }

    private void renderTracers(EntityPlayerSP player, Vec3 viewer) {
        double eyeY = viewer.yCoord + player.getEyeHeight();
        RenderUtil.beginWorldOverlay((float) Math.max(0.5, tracerWidth.get().doubleValue()));
        Tessellator tessellator = Tessellator.getInstance();
        WorldRenderer wr = tessellator.getWorldRenderer();
        for (Target target : frameTargets) {
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

    /** Shader fallback: the same snapshot boxes, drawn see-through with the current type colours. */
    private void drawFallbackBoxes() {
        RenderUtil.beginWorldOverlay(thickness.get().floatValue());
        for (Target target : frameTargets) {
            RenderGlobal.drawOutlinedBoundingBox(target.box,
                    (target.color >> 16) & 0xFF, (target.color >> 8) & 0xFF,
                    target.color & 0xFF, 255);
        }
        RenderUtil.endWorldOverlay();
    }

    /** Projects each snapshot hitbox at most once and fans the result out to all screen-space modes. */
    private void projectTargets(float cameraYaw, Vec3 viewer) {
        boolean wantBoxes = esp.get() && MODE_2D.equals(mode.get());
        boolean wantArrows = tracers.get() && TRACER_ARROWS.equals(tracerMode.get());
        boolean wantTags = nameTags.get();
        // glGet* stalls the pipeline; with nothing to project there is nothing to capture
        if (frameTargets.isEmpty() || !(wantBoxes || wantArrows || wantTags)) {
            return;
        }
        ProjectionUtil.captureMatrices(modelview, projection, viewport);

        for (Target target : frameTargets) {
            if (wantBoxes || wantArrows) {
                ProjectionUtil.AabbProjection projected = ProjectionUtil.projectAabb(target.box,
                        viewer.xCoord, viewer.yCoord, viewer.zCoord,
                        modelview, projection, viewport, screenCoords);
                if (wantBoxes && projected.hasCompleteBounds()) {
                    boxes2D.add(new ScreenBox(projected.left, projected.top,
                            projected.right, projected.bottom, target.color));
                }
                if (wantArrows && !projected.overlapsViewport(target.box, viewer.xCoord, viewer.zCoord)) {
                    int color = target.friendColor != 0 ? target.friendColor : distanceColor(target.distance);
                    arrows2D.add(new ScreenArrow(bearingAngle(cameraYaw, viewer.xCoord, viewer.zCoord,
                            target.centerX, target.centerZ), color));
                }
            }
            if (wantTags && target.entity instanceof EntityLivingBase) {
                collectNameTag((EntityLivingBase) target.entity, target, viewer);
            }
        }
    }

    private void collectNameTag(EntityLivingBase entity, Target target, Vec3 viewer) {
        ProjectionUtil.Point anchor = ProjectionUtil.projectPoint(target.centerX, target.box.maxY + 0.25,
                target.centerZ, viewer.xCoord, viewer.yCoord, viewer.zCoord,
                modelview, projection, viewport, screenCoords);
        if (anchor == null) {
            return;
        }
        float maxHealth = entity.getMaxHealth();
        float fraction = maxHealth > 0.0F
                ? MathHelper.clamp_float(HealthResolver.resolve(entity) / maxHealth, 0.0F, 1.0F)
                : 0.0F;
        ItemStack[] armor = {
                entity.getCurrentArmor(0), entity.getCurrentArmor(1),
                entity.getCurrentArmor(2), entity.getCurrentArmor(3)
        };
        int borderColor = target.friendColor != 0 ? target.friendColor : RenderUtil.rgb(0, 0, 0);
        nameTags2D.add(new ScreenNameTag(anchor.x, anchor.y, entity.getHeldItem(), armor,
                fraction, borderColor));
    }

    /** Yaw-only bearing in y-down screen radians keeps the compass stable while looking up or down. */
    static float bearingAngle(float cameraYaw, double viewerX, double viewerZ, double tx, double tz) {
        float wantYaw = (float) (Math.toDegrees(Math.atan2(tz - viewerZ, tx - viewerX)) - 90.0);
        float yawDiff = MathHelper.wrapAngleTo180_float(wantYaw - cameraYaw);
        // y-down ring: front (0) -> top (-PI/2); right (+90) -> 0; behind (+-180) -> +PI/2 (bottom); left (-90) -> PI.
        return (float) (Math.toRadians(yawDiff) - Math.PI / 2.0);
    }

    /** &le;5 blocks: light red (255,100,100); &ge;15: black; linear fade between (dark red mid). */
    private static int distanceColor(double dist) {
        double t = MathHelper.clamp_double((15.0 - dist) / 10.0, 0.0, 1.0);
        return RenderUtil.rgb((int) (255 * t), (int) (100 * t), (int) (100 * t));
    }

    @EventTarget
    public void onRender2D(EventRender2D event) {
        int scale = event.getResolution().getScaleFactor();
        drawEspBoxes(scale);
        drawArrows(event.getResolution().getScaledWidth(), event.getResolution().getScaledHeight(), false);
        drawNameTags(scale);
    }

    private void drawEspBoxes(int scale) {
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

    /** Draw immediately outside rectangle batches to preserve ordering with ESP boxes. */
    private void drawArrows(float width, float height, boolean mirror) {
        if (arrows2D.isEmpty()) {
            return;
        }
        float cx = width / 2.0F;
        float cy = height / 2.0F;
        double size = arrowSize.get();
        double radius = mirror ? mirrorArrowRadius(arrowRadius.get(), size, width, height) : arrowRadius.get();

        // Extend arrows past open containers so the panel cannot hide their arrowheads.
        float rectLeft = 0, rectTop = 0, rectRight = 0, rectBottom = 0;
        boolean avoidGui = false;
        if (!mirror && Minecraft.getMinecraft().currentScreen instanceof GuiContainer) {
            GuiContainer gui = (GuiContainer) Minecraft.getMinecraft().currentScreen;
            final float pad = 8;
            rectLeft = gui.guiLeft - pad;
            rectTop = gui.guiTop - pad;
            rectRight = gui.guiLeft + gui.xSize + pad;
            rectBottom = gui.guiTop + gui.ySize + pad;
            avoidGui = cx > rectLeft && cx < rectRight && cy > rectTop && cy < rectBottom;
        }

        GlStateManager.enableBlend();
        GlStateManager.disableTexture2D();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        GlStateManager.disableCull(); // arrowhead winding flips with the ring angle — don't let any get culled
        Tessellator tessellator = Tessellator.getInstance();
        WorldRenderer wr = tessellator.getWorldRenderer();
        wr.begin(4, DefaultVertexFormats.POSITION_COLOR); // 4 = GL_TRIANGLES
        for (ScreenArrow arrow : arrows2D) {
            double effRadius = radius;
            if (avoidGui) {
                // Ray from the (inside-rect) centre exits the padded rect at min(tx, ty).
                double cos = Math.cos(arrow.angle);
                double sin = Math.sin(arrow.angle);
                double tx = cos > 1e-6 ? (rectRight - cx) / cos : cos < -1e-6 ? (rectLeft - cx) / cos : Double.MAX_VALUE;
                double ty = sin > 1e-6 ? (rectBottom - cy) / sin : sin < -1e-6 ? (rectTop - cy) / sin : Double.MAX_VALUE;
                effRadius = Math.max(radius, Math.min(tx, ty) + size); // +size: the whole arrowhead clears
            }
            appendArrow(wr, cx, cy, effRadius, size, arrow.angle, arrow.color);
        }
        tessellator.draw();
        GlStateManager.enableCull();
        GlStateManager.enableTexture2D();
        GlStateManager.disableBlend();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        arrows2D.clear();
    }

    static double mirrorArrowRadius(double requested, double size, float width, float height) {
        return Math.max(0, Math.min(requested, Math.min(width, height) / 2.0 - size - 1));
    }

    private void appendArrow(WorldRenderer wr, float cx, float cy, double radius, double size, float angle, int color) {
        double cos = Math.cos(angle);
        double sin = Math.sin(angle);
        double px = cx + cos * radius; // ring point this arrow sits on
        double py = cy + sin * radius;
        double tipX = px + cos * size;
        double tipY = py + sin * size;
        double baseX = px - cos * (size * 0.5);
        double baseY = py - sin * (size * 0.5);
        double half = size * 0.6; // base half-width, along the perpendicular (-sin, cos)
        double leftX = baseX - sin * half;
        double leftY = baseY + cos * half;
        double rightX = baseX + sin * half;
        double rightY = baseY - cos * half;
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;
        wr.pos(tipX, tipY, 0.0).color(r, g, b, 255).endVertex();
        wr.pos(leftX, leftY, 0.0).color(r, g, b, 255).endVertex();
        wr.pos(rightX, rightY, 0.0).color(r, g, b, 255).endVertex();
    }

    private void drawNameTags(int scale) {
        if (nameTags2D.isEmpty()) {
            return;
        }
        final int iconSize = 8; // 16px GUI icon * 0.5
        final int iconGap = 1;
        final int barBorder = 2;
        final int barInnerHeight = 3;
        final int rowBarGap = 2;
        final int minBarWidth = 60;

        for (ScreenNameTag tag : nameTags2D) {
            float anchorX = tag.x / scale;
            float anchorY = tag.y / scale;

            int icons = tag.held != null ? 1 : 0;
            for (ItemStack piece : tag.armor) {
                if (piece != null) {
                    icons++;
                }
            }
            int rowW = icons > 0 ? icons * iconSize + (icons - 1) * iconGap : 0;
            int barW = Math.max(rowW, minBarWidth);

            // Stack bottom-up from the anchor: bar closest to the head, icon row above it.
            int barBottom = Math.round(anchorY);
            int barTop = barBottom - (barInnerHeight + barBorder * 2);
            int barLeft = Math.round(anchorX - barW / 2.0F);

            // Row: held item, then worn armor (boots..helmet), left to right.
            if (icons > 0) {
                int iconY = barTop - rowBarGap - iconSize;
                int cursorX = Math.round(anchorX - rowW / 2.0F);
                RenderUtil.beginItems(); // one GL-light setup per tag, not per icon
                if (tag.held != null) {
                    RenderUtil.drawItemRaw(tag.held, cursorX, iconY, 0.5F);
                    cursorX += iconSize + iconGap;
                }
                for (ItemStack piece : tag.armor) {
                    if (piece == null) {
                        continue;
                    }
                    RenderUtil.drawItemRaw(piece, cursorX, iconY, 0.5F);
                    cursorX += iconSize + iconGap;
                }
                RenderUtil.endItems();
            }

            // batched per tag; one buffer for every tag if crowds ever make this show up.
            WorldRenderer bar = RenderUtil.beginQuads();
            RenderUtil.appendOutline(bar, barLeft, barTop, barLeft + barW, barBottom, barBorder, tag.borderColor);
            int fillWidth = Math.round((barW - barBorder * 2) * tag.healthFraction);
            RenderUtil.appendRect(bar, barLeft + barBorder, barTop + barBorder,
                    barLeft + barBorder + fillWidth, barBottom - barBorder,
                    RenderUtil.lerpRedGreen(tag.healthFraction));
            RenderUtil.endQuads();
        }
        nameTags2D.clear();
    }

    /** One selected entity, interpolated to this frame. */
    private static final class Target {
        final Entity entity;
        final int color;
        final int friendColor;
        final AxisAlignedBB box;
        final double centerX;
        final double centerY;
        final double centerZ;
        final double distance;

        Target(Entity entity, int color, int friendColor, AxisAlignedBB box,
               double centerX, double centerY, double centerZ, double distance) {
            this.entity = entity;
            this.color = color;
            this.friendColor = friendColor;
            this.box = box;
            this.centerX = centerX;
            this.centerY = centerY;
            this.centerZ = centerZ;
            this.distance = distance;
        }
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

    /** An off-screen target's crosshair-ring bearing, in radians. */
    private static final class ScreenArrow {
        final float angle;
        final int color;

        ScreenArrow(float angle, int color) {
            this.angle = angle;
            this.color = color;
        }
    }

    private static final class ScreenNameTag {
        final float x, y; // anchor above the head
        final ItemStack held;
        final ItemStack[] armor; // boots..helmet, entries may be null
        final float healthFraction;
        final int borderColor; // friend's colour, black otherwise

        ScreenNameTag(float x, float y, ItemStack held, ItemStack[] armor, float healthFraction, int borderColor) {
            this.x = x;
            this.y = y;
            this.held = held;
            this.armor = armor;
            this.healthFraction = healthFraction;
            this.borderColor = borderColor;
        }
    }
}
