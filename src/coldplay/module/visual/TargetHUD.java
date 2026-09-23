package coldplay.module.visual;

import coldplay.broker.BotTracker;
import coldplay.event.EventAttackPerformed;
import coldplay.event.EventHurt;
import coldplay.event.EventRender2D;
import coldplay.event.EventTarget;
import coldplay.event.EventUpdate;
import coldplay.gui.Glass;
import coldplay.gui.GlassShader;
import coldplay.hud.HudState;
import coldplay.module.Category;
import coldplay.module.Module;
import coldplay.setting.BooleanSetting;
import coldplay.setting.HeaderSetting;
import coldplay.setting.NumberSetting;
import coldplay.friend.FriendManager;
import coldplay.util.Animation;
import coldplay.util.EntityTargets;
import coldplay.util.HealthResolver;
import coldplay.util.RenderUtil;
import coldplay.util.font.CustomFont;
import coldplay.util.font.FontRef;
import coldplay.util.font.Fonts;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.gui.inventory.GuiInventory;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ColorMath;
import net.minecraft.util.MathHelper;

import java.awt.Color;

/** Shows the entity last attacked, else the one under the crosshair, and holds it for Hold Time after it is lost. */
public class TargetHUD extends Module {
    private final HudState hud;

    private static final float PAD = 7.5F;
    private static final int HEAD = 30;
    private static final float HEAD_GAP = 7.5F;
    private static final float BAR_H = 3.0F;
    private static final float BAR_GAP = 6.0F; // between the text row and the bar
    private static final int MIN_COL_W = 121;  // keeps the card at least 174 wide
    private static final int TEXT_PAD = 8;     // min gap between the name and the health number
    private static final float RADIUS = 7.5F;
    private static final float HEAD_RADIUS = 4.5F;

    private static final int WELL = 0x40000000;
    private static final int TRACK = 0x24FFFFFF;
    private static final int TRAIL = 0x59FFFFFF;
    private static final int TEXT = 0xFFFFFFFF;
    private static final int HURT = 0xFFFF5A5A;
    private static final FontRef NAME_FONT = new FontRef(Fonts.GEIST_SEMIBOLD, 9.75F);
    private static final FontRef HP_FONT = new FontRef(Fonts.GEIST_MONO, 9.0F);

    private final BooleanSetting healthText = add(new BooleanSetting("Health Text", true).describe("Numeric health next to the name."));

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

    // the trail lags behind the bar to show the damage just taken
    private final Animation bar = new Animation(1.0, 14.0);
    private final Animation trail = new Animation(1.0, 3.0);
    private EntityLivingBase animated;

    public TargetHUD(HudState hud) {
        super("TargetHUD", Category.VISUAL,
                "Glass panel under the crosshair showing your combat target's head and real health.");
        this.hud = hud;
        hud.registerScale("TargetHUD", scale);
    }

    @Override
    protected void onDisable() {
        display = null;
        lastSeenAt = 0L;
        combatTarget = null;
        combatTargetAt = 0L;
        animated = null;
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
        CustomFont font = NAME_FONT.get();
        CustomFont hpFont = HP_FONT.get();

        // Scoreboard health can exceed max health, so clamp the bar but show the raw value.
        float health = HealthResolver.resolve(shown);
        float fraction = MathHelper.clamp_float(
                health / Math.max(1.0F, shown.getMaxHealth()), 0.0F, 1.0F);
        if (shown != animated) {
            animated = shown;
            bar.set(fraction);
            trail.set(fraction);
        }
        float barFraction = (float) bar.update(fraction);
        float trailFraction = (float) trail.update(fraction);
        String name = shown.getName();
        String hp = healthText.get() ? String.format("%.1f", health) : null;
        int friendColor = FriendManager.getInstance().getColor(name);

        // Unscaled units; Scale is a matrix pinned at the top-center anchor.
        int textW = font.getStringWidth(name) + (hp != null ? TEXT_PAD + hpFont.getStringWidth(hp) : 0);
        int colW = Math.max(MIN_COL_W, textW);
        int panelW = Math.round(PAD + HEAD + HEAD_GAP + colW + PAD);
        int panelH = Math.round(PAD + HEAD + PAD);

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

        GlassShader.panel(left, top, panelW, panelH, RADIUS, Glass.SMOKE_PANEL);

        float headX = left + PAD;
        float headY = top + PAD;
        float colX = headX + HEAD + HEAD_GAP;
        float textY = headY + (HEAD - font.getHeight() - BAR_GAP - BAR_H) / 2.0F;
        int color = Color.HSBtoRGB(fraction / 3.0F, 0.39F, 0.89F); // soft red to soft green
        font.drawString(name, colX, textY, friendColor != 0 ? friendColor : TEXT);
        if (hp != null) {
            hpFont.drawString(hp, colX + colW - hpFont.getStringWidth(hp),
                    textY + font.getAscent() - hpFont.getAscent(), color);
        }

        float barY = textY + font.getHeight() + BAR_GAP;
        GlassShader.rect(colX, barY, colW, BAR_H, BAR_H / 2.0F, TRACK, TRACK);
        GlassShader.rect(colX, barY, colW * trailFraction, BAR_H, BAR_H / 2.0F, TRAIL, TRAIL);
        GlassShader.rect(colX, barY, colW * barFraction, BAR_H, BAR_H / 2.0F, color, color);

        if (shown instanceof AbstractClientPlayer) {
            int tint = ColorMath.lerpArgb(0xFFFFFFFF, HURT, shown.hurtTime / 10.0F);
            Minecraft.getMinecraft().getTextureManager().bindTexture(((AbstractClientPlayer) shown).getLocationSkin());
            GlassShader.image(headX, headY, HEAD, HEAD, HEAD_RADIUS, 0.125F, 0.125F, 0.25F, 0.25F, tint); // face
            GlassShader.image(headX, headY, HEAD, HEAD, HEAD_RADIUS, 0.625F, 0.125F, 0.75F, 0.25F, tint); // hat layer
        } else {
            // Mob skins share no face layout, so crop the top of the model instead.
            GlassShader.rect(headX, headY, HEAD, HEAD, HEAD_RADIUS, WELL, WELL);
            int modelScale = Math.round(HEAD / Math.max(0.5F, Math.min(shown.width, shown.height)));
            RenderUtil.beginScissor(anchorX + (headX - anchorX) * s, top + (headY - top) * s,
                    HEAD * s, HEAD * s, resolution.getScaleFactor());
            GlStateManager.enableDepth(); // limb self-occlusion needs the depth test
            GuiInventory.drawEntityOnScreen(Math.round(headX + HEAD / 2.0F), Math.round(headY + 2 + shown.height * modelScale),
                    modelScale, 0.0F, 0.0F, shown);
            // drawEntityOnScreen leaves colorMaterial enabled
            GlStateManager.disableColorMaterial();
            GlStateManager.disableDepth();
            RenderUtil.endScissor();
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
