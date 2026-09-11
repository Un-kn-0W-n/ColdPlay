package coldplay.module.visual;

import coldplay.event.EventAttackPerformed;
import coldplay.event.EventHurt;
import coldplay.event.EventRender2D;
import coldplay.event.EventTarget;
import coldplay.event.EventUpdate;
import coldplay.hud.HudState;
import coldplay.module.Category;
import coldplay.module.Module;
import coldplay.setting.BooleanSetting;
import coldplay.setting.HeaderSetting;
import coldplay.setting.NumberSetting;
import coldplay.friend.FriendManager;
import coldplay.util.EntityTargets;
import coldplay.util.HealthResolver;
import coldplay.util.RenderUtil;
import coldplay.util.font.CustomFont;
import coldplay.util.font.Fonts;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.gui.inventory.GuiInventory;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.MathHelper;

/**
 * Completed attacks take priority over the real crosshair, independently of silent rotations.
 * The last valid target lingers for Hold Time; editing previews the player when no target is live.
 */
public class TargetHUD extends Module {
    private final HudState hud;

    private static final int COLOR_BOX = 0xF0101014;
    private static final int COLOR_BORDER = 0xFFB9B9C2;
    private static final int PAD = 4;      // panel inner padding
    private static final int GAP = 2;      // vertical gap between rows in the info column
    private static final int TEXT_PAD = 6; // min gap between the name and the health number

    // Doll column: drawEntityOnScreen renders ~1.8 blocks of player at scale*~1.8 px tall, so
    // scale 18 ≈ 32px — comfortably inside DOLL_H with headroom. Taller mobs shrink to fit.
    private static final int DOLL_W = 28;
    private static final int DOLL_H = 36;
    private static final int DOLL_SCALE = 18;

    // Full-size equipment icons mirror ArmorStatus so durability remains legible at every GUI scale.
    private static final int COLOR_WELL = 0xFF16181D;
    private static final int ICON = 16;
    private static final int ICON_GAP = 2;
    private static final int DURABILITY_GAP = 1;
    private static final int DURABILITY_H = 2;
    private static final int BAR_BORDER = 2;
    private static final int BAR_INNER_H = 3;
    private static final int MIN_BAR_W = 60;

    private final BooleanSetting showModel = add(new BooleanSetting("Show Model", true).describe("Render the target's 3D model in the panel."));
    private final BooleanSetting healthText = add(new BooleanSetting("Health Text", true).describe("Numeric health next to the name."));
    private final BooleanSetting showIcons = add(new BooleanSetting("Held + Armor", true).describe("Row of the target's held item and worn armor."));

    // Filters gate only the look-at source; an entity actually attacked always shows.
    private final HeaderSetting targetsHeader = add(new HeaderSetting("Targets"));
    private final BooleanSetting players = add(new BooleanSetting("Players", true).describe("Show when looking at players."));
    private final BooleanSetting mobs = add(new BooleanSetting("Mobs", true).describe("Show when looking at hostile mobs."));
    private final BooleanSetting animals = add(new BooleanSetting("Animals", false).describe("Show when looking at passive animals."));

    private final HeaderSetting placementHeader = add(new HeaderSetting("Placement"));
    private final NumberSetting scale = add(new NumberSetting("Scale", 1.0, 0.5, 2.0, 0.1).describe("Panel size multiplier."));
    private final NumberSetting holdTime = add(new NumberSetting("Hold Time", 1.5, 0.0, 5.0, 0.25).describe("Seconds to keep the panel after the target is lost."));

    /** What the panel is showing (may be lingering) + when a live source last confirmed it. */
    private EntityLivingBase display;
    private long lastSeenAt;
    private EntityLivingBase combatTarget;
    private long combatTargetAt;

    public TargetHUD(HudState hud) {
        super("TargetHUD", Category.VISUAL,
                "Panel under the crosshair showing your combat target: model, real health, armor.");
        this.hud = hud;
    }

    @Override
    protected void onDisable() {
        display = null; // never flash a stale entity on re-enable
        lastSeenAt = 0L;
        combatTarget = null;
        combatTargetAt = 0L;
    }

    @EventTarget
    public void onAttack(EventAttackPerformed event) {
        if (event.getTarget() instanceof EntityLivingBase) {
            combatTarget = (EntityLivingBase) event.getTarget();
            combatTargetAt = System.currentTimeMillis();
        }
    }

    @EventTarget
    public void onUpdate(EventUpdate event) {
        if (event.isPre() && (Minecraft.getMinecraft().thePlayer == null
                || Minecraft.getMinecraft().theWorld == null)) {
            display = null;
            lastSeenAt = 0L;
            combatTarget = null;
            combatTargetAt = 0L;
        }
    }

    /** Still being hit = still in combat: refresh the linger window (idempotent per EventHurt's contract). */
    @EventTarget
    public void onHurt(EventHurt event) {
        if (display != null) {
            lastSeenAt = System.currentTimeMillis();
            if (display == combatTarget) {
                combatTargetAt = lastSeenAt;
            }
        }
    }

    @EventTarget
    public void onRender2D(EventRender2D event) {
        EntityPlayerSP player = Minecraft.getMinecraft().thePlayer;
        if (player == null || Minecraft.getMinecraft().theWorld == null) {
            display = null;
            lastSeenAt = 0L;
            combatTarget = null;
            combatTargetAt = 0L;
            return;
        }

        // Resolve first, draw second — the linger bookkeeping must run even on frames we can't draw
        // (fonts not baked yet), so the hold window doesn't stretch by however long loading took.
        long now = System.currentTimeMillis();
        EntityLivingBase live = resolveLive(player, now);
        if (live != null) {
            display = live;
            lastSeenAt = now;
        } else if (display != null
                && (now - lastSeenAt > (long) (holdTime.get() * 1000.0) || !stillValid(display, player))) {
            display = null;
        }
        EntityLivingBase shown = display;
        if (shown == null) {
            if (!hud.isEditing()) {
                return;
            }
            // Edit GUI preview: the panel must render to be draggable, so show the player themself.
            shown = player;
        }

        Fonts.load(event.getResolution().getScaleFactor()); // lazy, first-frame init (GL context guaranteed here)
        if (!Fonts.isLoaded()) {
            return;
        }
        CustomFont font = Fonts.list;

        // Scoreboard health can exceed max health: clamp the bar but show the raw value.
        float health = HealthResolver.resolve(shown);
        float fraction = MathHelper.clamp_float(
                health / Math.max(1.0F, shown.getMaxHealth()), 0.0F, 1.0F);
        String name = shown.getName();
        String hp = healthText.get() ? String.format("%.1f", health) : null;
        int friendColor = FriendManager.getInstance().getColor(name);

        ItemStack held = showIcons.get() ? shown.getHeldItem() : null;
        ItemStack[] armor = new ItemStack[4];
        int icons = held != null ? 1 : 0;
        boolean hasArmor = false;
        if (showIcons.get()) {
            for (int slot = 0; slot < 4; slot++) {
                armor[slot] = shown.getCurrentArmor(slot); // boots..helmet, NameTags row order
                if (armor[slot] != null) {
                    icons++;
                    hasArmor = true;
                }
            }
        }

        // Geometry (unscaled units — Scale is applied as a matrix pinned to the top-center anchor,
        // so the panel grows downward/outward and stays centered on its pin).
        int rowW = icons > 0 ? icons * ICON + (icons - 1) * ICON_GAP : 0;
        int textW = font.getStringWidth(name) + (hp != null ? TEXT_PAD + font.getStringWidth(hp) : 0);
        int colW = Math.max(MIN_BAR_W, Math.max(textW, rowW));
        int dollW = showModel.get() ? DOLL_W + PAD : 0;
        int panelW = PAD + dollW + colW + PAD;
        int barH = BAR_INNER_H + BAR_BORDER * 2;
        int equipmentH = ICON + (hasArmor ? DURABILITY_GAP + DURABILITY_H : 0);
        int colH = font.getHeight() + GAP + barH + (icons > 0 ? GAP + equipmentH : 0);
        int panelH = PAD + Math.max(colH, showModel.get() ? DOLL_H : 0) + PAD;

        ScaledResolution resolution = event.getResolution();
        // Pin scaling at the panel's top-center. The default leaves 10px below the crosshair sprite.
        HudState.Position pin = hud.getOrCreate(
                "TargetHUD", resolution.getScaledWidth() / 2, resolution.getScaledHeight() / 2 + 17,
                resolution.getScaledWidth(), resolution.getScaledHeight());
        int anchorX = pin.x;
        int top = pin.y;
        int left = anchorX - panelW / 2;
        int right = left + panelW;
        int bottom = top + panelH;

        float s = scale.get().floatValue();
        if (hud.isEditing()) {
            // Box in screen coords: the scale matrix is pinned at (anchorX, top), so edges scale
            // around that point.
            hud.report("TargetHUD",
                    Math.round(anchorX + (left - anchorX) * s), top,
                    Math.round(anchorX + (right - anchorX) * s), Math.round(top + panelH * s));
        }
        GlStateManager.pushMatrix();
        GlStateManager.translate(anchorX, top, 0.0F);
        GlStateManager.scale(s, s, 1.0F);
        GlStateManager.translate(-anchorX, -top, 0.0F);

        RenderUtil.drawBorderedRect(left, top, right, bottom, COLOR_BOX, COLOR_BORDER);

        int colX = left + PAD + dollW;
        int textY = top + PAD;
        font.drawStringWithShadow(name, colX, textY, friendColor != 0 ? friendColor : 0xFFFFFFFF);
        if (hp != null) {
            font.drawStringWithShadow(hp, colX + colW - font.getStringWidth(hp), textY,
                    RenderUtil.lerpRedGreen(fraction));
        }

        int barTop = textY + font.getHeight() + GAP;
        int barBottom = barTop + barH;
        RenderUtil.outline(colX, barTop, colX + colW, barBottom, BAR_BORDER,
                friendColor != 0 ? friendColor : 0xFF000000);
        int fillWidth = Math.round((colW - BAR_BORDER * 2) * fraction);
        RenderUtil.rectBounds(colX + BAR_BORDER, barTop + BAR_BORDER,
                colX + BAR_BORDER + fillWidth, barBottom - BAR_BORDER,
                RenderUtil.lerpRedGreen(fraction));

        // Row: held item, then worn armor (boots..helmet), left to right. drawItem restores its own
        // GL state per call (lighting, depth off, white color), so the calls interleave freely.
        if (icons > 0) {
            int iconY = barBottom + GAP;
            int cursorX = colX;
            if (held != null) {
                RenderUtil.drawItem(held, cursorX, iconY);
                cursorX += ICON + ICON_GAP;
            }
            for (ItemStack piece : armor) {
                if (piece == null) {
                    continue;
                }
                RenderUtil.drawItem(piece, cursorX, iconY);
                if (piece.isItemStackDamageable()) {
                    float durability = MathHelper.clamp_float(
                            1.0F - (float) piece.getItemDamage() / Math.max(1, piece.getMaxDamage()),
                            0.0F, 1.0F);
                    int durabilityY = iconY + ICON + DURABILITY_GAP;
                    RenderUtil.rect(cursorX, durabilityY, ICON, DURABILITY_H, COLOR_WELL);
                    RenderUtil.rect(cursorX, durabilityY, Math.round(ICON * durability), DURABILITY_H,
                            RenderUtil.lerpRedGreen(durability));
                }
                cursorX += ICON + ICON_GAP;
            }
        }

        // Draw the model last so its depth writes cannot occlude the flat panel; fixed mouse
        // coordinates keep its pose steady. The helper restores entity rotations after drawing.
        if (showModel.get()) {
            // Height-normalize so tall mobs (enderman: 2.9bl) still fit the column.
            int dollScale = (int) (DOLL_SCALE * Math.min(1.0F, 1.8F / Math.max(0.5F, shown.height)));
            GlStateManager.enableDepth(); // limb self-occlusion needs the depth test
            GuiInventory.drawEntityOnScreen(left + PAD + DOLL_W / 2, bottom - PAD - 1, // posY = feet
                    dollScale, 0.0F, 0.0F, shown);
            // drawEntityOnScreen enables colorMaterial and never disables it, and can leave a tint.
            GlStateManager.disableColorMaterial();
            GlStateManager.disableDepth();
        }

        GlStateManager.popMatrix();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        GlStateManager.enableBlend();
    }

    /** The live target this frame: last completed attack first, then the real-crosshair entity. */
    private EntityLivingBase resolveLive(EntityPlayerSP player, long now) {
        if (combatTarget != null) {
            if (stillValid(combatTarget, player)
                    && now - combatTargetAt <= (long) (holdTime.get() * 1000.0)) {
                return combatTarget;
            }
            combatTarget = null;
            combatTargetAt = 0L;
        }
        Entity pointed = Minecraft.getMinecraft().pointedEntity;
        if (pointed instanceof EntityLivingBase && stillValid((EntityLivingBase) pointed, player)
                && matchesFilters(pointed)) {
            return (EntityLivingBase) pointed;
        }
        return null;
    }

    /** Revalidate lingering targets so dead entities and departed worlds cannot remain displayed. */
    private boolean stillValid(EntityLivingBase entity, EntityPlayerSP player) {
        return entity != player
                && entity.isEntityAlive()
                && entity.getHealth() > 0.0F
                && entity.worldObj == Minecraft.getMinecraft().theWorld;
    }

    /** Same type bucketing as EntityESP.colorFor: unmatched leftovers (villagers, armor stands) are skipped. */
    private boolean matchesFilters(Entity entity) {
        switch (EntityTargets.classify(entity)) {
            case PLAYER:
                return players.get();
            case NPC:
                // Player-shaped NPCs follow the Players setting.
                return entity instanceof EntityPlayer && players.get();
            case MOB:
                return mobs.get();
            case ANIMAL:
                return animals.get();
            default:
                return false;
        }
    }
}
