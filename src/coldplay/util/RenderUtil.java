package coldplay.util;

import net.minecraft.client.Minecraft;
import net.minecraft.util.MathHelper;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.item.ItemStack;
import net.minecraft.entity.Entity;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.Vec3;
import org.lwjgl.opengl.GL11;

public final class RenderUtil {

    private RenderUtil() {
    }

    /**
     * Draws outward-CCW faces with culling so near and far faces do not double-blend the alpha.
     * Flat boxes remain visible from both sides. Caller owns blend/depth/matrix state; culling is
     * disabled on return to match {@link #beginWorldOverlay(float)}.
     */
    public static void drawFilledBox(AxisAlignedBB box, int r, int g, int b, int a) {
        Tessellator tessellator = Tessellator.getInstance();
        WorldRenderer worldrenderer = tessellator.getWorldRenderer();
        GlStateManager.enableCull();
        worldrenderer.begin(7, DefaultVertexFormats.POSITION_COLOR); // 7 = GL_QUADS
        appendFilledBox(worldrenderer, box, r, g, b, a);
        tessellator.draw();
        GlStateManager.disableCull(); // restore beginWorldOverlay's cull-free invariant for the caller
    }

    /** The six faces of {@code box} into an open GL_QUADS / POSITION_COLOR buffer (winding as in {@link #drawFilledBox}). */
    public static void appendFilledBox(WorldRenderer wr, AxisAlignedBB box, int r, int g, int b, int a) {
        wr.pos(box.minX, box.minY, box.minZ).color(r, g, b, a).endVertex();
        wr.pos(box.maxX, box.minY, box.minZ).color(r, g, b, a).endVertex();
        wr.pos(box.maxX, box.minY, box.maxZ).color(r, g, b, a).endVertex();
        wr.pos(box.minX, box.minY, box.maxZ).color(r, g, b, a).endVertex();
        wr.pos(box.minX, box.maxY, box.minZ).color(r, g, b, a).endVertex();
        wr.pos(box.minX, box.maxY, box.maxZ).color(r, g, b, a).endVertex();
        wr.pos(box.maxX, box.maxY, box.maxZ).color(r, g, b, a).endVertex();
        wr.pos(box.maxX, box.maxY, box.minZ).color(r, g, b, a).endVertex();
        wr.pos(box.minX, box.minY, box.minZ).color(r, g, b, a).endVertex();
        wr.pos(box.minX, box.maxY, box.minZ).color(r, g, b, a).endVertex();
        wr.pos(box.maxX, box.maxY, box.minZ).color(r, g, b, a).endVertex();
        wr.pos(box.maxX, box.minY, box.minZ).color(r, g, b, a).endVertex();
        wr.pos(box.minX, box.minY, box.maxZ).color(r, g, b, a).endVertex();
        wr.pos(box.maxX, box.minY, box.maxZ).color(r, g, b, a).endVertex();
        wr.pos(box.maxX, box.maxY, box.maxZ).color(r, g, b, a).endVertex();
        wr.pos(box.minX, box.maxY, box.maxZ).color(r, g, b, a).endVertex();
        wr.pos(box.minX, box.minY, box.minZ).color(r, g, b, a).endVertex();
        wr.pos(box.minX, box.minY, box.maxZ).color(r, g, b, a).endVertex();
        wr.pos(box.minX, box.maxY, box.maxZ).color(r, g, b, a).endVertex();
        wr.pos(box.minX, box.maxY, box.minZ).color(r, g, b, a).endVertex();
        wr.pos(box.maxX, box.minY, box.minZ).color(r, g, b, a).endVertex();
        wr.pos(box.maxX, box.maxY, box.minZ).color(r, g, b, a).endVertex();
        wr.pos(box.maxX, box.maxY, box.maxZ).color(r, g, b, a).endVertex();
        wr.pos(box.maxX, box.minY, box.maxZ).color(r, g, b, a).endVertex();
    }

    /**
     * The twelve edges of {@code box} into an open GL_LINES / POSITION_COLOR buffer. Vanilla's
     * {@code RenderGlobal.drawOutlinedBoundingBox} is three LINE_STRIP draws per box, which cannot be
     * concatenated; this can, so a hundred boxes are one draw.
     */
    public static void appendOutlineBox(WorldRenderer wr, AxisAlignedBB box, int r, int g, int b, int a) {
        double x0 = box.minX, y0 = box.minY, z0 = box.minZ, x1 = box.maxX, y1 = box.maxY, z1 = box.maxZ;
        edge(wr, x0, y0, z0, x1, y0, z0, r, g, b, a);
        edge(wr, x1, y0, z0, x1, y0, z1, r, g, b, a);
        edge(wr, x1, y0, z1, x0, y0, z1, r, g, b, a);
        edge(wr, x0, y0, z1, x0, y0, z0, r, g, b, a);
        edge(wr, x0, y1, z0, x1, y1, z0, r, g, b, a);
        edge(wr, x1, y1, z0, x1, y1, z1, r, g, b, a);
        edge(wr, x1, y1, z1, x0, y1, z1, r, g, b, a);
        edge(wr, x0, y1, z1, x0, y1, z0, r, g, b, a);
        edge(wr, x0, y0, z0, x0, y1, z0, r, g, b, a);
        edge(wr, x1, y0, z0, x1, y1, z0, r, g, b, a);
        edge(wr, x1, y0, z1, x1, y1, z1, r, g, b, a);
        edge(wr, x0, y0, z1, x0, y1, z1, r, g, b, a);
    }

    private static void edge(WorldRenderer wr, double ax, double ay, double az, double bx, double by, double bz,
                             int r, int g, int b, int a) {
        wr.pos(ax, ay, az).color(r, g, b, a).endVertex();
        wr.pos(bx, by, bz).color(r, g, b, a).endVertex();
    }

    /**
     * Shared filled-plus-wireframe highlight recipe. Call between {@link #beginWorldOverlay(float)}
     * and {@link #endWorldOverlay()} when combining it with other world primitives.
     */
    public static void drawHighlightedBox(AxisAlignedBB box, int r, int g, int b,
                                          int fillAlpha, int outlineAlpha) {
        drawFilledBox(box, r, g, b, fillAlpha);
        RenderGlobal.drawOutlinedBoundingBox(box, r, g, b, outlineAlpha);
    }

    /** Complete standalone highlighted-box pass for callers that do not already own overlay state. */
    public static void drawHighlightedBox(AxisAlignedBB box, int r, int g, int b,
                                          int fillAlpha, int outlineAlpha, float lineWidth) {
        beginWorldOverlay(lineWidth);
        drawHighlightedBox(box, r, g, b, fillAlpha, outlineAlpha);
        endWorldOverlay();
    }

    /**
     * Complete tracer plus cubic endpoint marker pass shared by combat and placement overlays.
     * Coordinates are world-space; camera translation and GL restoration are owned here.
     */
    public static void drawTracerMarker(Vec3 start, Vec3 end, double markerHalf,
                                        int r, int g, int b, int tracerAlpha,
                                        int fillAlpha, int outlineAlpha, float lineWidth) {
        beginWorldOverlay(lineWidth);
        Tessellator tessellator = Tessellator.getInstance();
        WorldRenderer wr = tessellator.getWorldRenderer();
        wr.begin(GL11.GL_LINES, DefaultVertexFormats.POSITION_COLOR);
        wr.pos(start.xCoord, start.yCoord, start.zCoord).color(r, g, b, tracerAlpha).endVertex();
        wr.pos(end.xCoord, end.yCoord, end.zCoord).color(r, g, b, tracerAlpha).endVertex();
        tessellator.draw();

        AxisAlignedBB marker = new AxisAlignedBB(
                end.xCoord - markerHalf, end.yCoord - markerHalf, end.zCoord - markerHalf,
                end.xCoord + markerHalf, end.yCoord + markerHalf, end.zCoord + markerHalf);
        drawHighlightedBox(marker, r, g, b, fillAlpha, outlineAlpha);
        endWorldOverlay();
    }

    public static int rgba(int r, int g, int b, int a) {
        return ((a & 0xFF) << 24) | ((r & 0xFF) << 16) | ((g & 0xFF) << 8) | (b & 0xFF);
    }

    public static int rgb(int r, int g, int b) {
        return rgba(r, g, b, 255);
    }

    /** Red (0.0) &rarr; green (1.0) linear lerp, packed opaque — health bars, break-progress fills. */
    public static int lerpRedGreen(float fraction) {
        return rgb(Math.round(255 * (1.0F - fraction)), Math.round(255 * fraction), 0);
    }

    /** Filled rectangle expressed as x/y/width/height (vs Gui's left/top/right/bottom). */
    public static void rect(float x, float y, float width, float height, int color) {
        Gui.drawRect((int) x, (int) y, (int) (x + width), (int) (y + height), color);
    }

    public static void rectBounds(int left, int top, int right, int bottom, int color) {
        Gui.drawRect(left, top, right, bottom, color);
    }

    /** 1px vertical line segment: column at {@code x} spanning [y0, y1). */
    public static void vLine(int x, int y0, int y1, int color) {
        rectBounds(x, Math.min(y0, y1), x + 1, Math.max(y0, y1), color);
    }

    /** 1px horizontal line segment between either-order x bounds at row {@code y}. */
    public static void hLine(int xa, int xb, int y, int color) {
        rectBounds(Math.min(xa, xb), y, Math.max(xa, xb), y + 1, color);
    }

    /** Hollow rectangle: {@code thickness}-px frame just inside the left/top/right/bottom bounds. */
    public static void outline(int left, int top, int right, int bottom, int thickness, int color) {
        Gui.drawRect(left, top, right, top + thickness, color);
        Gui.drawRect(left, bottom - thickness, right, bottom, color);
        Gui.drawRect(left, top, left + thickness, bottom, color);
        Gui.drawRect(right - thickness, top, right, bottom, color);
    }

    /**
     * Opens one POSITION_COLOR quad buffer with {@link Gui#drawRect}'s blend / no-texture state set
     * once, so a run of {@link #appendRect} calls is a single draw instead of a draw and six GL
     * toggles per rectangle. Pair with {@link #endQuads()}; no textured draw may happen in between.
     */
    public static WorldRenderer beginQuads() {
        GlStateManager.enableBlend();
        GlStateManager.disableTexture2D();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        WorldRenderer wr = Tessellator.getInstance().getWorldRenderer();
        wr.begin(7, DefaultVertexFormats.POSITION_COLOR); // 7 = GL_QUADS
        return wr;
    }

    public static void endQuads() {
        Tessellator.getInstance().draw();
        GlStateManager.enableTexture2D();
        GlStateManager.disableBlend();
    }

    /** {@link #rectBounds} into an open {@link #beginQuads()} buffer. */
    public static void appendRect(WorldRenderer wr, int left, int top, int right, int bottom, int argb) {
        int a = argb >>> 24;
        int r = argb >> 16 & 0xFF;
        int g = argb >> 8 & 0xFF;
        int b = argb & 0xFF;
        wr.pos(left, bottom, 0.0D).color(r, g, b, a).endVertex();
        wr.pos(right, bottom, 0.0D).color(r, g, b, a).endVertex();
        wr.pos(right, top, 0.0D).color(r, g, b, a).endVertex();
        wr.pos(left, top, 0.0D).color(r, g, b, a).endVertex();
    }

    /** {@link #outline} into an open {@link #beginQuads()} buffer. */
    public static void appendOutline(WorldRenderer wr, int left, int top, int right, int bottom, int thickness, int argb) {
        appendRect(wr, left, top, right, top + thickness, argb);
        appendRect(wr, left, bottom - thickness, right, bottom, argb);
        appendRect(wr, left, top, left + thickness, bottom, argb);
        appendRect(wr, right - thickness, top, right, bottom, argb);
    }

    /** Floor for a 1px scroll indicator's thumb, so a long list still leaves something grabbable. */
    public static final int SCROLL_THUMB_MIN = 14;

    /** Thumb height for a {@code trackH} track showing {@code viewH} of {@code contentH}. */
    public static int scrollThumbHeight(int trackH, int viewH, int contentH) {
        if (trackH <= 0) {
            return 0;
        }
        int natural = (int) Math.round(trackH * (viewH / (double) Math.max(1, contentH)));
        return trackH <= SCROLL_THUMB_MIN
                ? trackH : MathHelper.clamp_int(natural, SCROLL_THUMB_MIN, trackH);
    }

    /** Thumb offset down the track at {@code scroll} of {@code maxScroll}; 0 when there is no travel. */
    public static int scrollThumbOffset(int trackH, int thumbH, int scroll, int maxScroll) {
        int travel = trackH - thumbH;
        return travel <= 0 || maxScroll <= 0
                ? 0 : (int) Math.round(travel * (scroll / (double) maxScroll));
    }

    public static void drawBorderedRect(int left, int top, int right, int bottom, int fill, int border) {
        drawBorderedRect(left, top, right, bottom, fill, border, 1);
    }

    public static void drawBorderedRect(int left, int top, int right, int bottom, int fill, int border, int thickness) {
        Gui.drawRect(left, top, right, bottom, fill);
        outline(left, top, right, bottom, thickness, border);
    }

    /**
     * Filled rounded rectangle: a single GL_POLYGON fan of four quarter-circle arcs stepped at 5
     * degrees, radius clamped to half the short side (radius <= 0 falls back to {@link #rect}).
     * Colour is packed ARGB.
     */
    public static void drawRoundedRect(double x, double y, double width, double height, double radius, int color) {
        if (width <= 0 || height <= 0) {
            return;
        }
        radius = Math.min(radius, Math.min(width, height) / 2);
        if (radius <= 0) {
            rect((float) x, (float) y, (float) width, (float) height, color);
            return;
        }
        float alpha = (color >> 24 & 0xFF) / 255.0F;
        float red = (color >> 16 & 0xFF) / 255.0F;
        float green = (color >> 8 & 0xFF) / 255.0F;
        float blue = (color & 0xFF) / 255.0F;
        GlStateManager.pushMatrix();
        GlStateManager.disableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.disableAlpha();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        Tessellator tessellator = Tessellator.getInstance();
        WorldRenderer worldrenderer = tessellator.getWorldRenderer();
        worldrenderer.begin(GL11.GL_POLYGON, DefaultVertexFormats.POSITION_COLOR);
        for (int deg = 0; deg <= 90; deg += 5) { // top-left: top edge -> left edge
            worldrenderer.pos(x + radius + Math.sin(Math.toRadians(deg + 180)) * radius,
                    y + radius + Math.cos(Math.toRadians(deg + 180)) * radius, 0)
                    .color(red, green, blue, alpha).endVertex();
        }
        for (int deg = 90; deg <= 180; deg += 5) { // bottom-left: left edge -> bottom edge
            worldrenderer.pos(x + radius + Math.sin(Math.toRadians(deg + 180)) * radius,
                    y + height - radius + Math.cos(Math.toRadians(deg + 180)) * radius, 0)
                    .color(red, green, blue, alpha).endVertex();
        }
        for (int deg = 0; deg <= 90; deg += 5) { // bottom-right: bottom edge -> right edge
            worldrenderer.pos(x + width - radius + Math.sin(Math.toRadians(deg)) * radius,
                    y + height - radius + Math.cos(Math.toRadians(deg)) * radius, 0)
                    .color(red, green, blue, alpha).endVertex();
        }
        for (int deg = 90; deg <= 180; deg += 5) { // top-right: right edge -> top edge
            worldrenderer.pos(x + width - radius + Math.sin(Math.toRadians(deg)) * radius,
                    y + radius + Math.cos(Math.toRadians(deg)) * radius, 0)
                    .color(red, green, blue, alpha).endVertex();
        }
        tessellator.draw();
        GlStateManager.enableAlpha();
        GlStateManager.disableBlend(); // mirror the entry state; Gui.drawRect makes the same promise
        GlStateManager.enableTexture2D();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        GlStateManager.popMatrix();
    }

    /** See-through world-space overlay. Pair with {@link #endWorldOverlay()}. */
    public static void beginWorldOverlay(float lineWidth) {
        GlStateManager.pushMatrix();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        GlStateManager.disableTexture2D();
        GlStateManager.disableLighting();
        GlStateManager.disableDepth();
        GlStateManager.depthMask(false);
        GlStateManager.disableCull();
        GL11.glLineWidth(lineWidth);
        RenderManager manager = Minecraft.getMinecraft().getRenderManager();
        GlStateManager.translate(-manager.viewerPosX, -manager.viewerPosY, -manager.viewerPosZ);
    }

    /** Restores everything {@link #beginWorldOverlay} touched (also reused as the tail of custom setups). */
    public static void endWorldOverlay() {
        GL11.glLineWidth(1.0F);
        GlStateManager.enableCull();
        GlStateManager.depthMask(true);
        GlStateManager.enableDepth();
        GlStateManager.enableTexture2D();
        GlStateManager.disableBlend();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        GlStateManager.popMatrix();
    }

    /**
     * Clips subsequent draws to a GUI-space rectangle. GL's scissor box is specified in framebuffer
     * pixels with a bottom-left origin, so the GUI rect is scaled by {@code scaleFactor} (from the
     * frame's {@link net.minecraft.client.gui.ScaledResolution}) and flipped against the window
     * height. Framebuffer-space, so it is unaffected by any current modelview transform. Pair with
     * {@link #endScissor()}.
     */
    public static void beginScissor(double x, double y, double width, double height, int scaleFactor) {
        int fx = Math.max(0, (int) Math.floor(x * scaleFactor));
        int fy = Math.max(0, (int) Math.floor(Minecraft.getMinecraft().displayHeight - (y + height) * scaleFactor));
        int fw = Math.max(0, (int) Math.ceil(width * scaleFactor));
        int fh = Math.max(0, (int) Math.ceil(height * scaleFactor));
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor(fx, fy, fw, fh);
    }

    public static void endScissor() {
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
    }

    public static double interp(double last, double cur, float partialTicks) {
        return MathHelper.denormalizeClamp(last, cur, partialTicks);
    }

    public static Vec3 interpolatedPosition(Entity entity, float partialTicks) {
        return new Vec3(
                interp(entity.lastTickPosX, entity.posX, partialTicks),
                interp(entity.lastTickPosY, entity.posY, partialTicks),
                interp(entity.lastTickPosZ, entity.posZ, partialTicks));
    }

    public static boolean hovered(double mouseX, double mouseY, double x, double y, double width, double height) {
        return mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height;
    }

    /** Half-open rectangle containment, useful for adjacent GUI cells without shared-edge overlap. */
    public static boolean hoveredExclusive(double mouseX, double mouseY,
                                           double x, double y, double width, double height) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }

    /**
     * Draws a 16x16 GUI sprite and resets lighting/colour. Depth is disabled on return because icons
     * write at z&approx;150; otherwise later z=0 tooltips would fail the depth test over them.
     */
    public static void drawItem(ItemStack stack, int x, int y) {
        if (stack == null) {
            return;
        }
        beginItems();
        Minecraft.getMinecraft().getRenderItem().renderItemAndEffectIntoGUI(stack, x, y);
        endItems();
    }

    /**
     * Item-lighting bracket for a run of {@link #drawItemRaw} icons. Vanilla's hotbar sets the GL
     * lights once per row, not per icon: {@code enableGUIStandardItemLighting} is nine driver calls
     * that {@link GlStateManager} does not cache.
     */
    public static void beginItems() {
        RenderHelper.enableGUIStandardItemLighting();
        GlStateManager.enableRescaleNormal();
    }

    /** Closes {@link #beginItems()} and resets what the icons dirtied (see {@link #drawItem(ItemStack, int, int)}). */
    public static void endItems() {
        GlStateManager.disableRescaleNormal();
        RenderHelper.disableStandardItemLighting();
        GlStateManager.disableDepth();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
    }

    /** One icon inside a {@link #beginItems()} bracket, drawn at {@code scale}x size. */
    public static void drawItemRaw(ItemStack stack, int x, int y, float scale) {
        GlStateManager.pushMatrix();
        GlStateManager.translate(x, y, 0.0F);
        GlStateManager.scale(scale, scale, 1.0F);
        Minecraft.getMinecraft().getRenderItem().renderItemAndEffectIntoGUI(stack, 0, 0);
        GlStateManager.popMatrix();
    }

    /** Like {@link #drawItem(ItemStack, int, int)} but drawn at {@code scale}x size (e.g. 0.5 for 8x8). */
    public static void drawItem(ItemStack stack, int x, int y, float scale) {
        if (stack == null) {
            return;
        }
        GlStateManager.pushMatrix();
        GlStateManager.translate(x, y, 0.0F);
        GlStateManager.scale(scale, scale, 1.0F);
        drawItem(stack, 0, 0);
        GlStateManager.popMatrix();
    }
}
