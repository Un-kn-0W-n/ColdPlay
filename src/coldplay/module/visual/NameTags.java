package coldplay.module.visual;

import coldplay.event.EventRender;
import coldplay.event.EventRender2D;
import coldplay.event.EventRender3D;
import coldplay.event.EventRenderMirror;
import coldplay.event.EventTarget;
import coldplay.util.HealthResolver;
import coldplay.util.ProjectionUtil;
import coldplay.util.RenderUtil;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.item.ItemStack;
import net.minecraft.util.MathHelper;
import net.minecraft.util.Vec3;

import org.lwjgl.BufferUtils;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;

/** Held item, armor and a real-health bar above each entity; anchors project in the world pass. */
public class NameTags extends EntityVisual {
    // gluProject scratch, reused across frames
    private final FloatBuffer modelview = BufferUtils.createFloatBuffer(16);
    private final FloatBuffer projection = BufferUtils.createFloatBuffer(16);
    private final IntBuffer viewport = BufferUtils.createIntBuffer(16);
    private final FloatBuffer screenCoords = BufferUtils.createFloatBuffer(3);

    // Raw framebuffer px; filled in the world pass, drawn in the HUD pass.
    private final List<ScreenNameTag> nameTags2D = new ArrayList<ScreenNameTag>();

    public NameTags() {
        super("NameTags", "Held item, armor and a real-health bar above each entity.");
        addFilters();
    }

    @Override
    protected void onDisable() {
        nameTags2D.clear();
        targets.clear();
    }

    @EventTarget
    public void onRender(EventRender event) {
        collectTargets(event.getPartialTicks());
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
            drawNameTags(event.getScale());
        }
    }

    @EventTarget
    public void onRender2D(EventRender2D event) {
        drawNameTags(event.getResolution().getScaleFactor());
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
            if (target.entity instanceof EntityLivingBase) {
                collectNameTag((EntityLivingBase) target.entity, target, viewer);
            }
        }
    }

    private void collectNameTag(EntityLivingBase entity, Target target, Vec3 viewer) {
        ProjectionUtil.Point anchor = ProjectionUtil.projectPoint(target.centerX, target.box.maxY + 0.25,
                target.centerZ, viewer.xCoord, viewer.yCoord, viewer.zCoord,
                modelview, projection, viewport, screenCoords);
        if (anchor == null) {
            return;
        }
        float maxHealth = entity.getMaxHealth();
        float fraction = maxHealth > 0.0F
                ? MathHelper.clamp_float(HealthResolver.resolve(entity) / maxHealth, 0.0F, 1.0F)
                : 0.0F;
        ItemStack[] armor = {
                entity.getCurrentArmor(0), entity.getCurrentArmor(1),
                entity.getCurrentArmor(2), entity.getCurrentArmor(3)
        };
        int borderColor = target.friendColor != 0 ? target.friendColor : RenderUtil.rgb(0, 0, 0);
        nameTags2D.add(new ScreenNameTag(anchor.x, anchor.y, entity.getHeldItem(), armor,
                fraction, borderColor));
    }

    private void drawNameTags(int scale) {
        if (nameTags2D.isEmpty()) {
            return;
        }
        final int iconSize = 8; // 16px GUI icon * 0.5
        final int iconGap = 1;
        final int barBorder = 2;
        final int barInnerHeight = 3;
        final int rowBarGap = 2;
        final int minBarWidth = 60;

        for (ScreenNameTag tag : nameTags2D) {
            float anchorX = tag.x / scale;
            float anchorY = tag.y / scale;

            int icons = tag.held != null ? 1 : 0;
            for (ItemStack piece : tag.armor) {
                if (piece != null) {
                    icons++;
                }
            }
            int rowW = icons > 0 ? icons * iconSize + (icons - 1) * iconGap : 0;
            int barW = Math.max(rowW, minBarWidth);

            // bar sits just above the head, icon row above the bar
            int barBottom = Math.round(anchorY);
            int barTop = barBottom - (barInnerHeight + barBorder * 2);
            int barLeft = Math.round(anchorX - barW / 2.0F);

            // held item first, then armor boots..helmet
            if (icons > 0) {
                int iconY = barTop - rowBarGap - iconSize;
                int cursorX = Math.round(anchorX - rowW / 2.0F);
                RenderUtil.beginItems(); // one GL-light setup per tag, not per icon
                if (tag.held != null) {
                    RenderUtil.drawItemRaw(tag.held, cursorX, iconY, 0.5F);
                    cursorX += iconSize + iconGap;
                }
                for (ItemStack piece : tag.armor) {
                    if (piece == null) {
                        continue;
                    }
                    RenderUtil.drawItemRaw(piece, cursorX, iconY, 0.5F);
                    cursorX += iconSize + iconGap;
                }
                RenderUtil.endItems();
            }

            WorldRenderer bar = RenderUtil.beginQuads();
            RenderUtil.appendOutline(bar, barLeft, barTop, barLeft + barW, barBottom, barBorder, tag.borderColor);
            int fillWidth = Math.round((barW - barBorder * 2) * tag.healthFraction);
            RenderUtil.appendRect(bar, barLeft + barBorder, barTop + barBorder,
                    barLeft + barBorder + fillWidth, barBottom - barBorder,
                    RenderUtil.lerpRedGreen(tag.healthFraction));
            RenderUtil.endQuads();
        }
        nameTags2D.clear();
    }

    private static final class ScreenNameTag {
        final float x, y; // anchor above the head
        final ItemStack held;
        final ItemStack[] armor; // boots..helmet, entries may be null
        final float healthFraction;
        final int borderColor; // friend's colour, black otherwise

        ScreenNameTag(float x, float y, ItemStack held, ItemStack[] armor, float healthFraction, int borderColor) {
            this.x = x;
            this.y = y;
            this.held = held;
            this.armor = armor;
            this.healthFraction = healthFraction;
            this.borderColor = borderColor;
        }
    }
}
