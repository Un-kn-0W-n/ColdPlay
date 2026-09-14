package coldplay.module.visual;

import coldplay.broker.BotTracker;
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

/** Shows the entity last attacked, else the one under the crosshair, and holds it for Hold Time after it is lost. */
public class TargetHUD extends Module {
    private final HudState hud;

    private static final int COLOR_BOX = 0xF0101014;
    private static final int COLOR_BORDER = 0xFFB9B9C2;
    private static final int PAD = 4;      // panel inner padding
    private static final int GAP = 2;      // vertical gap between rows in the info column
    private static final int TEXT_PAD = 6; // min gap between the name and the health number

    // drawEntityOnScreen draws a player about scale * 1.8 px tall, so 18 fits inside DOLL_H
    private static final int DOLL_W = 28;
    private static final int DOLL_H = 36;
    private static final int DOLL_SCALE = 18;

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

    // Filters apply to the crosshair source only; an attacked entity always shows.
    private final HeaderSetting targetsHeader = add(new HeaderSetting("Targets"));
    private final BooleanSetting players = add(new BooleanSetting("Players", true).describe("Show when looking at players."));
    private final BooleanSetting mobs = add(new BooleanSetting("Mobs", true).describe("Show when looking at hostile mobs."));
    private final BooleanSetting animals = add(new BooleanSetting("Animals", false).describe("Show when looking at passive animals."));

    private final HeaderSetting placementHeader = add(new HeaderSetting("Placement"));
    private final NumberSetting scale = add(HudState.scaleSetting("Scale"));
    private final NumberSetting holdTime = add(new NumberSetting("Hold Time", 1.5, 0.0, 5.0, 0.25).describe("Seconds to keep the panel after the target is lost."));

    private EntityLivingBase display;
    private long lastSeenAt;
    private EntityLivingBase combatTarget;
    private long combatTargetAt;

    public TargetHUD(HudState hud) {
        super("TargetHUD", Category.VISUAL,
                "Panel under the crosshair showing your combat target: model, real health, armor.");
        this.hud = hud;
        hud.registerScale("TargetHUD", scale);
    }

    @Override
    protected void onDisable() {
        display = null;
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

        // Resolve before the font check so the hold window keeps ticking while fonts load.
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
            // preview the player so the panel is draggable in the editor
            shown = player;
        }

        Fonts.load(event.getResolution().getScaleFactor()); // lazy init, needs a live GL context
        if (!Fonts.isLoaded()) {
            return;
        }
        CustomFont font = Fonts.list;

        // Scoreboard health can exceed max health, so clamp the bar but show the raw value.
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
                armor[slot] = shown.getCurrentArmor(slot); // boots..helmet
                if (armor[slot] != null) {
                    icons++;
                    hasArmor = true;
                }
            }
        }

        // Unscaled units; Scale is a matrix pinned at the top-center anchor.
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
        // default sits 10px below the crosshair sprite
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
            hud.report("TargetHUD", left, top, right, bottom, anchorX, top, s);
        }
        RenderUtil.pushScale(anchorX, top, s);

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

        // held item first, then armor boots..helmet
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

        // Drawn last so its depth writes cannot occlude the flat panel.
        if (showModel.get()) {
            // shrink tall mobs to fit the column
            int dollScale = (int) (DOLL_SCALE * Math.min(1.0F, 1.8F / Math.max(0.5F, shown.height)));
            GlStateManager.enableDepth(); // limb self-occlusion needs the depth test
            GuiInventory.drawEntityOnScreen(left + PAD + DOLL_W / 2, bottom - PAD - 1, // posY = feet
                    dollScale, 0.0F, 0.0F, shown);
            // drawEntityOnScreen leaves colorMaterial enabled
            GlStateManager.disableColorMaterial();
            GlStateManager.disableDepth();
        }

        GlStateManager.popMatrix();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        GlStateManager.enableBlend();
    }

    /** Last attacked entity first, then whatever the crosshair points at. */
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

    private boolean stillValid(EntityLivingBase entity, EntityPlayerSP player) {
        return entity != player
                && entity.isEntityAlive()
                && entity.getHealth() > 0.0F
                && entity.worldObj == Minecraft.getMinecraft().theWorld
                && !BotTracker.getInstance().isBot(entity);
    }

    private boolean matchesFilters(Entity entity) {
        switch (EntityTargets.classify(entity)) {
            case PLAYER:
                return players.get();
            case NPC:
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
