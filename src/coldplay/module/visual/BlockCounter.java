package coldplay.module.visual;

import coldplay.event.EventRender2D;
import coldplay.event.EventTarget;
import coldplay.module.Category;
import coldplay.module.Module;
import coldplay.util.RenderUtil;
import coldplay.util.font.CustomFont;
import coldplay.util.font.Fonts;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;

public class BlockCounter extends Module {

    private static final int GAP = 3;       // px between the crosshair box top and the icon
    private static final int ICON = 16;     // vanilla GUI item icon size
    private static final int TEXT_PAD = 2;  // px between the icon and the count text

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

        Fonts.load(event.getResolution().getScaleFactor()); // lazy, first-frame init (GL context guaranteed here)
        if (!Fonts.isLoaded()) {
            return;
        }
        CustomFont font = Fonts.list;

        String text = String.valueOf(held.stackSize);
        ScaledResolution resolution = event.getResolution();
        int centerX = resolution.getScaledWidth() / 2;
        int centerY = resolution.getScaledHeight() / 2;

        // Vanilla draws its 16x16 crosshair sprite at (-7,-7) from center; sit the group above it.
        int groupWidth = ICON + TEXT_PAD + font.getStringWidth(text);
        int iconX = centerX - groupWidth / 2;
        int iconY = centerY - 7 - GAP - ICON;
        RenderUtil.drawItem(held, iconX, iconY);
        font.drawStringWithShadow(text, iconX + ICON + TEXT_PAD, iconY + (ICON - font.getHeight()) / 2f, 0xFFFFFFFF);
    }
}
