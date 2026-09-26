package coldplay.module.visual;

import coldplay.broker.NameTagRegistry;
import coldplay.event.EventRender;
import coldplay.event.EventRender2D;
import coldplay.event.EventRender3D;
import coldplay.event.EventRenderMirror;
import coldplay.event.EventTarget;
import coldplay.gui.Glass;
import coldplay.gui.GlassShader;
import coldplay.gui.Theme;
import coldplay.setting.BooleanSetting;
import coldplay.util.HealthResolver;
import coldplay.util.ProjectionUtil;
import coldplay.util.RenderUtil;
import coldplay.util.font.CustomFont;
import coldplay.util.font.FontRef;
import coldplay.util.font.Fonts;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.MathHelper;
import net.minecraft.util.Vec3;

import org.lwjgl.BufferUtils;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * A glass badge above each entity: health in a colored block, then the name and distance, with the held item and
 * armor on top. Vanilla's own name plate is hidden for these entities through {@link NameTagRegistry}.
 */
public class NameTags extends EntityVisual {
    // GUI px, the badge canvas x0.75
    private static final float CHIP_H = 14.25F;
    private static final float RADIUS = 4.5F;
    private static final float BORDER = 0.75F;
    private static final float HP_PAD = 4.5F;
    private static final float NAME_PAD_LEFT = 5.25F;
    private static final float NAME_PAD_RIGHT = 6.0F;
    private static final float GAP = 3.75F;
    private static final float DOT = 4.5F;
    private static final float ICON = 9.0F;
    private static final float ICON_GAP = 1.5F;
    private static final float ROW_GAP = 2.25F;

    private static final int TEXT = 0xFFF4F6F8;
    private static final int TEXT_DIM = 0x8CF4F6F8;
    private static final int HP_TEXT = 0xFF0B0D11;
    private static final FontRef NAME_FONT = new FontRef(Fonts.GEIST_SEMIBOLD, 8.25F);
    private static final FontRef HP_FONT = new FontRef(Fonts.GEIST_MONO_SEMIBOLD, 7.875F);
    private static final FontRef DIST_FONT = new FontRef(Fonts.GEIST_MONO_MEDIUM, 7.125F);

    private final BooleanSetting items = add(new BooleanSetting("Items", true).describe("Held item and armor above the badge."));
    private final BooleanSetting distance = add(new BooleanSetting("Distance", true).describe("Distance after the name."));

    // gluProject scratch, reused across frames
    private final FloatBuffer modelview = BufferUtils.createFloatBuffer(16);
    private final FloatBuffer projection = BufferUtils.createFloatBuffer(16);
    private final IntBuffer viewport = BufferUtils.createIntBuffer(16);
    private final FloatBuffer screenCoords = BufferUtils.createFloatBuffer(3);

    // Raw framebuffer px; filled in the world pass, drawn in the HUD pass.
    private final List<ScreenNameTag> nameTags2D = new ArrayList<ScreenNameTag>();

    public NameTags() {
        super("NameTags", "Health, name, distance, held item and armor in a badge above each entity.");
        addFilters();
    }

    @Override
    protected void onDisable() {
        nameTags2D.clear();
        targets.clear();
        NameTagRegistry.getInstance().clear();
    }

    @EventTarget
    public void onRender(EventRender event) {
        collectTargets(event.getPartialTicks());
        // before either camera's entity pass, so vanilla skips these plates this frame
        List<Entity> shown = new ArrayList<Entity>(targets.size());
        for (Target target : targets) {
            shown.add(target.entity);
        }
        NameTagRegistry.getInstance().update(shown);
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
            drawNameTags(event.getScale(), true);
        }
    }

    @EventTarget
    public void onRender2D(EventRender2D event) {
        drawNameTags(event.getResolution().getScaleFactor(), false);
    }

    private void renderView(float partialTicks) {
        nameTags2D.clear();
        EntityPlayerSP player = Minecraft.getMinecraft().thePlayer;
        // glGet* stalls the pipeline, so skip the capture when idle
        if (player == null || targets.isEmpty()) {
            return;
        }
        Vec3 viewer = RenderUtil.interpolatedPosition(player, partialTicks);
        ProjectionUtil.captureMatrices(modelview, projection, viewport);
        for (Target target : targets) {
            collectNameTag((EntityLivingBase) target.entity, target, viewer);
        }
        // far badges first so near ones draw on top
        nameTags2D.sort((a, b) -> Double.compare(b.distance, a.distance));
    }

    private void collectNameTag(EntityLivingBase entity, Target target, Vec3 viewer) {
        ProjectionUtil.Point anchor = ProjectionUtil.projectPoint(target.centerX, target.box.maxY + 0.25,
                target.centerZ, viewer.xCoord, viewer.yCoord, viewer.zCoord,
                modelview, projection, viewport, screenCoords);
        if (anchor == null) {
            return;
        }
        float health = HealthResolver.resolve(entity);
        float maxHealth = entity.getMaxHealth();
        float fraction = maxHealth > 0.0F ? MathHelper.clamp_float(health / maxHealth, 0.0F, 1.0F) : 0.0F;
        // held first, then helmet down to boots
        ItemStack[] shown = {
                entity.getHeldItem(), entity.getCurrentArmor(3), entity.getCurrentArmor(2),
                entity.getCurrentArmor(1), entity.getCurrentArmor(0)
        };
        nameTags2D.add(new ScreenNameTag(anchor.x, anchor.y,
                EnumChatFormatting.getTextWithoutFormattingCodes(entity.getName()),
                String.format("%.1f", health), fraction, target.friendColor,
                String.format("%.1fm", target.distance), target.distance, shown));
    }

    /** The mirror shows its texture flipped, so there each badge is drawn flipped about its anchor to read the right way. */
    private void drawNameTags(int scale, boolean mirror) {
        if (nameTags2D.isEmpty()) {
            return;
        }
        Fonts.load(scale); // lazy init, needs a live GL context
        if (!Fonts.isLoaded()) {
            return;
        }
        CustomFont nameFont = NAME_FONT.get();
        CustomFont hpFont = HP_FONT.get();
        CustomFont distFont = DIST_FONT.get();
        GlassShader.capture(); // one backdrop for every badge
        for (ScreenNameTag tag : nameTags2D) {
            float ax = tag.x / scale;
            float ay = tag.y / scale;

            boolean friend = tag.friendColor != 0;
            float hpW = HP_PAD + hpFont.getStringWidth(tag.hp) + HP_PAD;
            float nameW = nameFont.getStringWidth(tag.name);
            float rightW = NAME_PAD_LEFT + (friend ? DOT + GAP : 0.0F) + nameW
                    + (distance.get() ? GAP + distFont.getStringWidth(tag.dist) : 0.0F) + NAME_PAD_RIGHT;
            float chipW = BORDER + hpW + rightW + BORDER;
            float chipX = ax - chipW / 2.0F;
            float chipY = ay - CHIP_H;

            if (mirror) {
                GlStateManager.disableCull();
                GlStateManager.pushMatrix();
                GlStateManager.translate(ax * 2.0F, 0.0F, 0.0F);
                GlStateManager.scale(-1.0F, 1.0F, 1.0F);
            }
            GlassShader.frost(chipX, chipY, chipW, CHIP_H, RADIUS, Glass.SMOKE);
            // the health block sits inside the border, square on its right side
            int hpColor = Theme.healthColor(tag.fraction);
            float blockX = chipX + BORDER, blockY = chipY + BORDER, blockH = CHIP_H - BORDER * 2.0F;
            float inner = RADIUS - BORDER;
            GlassShader.rect(blockX, blockY, hpW, blockH, inner, hpColor, hpColor);
            GlassShader.rect(blockX + hpW - inner, blockY, inner, blockH, 0.0F, hpColor, hpColor);
            if (friend) {
                GlassShader.stroke(chipX, chipY, chipW, CHIP_H, RADIUS, 0x8C000000 | tag.friendColor & 0xFFFFFF);
            }
            hpFont.drawString(tag.hp, blockX + HP_PAD, chipY + (CHIP_H - hpFont.getHeight()) / 2.0F, HP_TEXT);
            float x = blockX + hpW + NAME_PAD_LEFT;
            if (friend) {
                int dot = 0xFF000000 | tag.friendColor;
                GlassShader.rect(x, chipY + (CHIP_H - DOT) / 2.0F, DOT, DOT, DOT / 2.0F, dot, dot);
                x += DOT + GAP;
            }
            nameFont.drawString(tag.name, x, chipY + (CHIP_H - nameFont.getHeight()) / 2.0F, TEXT);
            if (distance.get()) {
                distFont.drawString(tag.dist, x + nameW + GAP, chipY + (CHIP_H - distFont.getHeight()) / 2.0F, TEXT_DIM);
            }
            if (mirror) {
                GlStateManager.popMatrix();
                GlStateManager.enableCull();
            }

            if (items.get()) {
                drawItems(tag, ax, chipY - ROW_GAP - ICON, mirror);
            }
        }
        nameTags2D.clear();
    }

    private static void drawItems(ScreenNameTag tag, float ax, float y, boolean mirror) {
        int count = 0;
        for (ItemStack stack : tag.items) {
            if (stack != null) {
                count++;
            }
        }
        if (count == 0) {
            return;
        }
        float x = ax - (count * ICON + (count - 1) * ICON_GAP) / 2.0F;
        RenderUtil.beginItems(); // one GL-light setup per badge, not per icon
        for (ItemStack stack : tag.items) {
            if (stack == null) {
                continue;
            }
            // mirrored slots, upright icons
            RenderUtil.drawItemRaw(stack, mirror ? ax * 2.0F - x - ICON : x, y, ICON / 16.0F);
            x += ICON + ICON_GAP;
        }
        RenderUtil.endItems();
    }

    private static final class ScreenNameTag {
        final float x, y; // anchor above the head
        final String name;
        final String hp;
        final float fraction;
        final int friendColor; // 0 when not a friend
        final String dist;
        final double distance;
        final ItemStack[] items; // held, helmet..boots; entries may be null

        ScreenNameTag(float x, float y, String name, String hp, float fraction, int friendColor,
                      String dist, double distance, ItemStack[] items) {
            this.x = x;
            this.y = y;
            this.name = name;
            this.hp = hp;
            this.fraction = fraction;
            this.friendColor = friendColor;
            this.dist = dist;
            this.distance = distance;
            this.items = items;
        }
    }
}
