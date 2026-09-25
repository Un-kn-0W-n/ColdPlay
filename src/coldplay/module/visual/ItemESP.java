package coldplay.module.visual;

import coldplay.event.EventRender2D;
import coldplay.event.EventRender3D;
import coldplay.event.EventRenderMirror;
import coldplay.event.EventTarget;
import coldplay.gui.Glass;
import coldplay.gui.GlassShader;
import coldplay.gui.Theme;
import coldplay.module.Category;
import coldplay.module.Module;
import coldplay.setting.ColorSetting;
import coldplay.setting.ModeSetting;
import coldplay.setting.NumberSetting;
import coldplay.util.ProjectionUtil;
import coldplay.util.RenderUtil;
import coldplay.util.ScreenProjector;
import coldplay.util.ScreenStrokes;
import coldplay.util.font.CustomFont;
import coldplay.util.font.FontRef;
import coldplay.util.font.Fonts;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumChatFormatting;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;

/** Dropped items are grouped and projected in the world pass; ground fills draw there, the rest on the HUD. */
public class ItemESP extends Module {
    private static final String MODE_CHIPS = "Chips";
    private static final String MODE_RINGS = "Rings";
    private static final String MODE_GLOW = "Glow";
    private static final String MODE_BRACKETS = "Brackets";

    // Same-type drops within this half-edge (blocks) share one summed count.
    private static final double TAG_CLUSTER_RADIUS = 1.5;

    // Sizes are GUI px, the design's board px times 0.75; the doubles are blocks.
    private static final int SEGMENTS = 32;
    private static final double CHIP_LIFT = 0.62;
    private static final float CHIP_RISE = 7.5F;
    private static final float CHIP_H = 15.0F;
    private static final float CHIP_PAD_L = 3.75F;
    private static final float CHIP_PAD_R = 6.0F;
    private static final float CHIP_GAP = 3.75F;
    private static final float CHIP_ICON = 9.75F;
    private static final Glass CHIP_GLASS = new Glass(0x800E1015, 0x800E1015, 0x2EFFFFFF, 10.5F, 1.4F, 16.5F, 6.0F, 0.26F);
    private static final double DOT_R = 0.16;
    private static final double DOT_Y = 0.01;
    private static final int DOT_FILL = 140;
    private static final double RING_Y = 0.02;
    private static final int RING_FILL = 41;
    private static final float RING_GLOW = 0.36F;
    private static final float RING_GLOW_A = 0.85F;
    private static final float RING_UNDER_W = 2.25F;
    private static final int RING_UNDER = 0x59000000;
    private static final float RING_CHIP_GAP = 6.0F;
    private static final float LINE_W = 1.2F;
    // a dropped sprite is half a block wide, RenderItem.renderItem halves the unit model
    private static final float ICON_BLOCKS = 0.5F;
    private static final double ICON_LIFT = 0.27;
    private static final float BLOB_R = 0.55F;
    private static final float BLOB_STOP = 0.55F;
    private static final int BLOB_CENTER = 179;
    private static final int BLOB_MID = 71;
    private static final int GLOW_TEXT = 0xFFFFFFFF;
    // the design pads its 12 px sprite by one px, two on top
    private static final float BRACKET_SIDE = ICON_BLOCKS * 7.0F / 12.0F;
    private static final float BRACKET_TOP = ICON_BLOCKS * 8.0F / 12.0F;
    private static final float BRACKET_LEN = 0.3F;
    private static final float BRACKET_GLOW = 0.32F;
    private static final float BRACKET_GLOW_A = 0.8F;
    private static final float BRACKET_UNDER_W = 2.4F;
    private static final int BRACKET_UNDER = 0x66000000;
    private static final int SMOKE = 0x2E0E1015;
    private static final float LABEL_GAP = 3.75F;
    private static final float TIMES_HALF = 1.5F;
    private static final float TIMES_W = 0.75F;
    private static final float TIMES_AXIS = 2.5F; // above the baseline
    // ScreenStrokes' glow stack, repeated so each bracket corner is one joined polyline
    private static final float[] GLOW_W = {14.0F, 10.0F, 6.0F, 3.0F};
    private static final float[] GLOW_A = {0.08F, 0.13F, 0.2F, 0.29F};
    private static final int TEXT = 0xFFF4F6F8;
    private static final int OUTLINE = 0x8C000000;
    private static final float OUTLINE_R = 1.2F;
    private static final FontRef CHIP_FONT = new FontRef(Fonts.GEIST_MONO_MEDIUM, 8.25F);
    private static final FontRef GLOW_FONT = new FontRef(Fonts.GEIST_MONO_MEDIUM, 8.625F);

    private final ModeSetting mode = add(new ModeSetting("Mode", MODE_CHIPS, MODE_CHIPS, MODE_RINGS, MODE_GLOW, MODE_BRACKETS)
            .describe("Chips with the count over each pile, Rings on the ground sized by count, a soft Glow behind the item, or corner Brackets."));
    private final NumberSetting range = add(new NumberSetting("Range", 64.0, 16.0, 256.0, 16.0).describe("How far to show dropped items, in blocks."));
    private final ColorSetting color = add(new ColorSetting("Color", 0x00FFC8).describe("Accent for items other than iron, gold, diamonds and emeralds."));

    private final List<Cluster> clusters = new ArrayList<Cluster>();
    private final ScreenProjector projector = new ScreenProjector();
    private final FloatBuffer view = BufferUtils.createFloatBuffer(16);
    private double rightX, rightY, rightZ;

    public ItemESP() {
        super("ItemESP", Category.VISUAL, "Highlights dropped items through walls.");
    }

    @Override
    protected void onDisable() {
        clusters.clear();
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
            // the mirror shows its texture flipped and frost reads the main frame, so no chips, icons or text there
            drawScreen(event.getScale(), false);
        }
    }

    @EventTarget
    public void onRender2D(EventRender2D event) {
        drawScreen(event.getResolution().getScaleFactor(), true);
    }

    // ---- world pass

    private void renderView(float partialTicks) {
        clusters.clear();
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP player = mc.thePlayer;
        WorldClient world = mc.theWorld;
        if (player == null || world == null) {
            return;
        }
        double maxDistSq = range.get() * range.get();
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
            double x = RenderUtil.interp(item.lastTickPosX, item.posX, partialTicks);
            double y = RenderUtil.interp(item.lastTickPosY, item.posY, partialTicks);
            double z = RenderUtil.interp(item.lastTickPosZ, item.posZ, partialTicks);
            cluster(stack, x, y, z).add(x, y, z, stack.stackSize);
        }
        if (clusters.isEmpty()) {
            return;
        }
        String m = mode.get();
        projector.capture();
        // the camera's right axis in world space; a step along it keeps the depth
        GL11.glGetFloatv(GL11.GL_MODELVIEW_MATRIX, view);
        rightX = view.get(0);
        rightY = view.get(4);
        rightZ = view.get(8);
        for (Cluster c : clusters) {
            project(c, m);
        }
        if (MODE_CHIPS.equals(m) || MODE_RINGS.equals(m)) {
            drawDiscs(MODE_RINGS.equals(m));
        }
        // far piles first so near ones sit on top
        clusters.sort((a, b) -> Double.compare(b.distance, a.distance));
    }

    private Cluster cluster(ItemStack stack, double x, double y, double z) {
        for (Cluster c : clusters) {
            if (c.stack.getItem() == stack.getItem() && c.stack.getItemDamage() == stack.getItemDamage()
                    && Math.abs(x - c.x) <= TAG_CLUSTER_RADIUS
                    && Math.abs(y - c.y) <= TAG_CLUSTER_RADIUS
                    && Math.abs(z - c.z) <= TAG_CLUSTER_RADIUS) {
                return c;
            }
        }
        Cluster c = new Cluster(stack, accent(stack.getItem()), x, y, z);
        clusters.add(c);
        return c;
    }

    private int accent(Item item) {
        if (item == Items.iron_ingot) {
            return 0xFFE4E7EB;
        } else if (item == Items.gold_ingot) {
            return 0xFFF2C230;
        } else if (item == Items.diamond) {
            return 0xFF4FE3D6;
        } else if (item == Items.emerald) {
            return 0xFF43D675;
        }
        return 0xFF000000 | color.get();
    }

    private void project(Cluster c, String m) {
        int n = c.drops.size();
        c.midX = c.sumX / n;
        c.midY = c.sumY / n;
        c.midZ = c.sumZ / n;
        c.distance = projector.distance(c.midX, c.midY, c.midZ);
        if (MODE_CHIPS.equals(m)) {
            c.anchor = projector.point(c.midX, c.midY + CHIP_LIFT, c.midZ);
        } else if (MODE_RINGS.equals(m)) {
            double r = ringRadius(c);
            c.anchor = projector.point(c.midX + r, c.midY, c.midZ);
            c.ringN = ring(c, r);
        } else {
            if (MODE_GLOW.equals(m)) {
                c.anchor = projector.point(c.midX, c.midY + ICON_LIFT, c.midZ);
                if (c.anchor != null) {
                    c.radius = BLOB_R * pxPerBlock(c.midX, c.midY + ICON_LIFT, c.midZ, c.anchor);
                }
            }
            icons(c);
        }
    }

    private static double ringRadius(Cluster c) {
        return 0.34 + 0.08 * Math.log(c.count + 1);
    }

    /** Screen points of a ground circle round the pile; 0 when one is behind the camera. */
    private int ring(Cluster c, double r) {
        for (int i = 0; i < SEGMENTS; i++) {
            double a = i * Math.PI * 2.0 / SEGMENTS;
            ProjectionUtil.Point p = projector.point(c.midX + Math.cos(a) * r, c.midY + RING_Y, c.midZ + Math.sin(a) * r);
            if (p == null) {
                return 0;
            }
            c.ring[i * 2] = p.x;
            c.ring[i * 2 + 1] = p.y;
        }
        return SEGMENTS;
    }

    /** Each drop's sprite center and scale, and the bracket bounds round them all. */
    private void icons(Cluster c) {
        c.icons = new float[c.drops.size() * 3];
        c.iconN = 0;
        c.left = c.top = Float.MAX_VALUE;
        c.right = c.bottom = -Float.MAX_VALUE;
        for (double[] d : c.drops) {
            ProjectionUtil.Point p = projector.point(d[0], d[1] + ICON_LIFT, d[2]);
            if (p == null) {
                continue;
            }
            float u = pxPerBlock(d[0], d[1] + ICON_LIFT, d[2], p);
            c.icons[c.iconN++] = p.x;
            c.icons[c.iconN++] = p.y;
            c.icons[c.iconN++] = u;
            c.left = Math.min(c.left, p.x - u * BRACKET_SIDE);
            c.right = Math.max(c.right, p.x + u * BRACKET_SIDE);
            c.top = Math.min(c.top, p.y - u * BRACKET_TOP);
            c.bottom = Math.max(c.bottom, p.y + u * BRACKET_SIDE);
        }
    }

    /** Framebuffer px one block spans at a point already projected to p. */
    private float pxPerBlock(double x, double y, double z, ProjectionUtil.Point p) {
        ProjectionUtil.Point q = projector.point(x + rightX, y + rightY, z + rightZ);
        return (float) Math.hypot(q.x - p.x, q.y - p.y);
    }

    /** Filled circles flat on the ground: a small dot under each chip, or the ring's tint. */
    private void drawDiscs(boolean rings) {
        Tessellator tessellator = Tessellator.getInstance();
        WorldRenderer wr = tessellator.getWorldRenderer();
        RenderUtil.beginWorldOverlay(1.0F);
        for (Cluster c : clusters) {
            double r = rings ? ringRadius(c) : DOT_R;
            double y = c.midY + (rings ? RING_Y : DOT_Y);
            int a = rings ? RING_FILL : DOT_FILL;
            int red = c.color >> 16 & 0xFF, green = c.color >> 8 & 0xFF, blue = c.color & 0xFF;
            wr.begin(GL11.GL_TRIANGLE_FAN, DefaultVertexFormats.POSITION_COLOR);
            wr.pos(c.midX, y, c.midZ).color(red, green, blue, a).endVertex();
            for (int i = 0; i <= SEGMENTS; i++) {
                double t = i * Math.PI * 2.0 / SEGMENTS;
                wr.pos(c.midX + Math.cos(t) * r, y, c.midZ + Math.sin(t) * r).color(red, green, blue, a).endVertex();
            }
            tessellator.draw();
        }
        RenderUtil.endWorldOverlay();
    }

    // ---- HUD pass

    private void drawScreen(int scale, boolean hud) {
        if (clusters.isEmpty()) {
            return;
        }
        String m = mode.get();
        if (MODE_CHIPS.equals(m)) {
            if (hud) {
                drawChips(scale, false);
            }
        } else if (MODE_RINGS.equals(m)) {
            for (Cluster c : clusters) {
                ScreenStrokes.glowLoop(c.ring, c.ringN, scale, RING_GLOW, c.color, RING_GLOW_A);
                ScreenStrokes.loop(c.ring, c.ringN, scale, RING_UNDER_W, RING_UNDER);
                ScreenStrokes.loop(c.ring, c.ringN, scale, LINE_W, c.color);
            }
            if (hud) {
                drawChips(scale, true);
            }
        } else if (MODE_GLOW.equals(m)) {
            for (Cluster c : clusters) {
                if (c.anchor != null) {
                    blob(c.anchor.x / scale, c.anchor.y / scale, c.radius / scale, c.color);
                }
            }
            if (hud) {
                drawIcons(scale);
                drawGlowCounts(scale);
            }
        } else {
            for (Cluster c : clusters) {
                if (c.iconN > 0) {
                    drawBrackets(c, scale);
                }
            }
            if (hud) {
                drawIcons(scale);
                drawBracketCounts(scale);
            }
        }
        clusters.clear();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        GlStateManager.enableBlend();
    }

    /** Chips: centered above the pile. Rings: left edge just past the ring's +X side. */
    private void drawChips(int scale, boolean rings) {
        Fonts.load(scale); // lazy init, needs a live GL context
        if (!Fonts.isLoaded()) {
            return;
        }
        CustomFont font = CHIP_FONT.get();
        GlassShader.capture();
        for (Cluster c : clusters) {
            if (c.anchor == null) {
                continue;
            }
            // a lone drop reads better by name, like the design's "Diamond"
            String value = rings || c.count > 1 ? String.valueOf(c.count)
                    : EnumChatFormatting.getTextWithoutFormattingCodes(c.stack.getDisplayName());
            float w = CHIP_PAD_L + CHIP_ICON + CHIP_GAP + font.getStringWidth(value) + CHIP_PAD_R;
            float ax = c.anchor.x / scale, ay = c.anchor.y / scale;
            float x = rings ? ax + RING_CHIP_GAP : ax - w / 2.0F;
            float y = (rings ? ay : ay - CHIP_RISE) - CHIP_H / 2.0F;
            GlassShader.frost(x, y, w, CHIP_H, CHIP_H / 2.0F, CHIP_GLASS);
            RenderUtil.beginItems();
            icon(c.stack, x + CHIP_PAD_L, y + (CHIP_H - CHIP_ICON) / 2.0F, CHIP_ICON);
            RenderUtil.endItems();
            font.drawString(value, x + CHIP_PAD_L + CHIP_ICON + CHIP_GAP, y + (CHIP_H - font.getHeight()) / 2.0F, TEXT);
        }
    }

    /** The design's radial gradient: 0.7 at the center, 0.28 at 55%, clear at the edge. */
    private static void blob(float cx, float cy, float r, int rgb) {
        int red = rgb >> 16 & 0xFF, green = rgb >> 8 & 0xFF, blue = rgb & 0xFF;
        Tessellator tessellator = Tessellator.getInstance();
        WorldRenderer wr = tessellator.getWorldRenderer();
        GlStateManager.enableBlend();
        GlStateManager.disableTexture2D();
        GlStateManager.disableAlpha(); // the fade sinks below the alpha test cutoff
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        GlStateManager.shadeModel(GL11.GL_SMOOTH);
        // angles run up from +x so the faces wind counter-clockwise on screen
        wr.begin(GL11.GL_TRIANGLE_FAN, DefaultVertexFormats.POSITION_COLOR);
        wr.pos(cx, cy, 0.0).color(red, green, blue, BLOB_CENTER).endVertex();
        for (int i = 0; i <= SEGMENTS; i++) {
            double a = -i * Math.PI * 2.0 / SEGMENTS;
            wr.pos(cx + Math.cos(a) * r * BLOB_STOP, cy + Math.sin(a) * r * BLOB_STOP, 0.0).color(red, green, blue, BLOB_MID).endVertex();
        }
        tessellator.draw();
        wr.begin(GL11.GL_TRIANGLE_STRIP, DefaultVertexFormats.POSITION_COLOR);
        for (int i = 0; i <= SEGMENTS; i++) {
            double a = -i * Math.PI * 2.0 / SEGMENTS;
            double cos = Math.cos(a), sin = Math.sin(a);
            wr.pos(cx + cos * r * BLOB_STOP, cy + sin * r * BLOB_STOP, 0.0).color(red, green, blue, BLOB_MID).endVertex();
            wr.pos(cx + cos * r, cy + sin * r, 0.0).color(red, green, blue, 0).endVertex();
        }
        tessellator.draw();
        GlStateManager.shadeModel(GL11.GL_FLAT);
        GlStateManager.enableAlpha();
        GlStateManager.enableTexture2D();
    }

    private static void drawBrackets(Cluster c, int scale) {
        float l = c.left / scale, t = c.top / scale, r = c.right / scale, b = c.bottom / scale;
        float len = Math.min(r - l, b - t) * BRACKET_LEN;
        for (int i = 0; i < GLOW_W.length; i++) {
            corners(l, t, r, b, len, GLOW_W[i] * BRACKET_GLOW, Theme.withAlpha(c.color, Math.round(255 * GLOW_A[i] * BRACKET_GLOW_A)));
        }
        GlassShader.rect(l, t, r - l, b - t, 0.0F, SMOKE, SMOKE);
        corners(l, t, r, b, len, BRACKET_UNDER_W, BRACKET_UNDER);
        corners(l, t, r, b, len, LINE_W, c.color);
    }

    private static void corners(float l, float t, float r, float b, float len, float width, int color) {
        GlassShader.polyline(l, t + len, l, t, l + len, t, width, color);
        GlassShader.polyline(r - len, t, r, t, r, t + len, width, color);
        GlassShader.polyline(r, b - len, r, b, r - len, b, width, color);
        GlassShader.polyline(l + len, b, l, b, l, b - len, width, color);
    }

    /** Every drop's own icon at the size of the sprite it sits on. */
    private void drawIcons(int scale) {
        RenderUtil.beginItems();
        for (Cluster c : clusters) {
            for (int i = 0; i < c.iconN; i += 3) {
                float size = c.icons[i + 2] * ICON_BLOCKS / scale;
                icon(c.stack, c.icons[i] / scale - size / 2.0F, c.icons[i + 1] / scale - size / 2.0F, size);
            }
        }
        RenderUtil.endItems();
    }

    private void drawGlowCounts(int scale) {
        Fonts.load(scale);
        if (!Fonts.isLoaded()) {
            return;
        }
        CustomFont font = GLOW_FONT.get();
        for (Cluster c : clusters) {
            if (c.anchor == null) {
                continue;
            }
            float r = c.radius / scale;
            font.drawStringWithOutline(String.valueOf(c.count), c.anchor.x / scale + r * 0.72F,
                    c.anchor.y / scale - r * 0.25F - font.getHeight() / 2.0F, GLOW_TEXT, OUTLINE, OUTLINE_R);
        }
    }

    private void drawBracketCounts(int scale) {
        Fonts.load(scale);
        if (!Fonts.isLoaded()) {
            return;
        }
        CustomFont font = CHIP_FONT.get();
        for (Cluster c : clusters) {
            if (c.iconN == 0) {
                continue;
            }
            float x = c.right / scale + LABEL_GAP;
            float top = (c.top + c.bottom) / 2.0F / scale - font.getHeight() / 2.0F;
            // the font's atlas stops at ASCII, so the sign is two strokes in the first monospace cell
            float cell = font.getStringWidth("0");
            float mx = x + cell / 2.0F, my = top + font.getAscent() - TIMES_AXIS;
            cross(mx, my, TIMES_W + OUTLINE_R * 2.0F, OUTLINE);
            cross(mx, my, TIMES_W, c.color);
            font.drawStringWithOutline(String.valueOf(c.count), x + cell, top, c.color, OUTLINE, OUTLINE_R);
        }
    }

    private static void cross(float x, float y, float width, int color) {
        GlassShader.line(x - TIMES_HALF, y - TIMES_HALF, x + TIMES_HALF, y + TIMES_HALF, width, color);
        GlassShader.line(x - TIMES_HALF, y + TIMES_HALF, x + TIMES_HALF, y - TIMES_HALF, width, color);
    }

    /** A GUI item icon {@code size} px square; call between RenderUtil.beginItems and endItems. */
    private static void icon(ItemStack stack, float x, float y, float size) {
        GlStateManager.pushMatrix();
        GlStateManager.translate(x, y, 0.0F);
        GlStateManager.scale(size / 16.0F, size / 16.0F, 1.0F);
        Minecraft.getMinecraft().getRenderItem().renderItemAndEffectIntoGUI(stack, 0, 0);
        GlStateManager.popMatrix();
    }

    /** Same-type drops near the first one, summed; projections are framebuffer px. */
    private static final class Cluster {
        final ItemStack stack;
        final int color;
        final double x;
        final double y;
        final double z;
        final List<double[]> drops = new ArrayList<double[]>();
        final float[] ring = new float[SEGMENTS * 2];
        int count;
        double sumX, sumY, sumZ;
        double midX, midY, midZ;
        double distance;
        ProjectionUtil.Point anchor; // null when behind the camera
        float radius;
        float[] icons; // x, y and px per block for each drop in front of the camera
        int iconN;
        int ringN;
        float left, top, right, bottom;

        Cluster(ItemStack stack, int color, double x, double y, double z) {
            this.stack = stack;
            this.color = color;
            this.x = x;
            this.y = y;
            this.z = z;
        }

        void add(double x, double y, double z, int n) {
            drops.add(new double[]{x, y, z});
            sumX += x;
            sumY += y;
            sumZ += z;
            count += n;
        }
    }
}
