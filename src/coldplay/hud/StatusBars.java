package coldplay.hud;

import coldplay.util.RenderUtil;
import coldplay.util.font.Fonts;

import net.minecraft.block.material.Material;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.MathHelper;

/** Pill bars for golden apple health, health, armor and hunger, drawn in place of the vanilla icon rows. */
public final class StatusBars {

    private static final int BAR_W = 90;
    private static final int BAR_H = 11;
    private static final int GAP = 2;
    private static final int LOWER_ROW = 46; // bar top, up from the screen bottom
    private static final int XP_RAIL = 29;   // rail top, up from the screen bottom
    private static final int XP_RAIL_H = 2;
    private static final int BUBBLE = 9;

    private static final int TRACK = 0xF01C1C1C; // hotbar panel
    private static final int ABSORPTION = 0xFFDEBB19;
    private static final int HEALTH = 0xFF6CD981;
    private static final int ARMOR = 0xFF449DF0;
    private static final int HUNGER = 0xFFFFA752;
    private static final int ICON = 0xEBFFFFFF;
    private static final int ICON_DETAIL = 0x47000000;
    private static final int TEXT = 0xFFFFFFFF;
    private static final int XP_TRACK = 0xB3101014;
    private static final int XP_FILL = 0xFFB9B9C2;
    private static final int XP_TEXT = 0xFFB4B4BE;

    // 7x7 icons: X is the icon, D a darker detail
    private static final String[] APPLE = {
            "....XX.", "...X...", ".XXXXX.", "XXXXXXX", "XXXXXXX", "XXXXXXX", ".XX.XX."};
    private static final String[] HEART = {
            ".XX.XX.", "XXXXXXX", "XXXXXXX", "XXXXXXX", ".XXXXX.", "..XXX..", "...X..."};
    private static final String[] SHIELD = {
            "XXXXXXX", "XXXDXXX", "XXXDXXX", "XXXDXXX", ".XXDXX.", "..XDX..", "...X..."};
    private static final String[] FOOD = {
            "..XXXX.", ".XXXXXX", ".XXXXXX", ".XXXXXX", "..XXXX.", ".XX....", "XX....."};

    private static float absorptionPeak;

    private StatusBars() {
    }

    /** Returns false when the view entity is not a player, leaving the frame to vanilla. */
    public static boolean render(ScaledResolution resolution, Gui gui) {
        Minecraft mc = Minecraft.getMinecraft();
        if (!(mc.getRenderViewEntity() instanceof EntityPlayer)) {
            return false;
        }
        EntityPlayer player = (EntityPlayer) mc.getRenderViewEntity();
        int left = resolution.getScaledWidth() / 2 - 91;
        int right = left + BAR_W + GAP;
        int lower = resolution.getScaledHeight() - LOWER_ROW;
        int upper = lower - BAR_H - GAP;

        float absorption = player.getAbsorptionAmount();
        absorptionPeak = trackAbsorption(absorptionPeak, absorption);
        boolean golden = absorption > 0.0F;
        float health = player.getHealth();
        int armor = player.getTotalArmorValue();
        int food = player.getFoodStats().getFoodLevel();

        if (golden) {
            bar(left, upper, absorption / absorptionPeak, ABSORPTION);
        }
        bar(left, lower, health / Math.max(1.0F, player.getMaxHealth()), HEALTH);
        bar(right, upper, armor / 20.0F, ARMOR);
        bar(right, lower, food / 20.0F, HUNGER);

        WorldRenderer wr = RenderUtil.beginQuads();
        if (golden) {
            appendIcon(wr, APPLE, left, upper);
        }
        appendIcon(wr, HEART, left, lower);
        appendIcon(wr, SHIELD, right, upper);
        appendIcon(wr, FOOD, right, lower);
        RenderUtil.endQuads();

        Fonts.load(resolution.getScaleFactor());
        if (golden) {
            label(MathHelper.ceiling_float_int(absorption), left, upper);
        }
        label(MathHelper.ceiling_float_int(health), left, lower);
        label(armor, right, upper);
        label(food, right, lower);

        mc.getTextureManager().bindTexture(Gui.icons);
        GlStateManager.enableBlend();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        if (player.isInsideOfMaterial(Material.water)) {
            // vanilla bubbles, right-aligned on a row above the armor bar
            int air = player.getAir();
            int full = MathHelper.ceiling_double_int((air - 2) * 10.0D / 300.0D);
            int popping = MathHelper.ceiling_double_int(air * 10.0D / 300.0D) - full;
            int y = upper - GAP - BUBBLE;
            for (int i = 0; i < full + popping; i++) {
                gui.drawTexturedModalRect(right + BAR_W - i * 8 - BUBBLE, y, i < full ? 16 : 25, 18, BUBBLE, BUBBLE);
            }
        }
        return true;
    }

    /** Level number and a thin rail in place of the vanilla XP bar. */
    public static boolean renderExperience(ScaledResolution resolution, EntityPlayer player) {
        if (player == null) {
            return false;
        }
        int left = resolution.getScaledWidth() / 2 - 91;
        int right = left + BAR_W * 2 + GAP;
        int railTop = resolution.getScaledHeight() - XP_RAIL;
        int railLeft = left;
        Fonts.load(resolution.getScaleFactor());
        if (player.experienceLevel > 0) {
            String level = String.valueOf(player.experienceLevel);
            drawText(level, left, railTop + XP_RAIL_H / 2.0F, XP_TEXT);
            railLeft += textWidth(level) + 3;
        }
        if (player.xpBarCap() > 0) {
            int fill = Math.round((right - railLeft) * MathHelper.clamp_float(player.experience, 0.0F, 1.0F));
            WorldRenderer wr = RenderUtil.beginQuads();
            RenderUtil.appendRect(wr, railLeft, railTop, right, railTop + XP_RAIL_H, XP_TRACK);
            RenderUtil.appendRect(wr, railLeft, railTop, railLeft + fill, railTop + XP_RAIL_H, XP_FILL);
            RenderUtil.endQuads();
        }
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        return true;
    }

    /** Top edge of the bars, a row higher while air bubbles show. */
    public static int stackTop(ScaledResolution resolution, EntityPlayer player) {
        int top = resolution.getScaledHeight() - LOWER_ROW - BAR_H - GAP;
        if (player != null && player.isInsideOfMaterial(Material.water)) {
            top -= GAP + BUBBLE;
        }
        return top;
    }

    /** The golden apple bar drains against the most absorption held since it was last empty. */
    static float trackAbsorption(float peak, float absorption) {
        return absorption > 0.0F ? Math.max(peak, absorption) : 0.0F;
    }

    /** A non-empty fill never drops below {@code minWidth}, so its rounded ends survive. */
    static int fillWidth(float fraction, int width, int minWidth) {
        if (!(fraction > 0.0F)) {
            return 0;
        }
        return Math.max(minWidth, Math.round(Math.min(1.0F, fraction) * width));
    }

    private static void bar(int x, int y, float fraction, int color) {
        RenderUtil.drawRoundedRect(x, y, BAR_W, BAR_H, BAR_H / 2.0, TRACK);
        int fill = fillWidth(fraction, BAR_W, BAR_H);
        if (fill > 0) {
            RenderUtil.drawRoundedRect(x, y, fill, BAR_H, BAR_H / 2.0, color);
        }
    }

    private static void appendIcon(WorldRenderer wr, String[] rows, int barX, int barY) {
        int left = barX + 3;
        int top = barY + (BAR_H - rows.length) / 2;
        for (int row = 0; row < rows.length; row++) {
            String line = rows[row];
            int start = 0;
            while (start < line.length()) {
                char c = line.charAt(start);
                int end = start + 1;
                while (end < line.length() && line.charAt(end) == c) {
                    end++;
                }
                if (c != '.') {
                    RenderUtil.appendRect(wr, left + start, top + row, left + end, top + row + 1,
                            c == 'D' ? ICON_DETAIL : ICON);
                }
                start = end;
            }
        }
    }

    private static void label(int value, int barX, int barY) {
        String text = String.valueOf(value);
        drawText(text, barX + (BAR_W - textWidth(text)) / 2.0F, barY + BAR_H / 2.0F, TEXT);
    }

    private static int textWidth(String text) {
        return Fonts.isLoaded() ? Fonts.list.getStringWidth(text)
                : Minecraft.getMinecraft().fontRendererObj.getStringWidth(text);
    }

    // The vanilla font stands in until the HUD font atlas is baked.
    private static void drawText(String text, float x, float centerY, int color) {
        if (Fonts.isLoaded()) {
            Fonts.list.drawStringWithShadow(text, x, centerY - Fonts.list.getHeight() / 2.0F, color);
        } else {
            Minecraft.getMinecraft().fontRendererObj.drawStringWithShadow(text, x, centerY - 4.0F, color);
        }
    }
}
