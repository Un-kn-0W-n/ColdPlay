package coldplay.module.visual;

import coldplay.event.EventRender2D;
import coldplay.event.EventTarget;
import coldplay.gui.Glass;
import coldplay.gui.GlassShader;
import coldplay.gui.Theme;
import coldplay.module.Category;
import coldplay.module.Module;
import coldplay.setting.ModeSetting;
import coldplay.util.RenderUtil;
import coldplay.util.font.CustomFont;
import coldplay.util.font.FontRef;
import coldplay.util.font.Fonts;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;

/** Blocks left in the held stack, drawn around the crosshair. */
public class BlockCounter extends Module {

    private static final String HALO = "Halo";
    private static final String TILE = "Tile";
    private static final String PILL = "Pill";
    private static final String STACKS = "Stacks";
    private static final String TICKER = "Ticker";

    private static final int STACK = 64;
    private static final int LOW = 8; // at or under this the accent pulses
    private static final long PULSE_NANOS = 400_000_000L;
    private static final long IDLE_NANOS = 10_000_000_000L;

    // animation lengths, seconds
    private static final float FILL_IN = 0.35F;
    private static final float SLIDE_IN = 0.25F;
    private static final float BUMP = 0.12F;
    private static final float ROLL = 0.12F;
    private static final float FLOAT = 0.6F;

    // Sizes are GUI px, offsets are from the crosshair center.
    private static final float LINE_W = 1.5F;
    private static final float HALO_R = 12.75F;
    private static final float HALO_UNDER_W = 2.625F;
    private static final float HALO_CHIP_TOP = 18.0F;
    private static final float HALO_CHIP_H = 13.5F;
    private static final float HALO_PAD_L = 3.75F;
    private static final float HALO_PAD_R = 5.25F;
    private static final float HALO_GAP = 3.0F;
    private static final float HALO_ICON = 8.25F;

    private static final float TILE_LEFT = 16.5F;
    private static final float TILE_SIZE = 25.5F;
    private static final float TILE_RADIUS = 6.75F;
    private static final float TILE_ICON = 13.5F;
    private static final float TILE_RING = 3.0F; // ring centerline out from the tile edge
    private static final float TILE_TEXT = 33.75F; // from the tile's left edge
    private static final float TILE_COUNT_TOP = -11.25F;
    private static final float TILE_COUNT_LINE = 12.0F;
    private static final float TILE_WORD_TOP = 0.75F;
    private static final float TILE_SLIDE = 4.5F;
    private static final float WORD_LINE = 9.0F;

    private static final float PILL_TOP = 29.5F; // above the center
    private static final float PILL_H = 19.5F;
    private static final float PILL_PAD_L = 6.0F;
    private static final float PILL_PAD_R = 8.25F;
    private static final float PILL_GAP = 5.25F;
    private static final float PILL_ICON = 9.75F;
    private static final float PILL_COUNT_W = 12.75F;
    private static final float BAR_W = 28.5F;
    private static final float BAR_H = 2.25F;
    private static final float BAR_GLOW = 4.5F;

    private static final float STACKS_TOP = 28.0F; // above the center
    private static final float STACKS_H = 16.5F;
    private static final float STACKS_PAD_L = 6.75F;
    private static final float STACKS_PAD_R = 7.5F;
    private static final float STACKS_GAP = 6.0F;
    private static final float SEG_W = 18.0F;
    private static final float SEG_H = 4.5F;
    private static final float SEG_R = 1.5F;
    private static final float SEG_GAP = 2.25F;
    private static final float SEG_OUTLINE_W = 0.75F;
    private static final float SEG_GLOW = 3.75F;

    private static final float TICKER_LEFT = 12.0F;
    private static final float TICKER_TOP = -15.0F; // the number's line box, which also clips the roll
    private static final float TICKER_LINE = 18.0F;
    private static final float TICKER_BOX_W = 45.0F;
    private static final float TICKER_RISE = 9.0F;
    private static final float TICKER_WORD_LEFT = 12.75F;
    private static final float TICKER_WORD_TOP = 3.0F;
    private static final float FLOAT_GAP = 3.0F;
    private static final float FLOAT_TOP = -13.5F;
    private static final float FLOAT_LINE = 10.5F;
    private static final float FLOAT_RISE = 10.5F;
    private static final float SHADOW_DROP = 0.75F;
    private static final float SHADOW_HALO = 0.5F;

    // a css glow as stacked rounded rects, outermost first, as a share of the glow color's alpha
    private static final float[] GLOW_A = {0.05F, 0.09F, 0.14F};

    private static final int TEXT = 0xFFF4F6F8;
    private static final int WHITE = 0xFFFFFFFF;
    private static final int UNDER = 0x47000000;
    private static final int TRACK = 0x2EFFFFFF;
    private static final int TILE_WORD = 0x8FF4F6F8;
    private static final int TICKER_WORD = 0xB8FFFFFF;
    private static final int BAR_TRACK = 0x24FFFFFF;
    private static final int TOTAL_DIM = 0x80F4F6F8;
    private static final int SEG_TRACK = 0x1FFFFFFF;
    private static final int SEG_ACTIVE = 0xEBFFFFFF;
    private static final int SEG_IDLE = 0x6BFFFFFF;
    private static final int SEG_OUTLINE = 0x47FFFFFF;
    private static final int SEG_SHINE = 0x59FFFFFF;
    private static final int DROP = 0xCCFFFFFF;
    private static final int REFILL = 0xFF7EE08E;
    private static final Glass GLASS = new Glass(0x800E1015, 0x800E1015, 0x2EFFFFFF, 10.5F, 1.4F, 16.5F, 6.0F, 0.26F);

    private static final FontRef SMALL_FONT = new FontRef(Fonts.GEIST_MONO_MEDIUM, 8.25F);
    private static final FontRef TILE_FONT = new FontRef(Fonts.GEIST_MONO_MEDIUM, 9.0F);
    private static final FontRef PILL_FONT = new FontRef(Fonts.GEIST_MONO_MEDIUM, 9.375F);
    private static final FontRef PILL_TOTAL_FONT = new FontRef(Fonts.GEIST_MONO, 8.25F);
    private static final FontRef STACKS_FONT = new FontRef(Fonts.GEIST_MONO_MEDIUM, 8.625F);
    private static final FontRef TICKER_FONT = new FontRef(Fonts.GEIST_MONO_MEDIUM, 13.5F); // no mono semibold is bundled
    private static final FontRef WORD_FONT = new FontRef(Fonts.GEIST, 7.125F);

    private final ModeSetting mode = add(new ModeSetting("Mode", HALO, HALO, TILE, PILL, STACKS, TICKER)
            .describe("Halo rings the crosshair, Tile and Ticker sit right of it, Pill and Stacks above it."));

    // the stack held last frame, to tell a placement from a switch
    private int lastSlot = -1;
    private int lastCount;
    private int prevCount; // what the ticker rolls away from
    private long placedAt = System.nanoTime() - IDLE_NANOS;
    private long switchedAt = placedAt;

    // this frame's values, shared by the draw methods
    private float cx;
    private float cy;
    private int count;
    private int ramp;
    private int accent;
    private int number;
    private float fill;
    private float bump;
    private float since;
    private float sw;

    public BlockCounter() {
        super("BlockCounter", Category.VISUAL, "Blocks left in your held stack, around the crosshair.");
    }

    @EventTarget
    public void onRender2D(EventRender2D event) {
        EntityPlayerSP player = Minecraft.getMinecraft().thePlayer;
        if (player == null) {
            return;
        }
        ItemStack held = player.getHeldItem();
        if (!isBlocks(held)) {
            return;
        }
        long now = System.nanoTime();
        int slot = player.inventory.currentItem;
        if (lastSlot != -1 && slot != lastSlot) {
            // moved to another stack, usually Scaffold after one ran out
            ItemStack old = player.inventory.mainInventory[lastSlot];
            prevCount = old == null ? 0 : old.stackSize;
            switchedAt = now;
        } else if (held.stackSize < lastCount) {
            prevCount = lastCount;
            placedAt = now;
        }
        lastSlot = slot;
        lastCount = held.stackSize;

        ScaledResolution resolution = event.getResolution();
        Fonts.load(resolution.getScaleFactor()); // lazy init, needs a live GL context
        if (!Fonts.isLoaded()) {
            return;
        }
        int total = 0;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = player.inventory.mainInventory[i];
            if (isBlocks(stack)) {
                total += stack.stackSize;
            }
        }

        count = held.stackSize;
        since = (now - placedAt) / 1.0E9F;
        sw = (now - switchedAt) / 1.0E9F;
        float pulse = 0.62F + 0.38F * (0.5F + 0.5F * (float) Math.cos(now % PULSE_NANOS / (double) PULSE_NANOS * 2.0 * Math.PI));
        ramp = Theme.healthColor(Math.min(1.0F, count / 32.0F));
        accent = count <= LOW ? Theme.applyAlpha(ramp, pulse) : ramp;
        number = count <= LOW ? ramp : TEXT;
        fill = Math.min(count, STACK) / (float) STACK * ease(sw / FILL_IN);
        bump = 1.0F + 0.14F * Math.max(0.0F, 1.0F - since / BUMP);
        // the vanilla crosshair is centered half a pixel right of and below the middle
        cx = resolution.getScaledWidth() / 2 + 0.5F;
        cy = resolution.getScaledHeight() / 2 + 0.5F;

        String m = mode.get();
        if (HALO.equals(m)) {
            drawHalo(held);
        } else if (TILE.equals(m)) {
            drawTile(held);
        } else if (PILL.equals(m)) {
            drawPill(held, total);
        } else if (STACKS.equals(m)) {
            drawStacks(player, total);
        } else {
            drawTicker(resolution.getScaleFactor());
        }
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        GlStateManager.enableBlend();
    }

    /** A ring around the crosshair that fills clockwise, and a small chip under it. */
    private void drawHalo(ItemStack held) {
        float d = HALO_R * 2.0F;
        GlassShader.arc(cx - HALO_R, cy - HALO_R, d, d, HALO_R, HALO_UNDER_W, 0.0F, 1.0F, UNDER);
        GlassShader.arc(cx - HALO_R, cy - HALO_R, d, d, HALO_R, LINE_W, 0.0F, 1.0F, TRACK);
        GlassShader.arc(cx - HALO_R, cy - HALO_R, d, d, HALO_R, LINE_W, 0.0F, fill, accent);

        CustomFont font = SMALL_FONT.get();
        String text = String.valueOf(count);
        float w = HALO_PAD_L + HALO_ICON + HALO_GAP + font.getStringWidth(text) + HALO_PAD_R;
        float x = cx - w / 2.0F;
        float y = cy + HALO_CHIP_TOP;
        GlassShader.capture();
        GlassShader.frost(x, y, w, HALO_CHIP_H, HALO_CHIP_H / 2.0F, GLASS);
        icon(held, x + HALO_PAD_L + HALO_ICON / 2.0F, y + HALO_CHIP_H / 2.0F, HALO_ICON);
        font.drawString(text, x + HALO_PAD_L + HALO_ICON + HALO_GAP, inLine(font, y, HALO_CHIP_H), number);
    }

    /** A glass tile right of the crosshair with a ring around it, the count beside it. */
    private void drawTile(ItemStack held) {
        // after a switch the whole tile slides in from the right and fades up
        float slide = ease(sw / SLIDE_IN);
        float a = 0.4F + 0.6F * slide;
        float x = cx + TILE_LEFT + (1.0F - slide) * TILE_SLIDE;
        float y = cy - TILE_SIZE / 2.0F;
        GlassShader.capture();
        GlassShader.frost(x, y, TILE_SIZE, TILE_SIZE, TILE_RADIUS, GLASS, a);
        float ring = TILE_SIZE + 2.0F * TILE_RING;
        GlassShader.arc(x - TILE_RING, y - TILE_RING, ring, ring, TILE_RADIUS + TILE_RING, LINE_W, 0.0F, 1.0F,
                Theme.applyAlpha(TRACK, a));
        GlassShader.arc(x - TILE_RING, y - TILE_RING, ring, ring, TILE_RADIUS + TILE_RING, LINE_W, 0.0F, fill,
                Theme.applyAlpha(accent, a));
        icon(held, x + TILE_SIZE / 2.0F, y + TILE_SIZE / 2.0F, TILE_ICON * bump);

        CustomFont countFont = TILE_FONT.get();
        CustomFont wordFont = WORD_FONT.get();
        countFont.drawString(String.valueOf(count), x + TILE_TEXT, inLine(countFont, cy + TILE_COUNT_TOP, TILE_COUNT_LINE),
                Theme.applyAlpha(number, a));
        wordFont.drawString(word(), x + TILE_TEXT, inLine(wordFont, cy + TILE_WORD_TOP, WORD_LINE),
                Theme.applyAlpha(TILE_WORD, a));
    }

    /** A pill above the crosshair: block, count, a fill bar and the hotbar total. */
    private void drawPill(ItemStack held, int total) {
        CustomFont countFont = PILL_FONT.get();
        CustomFont totalFont = PILL_TOTAL_FONT.get();
        String text = String.valueOf(count);
        String totalText = "/ " + total;
        float w = PILL_PAD_L + PILL_ICON + PILL_GAP + PILL_COUNT_W + PILL_GAP + BAR_W + PILL_GAP
                + totalFont.getStringWidth(totalText) + PILL_PAD_R;
        float x = cx - w / 2.0F;
        float y = cy - PILL_TOP;
        GlassShader.capture();
        GlassShader.frost(x, y, w, PILL_H, PILL_H / 2.0F, GLASS);

        float left = x + PILL_PAD_L;
        icon(held, left + PILL_ICON / 2.0F, y + PILL_H / 2.0F, PILL_ICON * bump);
        left += PILL_ICON + PILL_GAP;
        countFont.drawString(text, left + PILL_COUNT_W - countFont.getStringWidth(text), inLine(countFont, y, PILL_H), number);
        left += PILL_COUNT_W + PILL_GAP;
        float barY = y + (PILL_H - BAR_H) / 2.0F;
        GlassShader.rect(left, barY, BAR_W, BAR_H, BAR_H / 2.0F, BAR_TRACK, BAR_TRACK);
        glow(left, barY, BAR_W * fill, BAR_H, BAR_H / 2.0F, Theme.withAlpha(ramp, 128), BAR_GLOW);
        GlassShader.rect(left, barY, BAR_W * fill, BAR_H, BAR_H / 2.0F, accent, accent);
        left += BAR_W + PILL_GAP;
        totalFont.drawString(totalText, left, inLine(totalFont, y, PILL_H), TOTAL_DIM);
    }

    /** A chip above the crosshair with one bar per block stack in the hotbar, then the total. */
    private void drawStacks(EntityPlayerSP player, int total) {
        int segments = 0;
        for (int i = 0; i < 9; i++) {
            if (isBlocks(player.inventory.mainInventory[i])) {
                segments++;
            }
        }
        CustomFont font = STACKS_FONT.get();
        String text = String.valueOf(total);
        float segsW = segments * SEG_W + (segments - 1) * SEG_GAP;
        float w = STACKS_PAD_L + segsW + STACKS_GAP + font.getStringWidth(text) + STACKS_PAD_R;
        float x = cx - w / 2.0F;
        float y = cy - STACKS_TOP;
        GlassShader.capture();
        GlassShader.frost(x, y, w, STACKS_H, STACKS_H / 2.0F, GLASS);

        boolean low = count <= LOW;
        float segX = x + STACKS_PAD_L;
        float segY = y + (STACKS_H - SEG_H) / 2.0F;
        float o = SEG_OUTLINE_W / 2.0F;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = player.inventory.mainInventory[i];
            if (!isBlocks(stack)) {
                continue;
            }
            float f = Math.min(stack.stackSize, STACK) / (float) STACK;
            if (i == player.inventory.currentItem) {
                // the held stack is outlined and bright, and fills in again after a switch
                f *= ease(sw / FILL_IN);
                GlassShader.arc(segX - o, segY - o, SEG_W + 2.0F * o, SEG_H + 2.0F * o, SEG_R + o, SEG_OUTLINE_W,
                        0.0F, 1.0F, SEG_OUTLINE);
                GlassShader.rect(segX, segY, SEG_W, SEG_H, SEG_R, SEG_TRACK, SEG_TRACK);
                glow(segX, segY, SEG_W * f, SEG_H, SEG_R, low ? Theme.withAlpha(ramp, 128) : SEG_SHINE, SEG_GLOW);
                int color = low ? accent : SEG_ACTIVE;
                GlassShader.rect(segX, segY, SEG_W * f, SEG_H, SEG_R, color, color);
            } else {
                GlassShader.rect(segX, segY, SEG_W, SEG_H, SEG_R, SEG_TRACK, SEG_TRACK);
                GlassShader.rect(segX, segY, SEG_W * f, SEG_H, SEG_R, SEG_IDLE, SEG_IDLE);
            }
            segX += SEG_W + SEG_GAP;
        }
        font.drawString(text, x + STACKS_PAD_L + segsW + STACKS_GAP, inLine(font, y, STACKS_H), number);
    }

    /** Just the number right of the crosshair; it rolls on each change and a -1 or +N floats off. */
    private void drawTicker(int scaleFactor) {
        CustomFont font = TICKER_FONT.get();
        String text = String.valueOf(count);
        float left = cx + TICKER_LEFT;
        float top = inLine(font, cy + TICKER_TOP, TICKER_LINE);
        // the old number rolls up and out while the new one rolls in from below
        float k = ease(sw < ROLL ? sw / ROLL : since / ROLL);
        RenderUtil.beginScissor(left, cy + TICKER_TOP, TICKER_BOX_W, TICKER_LINE, scaleFactor);
        shadowed(font, String.valueOf(prevCount), left, top - k * TICKER_RISE, Theme.applyAlpha(TEXT, 1.0F - k));
        shadowed(font, text, left, top + (1.0F - k) * TICKER_RISE, Theme.applyAlpha(count <= LOW ? ramp : WHITE, k));
        RenderUtil.endScissor();

        CustomFont wordFont = WORD_FONT.get();
        shadowed(wordFont, word(), cx + TICKER_WORD_LEFT, inLine(wordFont, cy + TICKER_WORD_TOP, WORD_LINE), TICKER_WORD);

        boolean refill = sw < FLOAT;
        float age = Math.min(1.0F, (refill ? sw : since) / FLOAT);
        CustomFont small = SMALL_FONT.get();
        shadowed(small, refill ? "+" + count : "-1", left + font.getStringWidth(text) + FLOAT_GAP,
                inLine(small, cy + FLOAT_TOP, FLOAT_LINE) - age * FLOAT_RISE,
                Theme.applyAlpha(refill ? REFILL : DROP, 1.0F - age));
    }

    private String word() {
        return count == 1 ? "block" : "blocks";
    }

    private static boolean isBlocks(ItemStack stack) {
        return stack != null && stack.getItem() instanceof ItemBlock;
    }

    /** Ease-out cubic over 0..1. */
    private static float ease(float k) {
        float c = 1.0F - Math.max(0.0F, Math.min(1.0F, k));
        return 1.0F - c * c * c;
    }

    /** Glyph top for text centered in a line box like the design's. */
    private static float inLine(CustomFont font, float top, float line) {
        return top + (line - font.getHeight()) / 2.0F;
    }

    /** The block's own GUI icon, {@code size} px square around (x, y). */
    private static void icon(ItemStack stack, float x, float y, float size) {
        RenderUtil.beginItems();
        GlStateManager.pushMatrix();
        GlStateManager.translate(x - size / 2.0F, y - size / 2.0F, 0.0F);
        GlStateManager.scale(size / 16.0F, size / 16.0F, 1.0F);
        Minecraft.getMinecraft().getRenderItem().renderItemAndEffectIntoGUI(stack, 0, 0);
        GlStateManager.popMatrix();
        RenderUtil.endItems();
    }

    /** Stacked rounded rects standing in for a css box-shadow glow blurred over about {@code spread} px. */
    private static void glow(float x, float y, float w, float h, float r, int color, float spread) {
        float alpha = (color >>> 24) / 255.0F;
        for (int i = 0; i < GLOW_A.length; i++) {
            float g = spread * (GLOW_A.length - i) / GLOW_A.length;
            int c = Theme.withAlpha(color, Math.round(255.0F * alpha * GLOW_A[i]));
            GlassShader.rect(x - g, y - g, w + 2.0F * g, h + 2.0F * g, r + g, c, c);
        }
    }

    /** Text with a soft dark shadow: a faint copy a little lower, then the text over a thin halo. */
    private static void shadowed(CustomFont font, String text, float x, float y, int color) {
        int alpha = color >>> 24;
        // CustomFont draws alpha under 4 opaque, and the shadow copy carries a third of this
        if (alpha < 12) {
            return;
        }
        font.drawString(text, x, y + SHADOW_DROP, Theme.withAlpha(0, alpha * 90 / 255));
        font.drawStringWithOutline(text, x, y, color, Theme.withAlpha(0, alpha * 128 / 255), SHADOW_HALO);
    }
}
