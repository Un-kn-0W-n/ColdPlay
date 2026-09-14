package coldplay.module.visual;

import coldplay.event.EventRender2D;
import coldplay.event.EventTarget;
import coldplay.hud.HudState;
import coldplay.module.Category;
import coldplay.module.Module;
import coldplay.setting.NumberSetting;
import coldplay.util.RenderUtil;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.item.ItemStack;
import net.minecraft.util.MathHelper;

public class ArmorStatus extends Module {

    private static final int COLOR_WELL = 0xFF16181D;

    // Layout dimensions use scaled GUI pixels.
    private static final int ICON = 16;
    private static final int SLOT_GAP = 2;
    private static final int BAR_H = 2;
    private static final int BAR_GAP = 1; // icon bottom to bar top
    private static final int WIDTH = 4 * ICON + 3 * SLOT_GAP;
    private static final int HEIGHT = ICON + BAR_GAP + BAR_H;

    private final NumberSetting scale = add(HudState.scaleSetting("Scale"));
    private final HudState hud;

    public ArmorStatus(HudState hud) {
        super("ArmorStatus", Category.VISUAL, "Shows your equipped armor and its durability.");
        this.hud = hud;
        hud.registerScale("ArmorStatus", scale);
    }

    @EventTarget
    public void onRender2D(EventRender2D event) {
        EntityPlayerSP player = Minecraft.getMinecraft().thePlayer;
        if (player == null) {
            return;
        }
        boolean editing = hud.isEditing();
        boolean anyWorn = false;
        for (int slot = 0; slot < 4; slot++) {
            if (player.getCurrentArmor(slot) != null) {
                anyWorn = true;
            }
        }
        if (!anyWorn && !editing) {
            return;
        }

        ScaledResolution resolution = event.getResolution();
        // default sits above the vanilla hotbar and status bars
        HudState.Position state = hud.getOrCreate(
                "ArmorStatus", resolution.getScaledWidth() / 2 - WIDTH / 2,
                resolution.getScaledHeight() - 55 - HEIGHT,
                resolution.getScaledWidth(), resolution.getScaledHeight());
        float s = scale.get().floatValue();
        RenderUtil.pushScale(state.x, state.y, s);

        // armorInventory is boots-first, so walk 3..0 for helmet..boots
        int x = state.x;
        for (int slot = 3; slot >= 0; slot--) {
            ItemStack piece = player.getCurrentArmor(slot);
            if (piece != null) {
                RenderUtil.drawItem(piece, x, state.y);
                if (piece.isItemStackDamageable()) {
                    float fraction = MathHelper.clamp_float(
                            1.0F - (float)piece.getItemDamage() / Math.max(1, piece.getMaxDamage()),
                            0.0F, 1.0F);
                    int barY = state.y + ICON + BAR_GAP;
                    RenderUtil.rect(x, barY, ICON, BAR_H, COLOR_WELL);
                    RenderUtil.rect(x, barY, Math.round(ICON * fraction), BAR_H,
                            RenderUtil.lerpRedGreen(fraction));
                }
            } else if (editing) {
                RenderUtil.rect(x, state.y, ICON, ICON, COLOR_WELL);
            }
            x += ICON + SLOT_GAP;
        }
        GlStateManager.popMatrix();
        if (editing) {
            hud.report("ArmorStatus", state.x, state.y, state.x + WIDTH, state.y + HEIGHT, state.x, state.y, s);
        }
    }
}
