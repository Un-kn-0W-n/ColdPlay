package coldplay.module.visual;

import coldplay.event.EventRender2D;
import coldplay.event.EventTarget;
import coldplay.gui.Glass;
import coldplay.gui.GlassShader;
import coldplay.gui.Theme;
import coldplay.hud.HudState;
import coldplay.module.Category;
import coldplay.module.Module;
import coldplay.setting.NumberSetting;
import coldplay.util.Animation;
import coldplay.util.RenderUtil;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.MathHelper;

/** One glass tile per armor piece; the ring around each tile is its durability. */
public class ArmorStatus extends Module {

    // Layout dimensions use scaled GUI pixels.
    private static final int TILE = 22;
    private static final int GAP = 9;
    private static final int ICON_INSET = 3;
    private static final int WIDTH = 4 * TILE + 3 * GAP;
    private static final float RADIUS = 6.0F;
    private static final float RING_GAP = 2.25F;
    private static final float RING_W = 1.5F;
    private static final float EMPTY_INSET = 4.0F; // dashed outline of an empty slot
    private static final float EMPTY_RADIUS = 3.0F;
    private static final float DASH_W = 0.75F;
    private static final int DASHES = 12;
    private static final float LOW = 0.15F; // durability under this pulses

    private static final int TRACK = 0x29FFFFFF;
    private static final int TRAIL = 0x8CFFFFFF;
    private static final int DASH = 0x3DFFFFFF;
    private static final Glass TILE_GLASS = new Glass(0x800E1015, 0x800E1015, 0x2EFFFFFF, 10.5F, 1.4F, 16.5F, 6.0F, 0.26F);

    private final NumberSetting scale = add(HudState.scaleSetting("Scale"));
    private final HudState hud;
    private final Slot[] slots = {new Slot(), new Slot(), new Slot(), new Slot()};

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
                resolution.getScaledHeight() - 55 - TILE,
                resolution.getScaledWidth(), resolution.getScaledHeight());
        float s = scale.get().floatValue();
        RenderUtil.pushScale(state.x, state.y, s);

        GlassShader.capture();
        for (int i = 0; i < 4; i++) {
            GlassShader.frost(tileX(state.x, i), state.y, TILE, TILE, RADIUS, TILE_GLASS);
        }

        float pulse = 0.55F + 0.45F * (float) Math.cos(System.currentTimeMillis() / 1000.0 * Math.PI * 4.0);
        float o = RING_GAP + RING_W / 2.0F;
        float ring = TILE + 2.0F * o;
        // armorInventory is boots-first, so walk 3..0 for helmet..boots
        for (int i = 0; i < 4; i++) {
            ItemStack piece = player.getCurrentArmor(3 - i);
            Slot slot = slots[i];
            int x = tileX(state.x, i);
            GlassShader.arc(x - o, state.y - o, ring, ring, RADIUS + o, RING_W, 0.0F, 1.0F, TRACK);
            if (piece == null) {
                slot.item = null;
                dashes(x + EMPTY_INSET, state.y + EMPTY_INSET, TILE - 2.0F * EMPTY_INSET);
                continue;
            }
            if (!piece.isItemStackDamageable()) {
                slot.item = piece.getItem();
                continue;
            }
            float fraction = MathHelper.clamp_float(
                    1.0F - (float) piece.getItemDamage() / Math.max(1, piece.getMaxDamage()), 0.0F, 1.0F);
            if (piece.getItem() != slot.item) {
                slot.item = piece.getItem();
                slot.bar.set(fraction);
                slot.trail.set(fraction);
            }
            float bar = (float) slot.bar.update(fraction);
            float trail = (float) slot.trail.update(fraction);
            int color = Theme.healthColor(bar);
            if (bar < LOW) {
                color = Theme.applyAlpha(color, pulse);
            }
            GlassShader.arc(x - o, state.y - o, ring, ring, RADIUS + o, RING_W, bar, trail, TRAIL);
            GlassShader.arc(x - o, state.y - o, ring, ring, RADIUS + o, RING_W, 0.0F, bar, color);
        }

        RenderUtil.beginItems();
        for (int i = 0; i < 4; i++) {
            ItemStack piece = player.getCurrentArmor(3 - i);
            if (piece != null) {
                RenderUtil.drawItemRaw(piece, tileX(state.x, i) + ICON_INSET, state.y + ICON_INSET, 1.0F);
            }
        }
        RenderUtil.endItems();
        GlStateManager.popMatrix();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        GlStateManager.enableBlend();
        if (editing) {
            hud.report("ArmorStatus", state.x, state.y, state.x + WIDTH, state.y + TILE, state.x, state.y, s);
        }
    }

    private static int tileX(int left, int i) {
        return left + i * (TILE + GAP);
    }

    private static void dashes(float x, float y, float size) {
        for (int i = 0; i < DASHES; i++) {
            float from = (float) i / DASHES;
            GlassShader.arc(x, y, size, size, EMPTY_RADIUS, DASH_W, from, from + 0.5F / DASHES, DASH);
        }
    }

    private static final class Slot {
        // the trail lags behind the ring to show the durability just lost
        final Animation bar = new Animation(1.0, 14.0);
        final Animation trail = new Animation(1.0, 3.0);
        Item item;
    }
}
