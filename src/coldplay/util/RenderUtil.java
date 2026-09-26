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

import java.util.List;

public final class RenderUtil {

    private RenderUtil() {
    }

    /** Culled so near and far faces do not double-blend. Culling is left disabled on return. */
    public static void drawFilledBox(AxisAlignedBB box, int r, int g, int b, int a) {
        Tessellator tessellator = Tessellator.getInstance();
        WorldRenderer worldrenderer = tessellator.getWorldRenderer();
        GlStateManager.enableCull();
        worldrenderer.begin(7, DefaultVertexFormats.POSITION_COLOR); // 7 = GL_QUADS
        appendFilledBox(worldrenderer, box, r, g, b, a);
        tessellator.draw();
        GlStateManager.disableCull();
    }

    /** The six faces of {@code box} into an open GL_QUADS / POSITION_COLOR buffer. */
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

    /** The twelve edges of {@code box} into an open GL_LINES buffer, so many boxes share one draw. */
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

    /** One culled fill pass and one edge pass over every box. Sets up and restores the overlay state itself. */
    public static void drawBoxes(List<AxisAlignedBB> boxes, int fillRgb, int fillAlpha, int lineRgb,
                                 boolean filled, boolean outline, float lineWidth) {
        Tessellator tessellator = Tessellator.getInstance();
        WorldRenderer wr = tessellator.getWorldRenderer();
        beginWorldOverlay(lineWidth);
        if (filled) {
            GlStateManager.enableCull(); // stop near/far faces double-blending
            wr.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR);
            for (AxisAlignedBB box : boxes) {
                appendFilledBox(wr, box, (fillRgb >> 16) & 0xFF, (fillRgb >> 8) & 0xFF, fillRgb & 0xFF, fillAlpha);
            }
            tessellator.draw();
            GlStateManager.disableCull();
        }
        if (outline) {
            wr.begin(GL11.GL_LINES, DefaultVertexFormats.POSITION_COLOR);
            for (AxisAlignedBB box : boxes) {
                appendOutlineBox(wr, box, (lineRgb >> 16) & 0xFF, (lineRgb >> 8) & 0xFF, lineRgb & 0xFF, 255);
            }
            tessellator.draw();
        }
        endWorldOverlay();
    }

    /** Filled plus outlined box. Call between beginWorldOverlay and endWorldOverlay. */
    public static void drawHighlightedBox(AxisAlignedBB box, int r, int g, int b,
                                          int fillAlpha, int outlineAlpha) {
        drawFilledBox(box, r, g, b, fillAlpha);
        RenderGlobal.drawOutlinedBoundingBox(box, r, g, b, outlineAlpha);
    }

    /** Standalone version that sets up and restores the overlay state itself. */
    public static void drawHighlightedBox(AxisAlignedBB box, int r, int g, int b,
                                          int fillAlpha, int outlineAlpha, float lineWidth) {
        beginWorldOverlay(lineWidth);
        drawHighlightedBox(box, r, g, b, fillAlpha, outlineAlpha);
        endWorldOverlay();
    }

    /** Tracer line plus a cube marker at {@code end}, in world space. */
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

    /** Filled rectangle as x/y/width/height rather than Gui's left/top/right/bottom. */
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

    /** Frame of {@code thickness} px just inside the bounds. */
    public static void outline(int left, int top, int right, int bottom, int thickness, int color) {
        Gui.drawRect(left, top, right, top + thickness, color);
        Gui.drawRect(left, bottom - thickness, right, bottom, color);
        Gui.drawRect(left, top, left + thickness, bottom, color);
        Gui.drawRect(right - thickness, top, right, bottom, color);
    }

    /**
     * Opens one quad buffer with drawRect's blend state so a run of appendRect calls is a single
     * draw. Pair with {@link #endQuads()}; no textured draw may happen in between.
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

    public static final int SCROLL_THUMB_MIN = 14; // px

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

    /** Filled rounded rectangle; radius is clamped to half the short side. */
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
        GlStateManager.disableBlend();
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

    /** Restores everything {@link #beginWorldOverlay} touched. */
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
     * Clips later draws to a GUI-space rectangle. GL scissor is in framebuffer pixels with a
     * bottom-left origin, hence the scale and flip.
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

    /** Half-open containment, for adjacent cells without shared-edge overlap. */
    public static boolean hoveredExclusive(double mouseX, double mouseY,
                                           double x, double y, double width, double height) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }

    /** 16x16 GUI sprite. Depth is left disabled so later z=0 tooltips draw over the z=150 icon. */
    public static void drawItem(ItemStack stack, int x, int y) {
        if (stack == null) {
            return;
        }
        beginItems();
        Minecraft.getMinecraft().getRenderItem().renderItemAndEffectIntoGUI(stack, x, y);
        endItems();
    }

    /**
     * Item lighting for a run of drawItemRaw icons; enableGUIStandardItemLighting is not cached by
     * GlStateManager.
     */
    public static void beginItems() {
        RenderHelper.enableGUIStandardItemLighting();
        GlStateManager.enableRescaleNormal();
    }

    /** Closes {@link #beginItems()} and resets what the icons dirtied. */
    public static void endItems() {
        GlStateManager.disableRescaleNormal();
        RenderHelper.disableStandardItemLighting();
        GlStateManager.disableDepth();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
    }

    /** One icon inside a {@link #beginItems()} bracket at {@code scale}x. */
    public static void drawItemRaw(ItemStack stack, float x, float y, float scale) {
        GlStateManager.pushMatrix();
        GlStateManager.translate(x, y, 0.0F);
        GlStateManager.scale(scale, scale, 1.0F);
        Minecraft.getMinecraft().getRenderItem().renderItemAndEffectIntoGUI(stack, 0, 0);
        GlStateManager.popMatrix();
    }

    /** {@link #drawItem(ItemStack, int, int)} at {@code scale}x. */
    public static void drawItem(ItemStack stack, float x, float y, float scale) {
        if (stack == null) {
            return;
        }
        GlStateManager.pushMatrix();
        GlStateManager.translate(x, y, 0.0F);
        GlStateManager.scale(scale, scale, 1.0F);
        drawItem(stack, 0, 0);
        GlStateManager.popMatrix();
    }

    /** Scales later draws around (x, y); close with GlStateManager.popMatrix(). */
    public static void pushScale(float x, float y, float scale) {
        GlStateManager.pushMatrix();
        GlStateManager.translate(x, y, 0.0F);
        GlStateManager.scale(scale, scale, 1.0F);
        GlStateManager.translate(-x, -y, 0.0F);
    }
}
