package coldplay.module.visual;

import coldplay.event.EventRender2D;
import coldplay.event.EventTarget;
import coldplay.gui.Glass;
import coldplay.gui.GlassShader;
import coldplay.module.Category;
import coldplay.module.Module;
import coldplay.util.font.CustomFont;
import coldplay.util.font.FontRef;
import coldplay.util.font.Fonts;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;

public class BlockCounter extends Module {

    private static final int GAP = 3;          // px between the crosshair box top and the chip
    private static final float H = 19.5F;
    private static final float PAD = 9.0F;
    private static final float WORD_GAP = 3.75F;
    private static final float RADIUS = 5.25F;
    private static final int COLOR_WORD = 0x8CFFFFFF;

    private static final FontRef COUNT = new FontRef(Fonts.GEIST_MONO_MEDIUM, 9.375F);
    private static final FontRef WORD = new FontRef(Fonts.GEIST, 8.625F);

    public BlockCounter() {
        super("BlockCounter", Category.VISUAL, "Remaining hotbar blocks above the crosshair.");
    }

    @EventTarget
    public void onRender2D(EventRender2D event) {
        EntityPlayerSP player = Minecraft.getMinecraft().thePlayer;
        if (player == null) {
            return;
        }
        ItemStack held = player.getHeldItem();
        if (held == null || !(held.getItem() instanceof ItemBlock)) {
            return;
        }

        Fonts.load(event.getResolution().getScaleFactor()); // lazy init, needs a live GL context
        if (!Fonts.isLoaded()) {
            return;
        }
        CustomFont countFont = COUNT.get();
        CustomFont wordFont = WORD.get();

        String count = String.valueOf(held.stackSize);
        String word = held.stackSize == 1 ? "block" : "blocks";
        ScaledResolution resolution = event.getResolution();
        int centerX = resolution.getScaledWidth() / 2;
        int centerY = resolution.getScaledHeight() / 2;

        // vanilla draws the 16x16 crosshair at (-7,-7) from center
        float countW = countFont.getStringWidth(count);
        float chipW = PAD + countW + WORD_GAP + wordFont.getStringWidth(word) + PAD;
        float chipX = centerX - chipW / 2.0F;
        float chipY = centerY - 7 - GAP - H;
        GlassShader.panel(chipX, chipY, chipW, H, RADIUS, Glass.SMOKE);
        float countTop = chipY + (H - countFont.getHeight()) / 2.0F;
        countFont.drawString(count, chipX + PAD, countTop, 0xFFFFFFFF);
        wordFont.drawString(word, chipX + PAD + countW + WORD_GAP,
                countTop + countFont.getAscent() - wordFont.getAscent(), COLOR_WORD);
    }
}
