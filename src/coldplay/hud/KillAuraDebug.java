package coldplay.hud;

import coldplay.broker.RotationManager;
import coldplay.event.EventRender2D;
import coldplay.event.EventTarget;
import coldplay.gui.Theme;
import coldplay.setting.NumberSetting;
import coldplay.setting.RangeSetting;
import coldplay.util.RenderUtil;
import coldplay.util.font.CustomFont;
import coldplay.util.font.Fonts;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.util.MathHelper;
import net.minecraft.util.Vec3;
import org.lwjgl.opengl.GL11;

import java.util.function.BooleanSupplier;

/** Rotation broker history and aim tracer rendering. */
public final class KillAuraDebug {
    private static final double MARKER_HALF = 0.05;
    private static final int GRAPH_TICKS = 200;
    private static final int GRAPH_PLOT_H = 40;
    private static final int GRAPH_PAD = 4;
    private static final int GRAPH_GAP = 2;
    private static final int COLOR_AXIS = 0xFF3A3A44;
    private static final int COLOR_YAW = 0xFF5FB3FF;
    private static final int COLOR_PITCH = 0xFFFFB454;
    private static final int COLOR_YAW_RATE = 0xFF7FE38C;
    private static final int COLOR_PITCH_RATE = 0xFFE58CFF;
    private final NumberSetting graphScale;
    private final RangeSetting yawSpeed;
    private final RangeSetting pitchSpeed;
    private final float[] brokerYaw = new float[GRAPH_TICKS];
    private final float[] brokerPitch = new float[GRAPH_TICKS];
    private final float[] yawRates = new float[GRAPH_TICKS];
    private final float[] pitchRates = new float[GRAPH_TICKS];
    private int graphHead;
    private int graphCount;

    public KillAuraDebug(NumberSetting graphScale, RangeSetting yawSpeed, RangeSetting pitchSpeed) {
        this.graphScale = graphScale;
        this.yawSpeed = yawSpeed;
        this.pitchSpeed = pitchSpeed;
    }

    public Object graph(HudState hud, BooleanSupplier visible) {
        hud.registerScale("RotationGraph", graphScale);
        return new Object() {
            @EventTarget
            public void onRender2D(EventRender2D event) {
                drawGraph(event, hud, visible.getAsBoolean());
            }
        };
    }

    public static void drawTracer(Vec3 eyes, Vec3 look, Vec3 aim) {
        Vec3 tracerStart = eyes.add(look);
        RenderUtil.drawTracerMarker(tracerStart, aim, MARKER_HALF,
                255, 60, 60, 180, 90, 255, 2.0F);
    }

    /**
     * Records one tick of broker state for the graph. This must stay purely passive: no randomness,
     * no state that the aim reads back. The passive-diagnostics check asserts that toggling the
     * Render setting changes neither the aim progression nor the combat randomness, so a sample
     * taken here can never be allowed to consume a draw.
     */
    public void sample(EntityPlayerSP player, double yawRate, double pitchRate) {
        RotationManager rm = RotationManager.getInstance();
        brokerYaw[graphHead] = rm.isActive() ? rm.getServerYaw() : player.rotationYaw;
        brokerPitch[graphHead] = rm.isActive() ? rm.getServerPitch() : player.rotationPitch;
        yawRates[graphHead] = (float) yawRate;
        pitchRates[graphHead] = (float) pitchRate;
        graphHead = (graphHead + 1) % GRAPH_TICKS;
        graphCount = Math.min(graphCount + 1, GRAPH_TICKS);
    }

    private void drawGraph(EventRender2D event, HudState hud, boolean visible) {
        if (!hud.isEditing() && !visible) {
            return;
        }
        ScaledResolution resolution = event.getResolution();
        Fonts.load(resolution.getScaleFactor());
        if (!Fonts.isLoaded()) {
            return;
        }
        CustomFont font = Fonts.list;
        int textH = font.getHeight();
        int panelW = GRAPH_PAD * 2 + GRAPH_TICKS;
        int panelH = GRAPH_PAD * 2 + textH * 2 + GRAPH_PLOT_H * 2 + GRAPH_GAP * 3;
        HudState.Position pos = hud.getOrCreate("RotationGraph",
                resolution.getScaledWidth() - panelW - GRAPH_PAD, GRAPH_PAD,
                resolution.getScaledWidth(), resolution.getScaledHeight());
        int left = pos.x;
        int top = pos.y;
        float s = graphScale.get().floatValue();
        hud.report("RotationGraph", left, top, left + panelW, top + panelH, left, top, s);
        RenderUtil.pushScale(left, top, s);
        RenderUtil.drawBorderedRect(left, top, left + panelW, top + panelH, Theme.BODY, Theme.CONTOUR);

        int x0 = left + GRAPH_PAD;
        int legendY = top + GRAPH_PAD;
        int deltaTop = legendY + textH + GRAPH_GAP;
        int rateTop = deltaTop + GRAPH_PLOT_H + GRAPH_GAP;
        int valuesY = rateTop + GRAPH_PLOT_H + GRAPH_GAP;
        RenderUtil.hLine(x0, x0 + GRAPH_TICKS, deltaTop + GRAPH_PLOT_H / 2, COLOR_AXIS);
        RenderUtil.hLine(x0, x0 + GRAPH_TICKS, rateTop + GRAPH_PLOT_H - 1, COLOR_AXIS);

        double m = Math.max(yawSpeed.getHi(), pitchSpeed.getHi());
        GlStateManager.enableBlend();
        GlStateManager.disableTexture2D();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        plot(brokerYaw, true, true, x0, deltaTop, m, COLOR_YAW);
        plot(brokerPitch, true, false, x0, deltaTop, m, COLOR_PITCH);
        plot(yawRates, false, false, x0, rateTop, m, COLOR_YAW_RATE);
        plot(pitchRates, false, false, x0, rateTop, m, COLOR_PITCH_RATE);
        GlStateManager.enableTexture2D();

        int lx = label(font, "yaw", x0, legendY, COLOR_YAW);
        lx = label(font, "pitch", lx, legendY, COLOR_PITCH);
        lx = label(font, "yaw rate", lx, legendY, COLOR_YAW_RATE);
        label(font, "pitch rate", lx, legendY, COLOR_PITCH_RATE);

        int last = (graphHead + GRAPH_TICKS - 1) % GRAPH_TICKS;
        int prev = (last + GRAPH_TICKS - 1) % GRAPH_TICKS;
        double dYaw = graphCount < 2 ? 0.0 : MathHelper.wrapAngleTo180_double(brokerYaw[last] - brokerYaw[prev]);
        double dPitch = graphCount < 2 ? 0.0 : brokerPitch[last] - brokerPitch[prev];
        String deltas = String.format("yaw %+.1f  pitch %+.1f", dYaw, dPitch);
        String rates = String.format("rate %.1f / %.1f", yawRates[last], pitchRates[last]);
        font.drawStringWithShadow(deltas, x0, valuesY, Theme.TEXT);
        font.drawStringWithShadow(rates, x0 + GRAPH_TICKS - font.getStringWidth(rates), valuesY, Theme.TEXT);
        GlStateManager.popMatrix();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
    }

    private void plot(float[] series, boolean delta, boolean wrap, int x0, int top, double m, int color) {
        if (graphCount < 2) {
            return;
        }
        int r = color >> 16 & 0xFF, g = color >> 8 & 0xFF, b = color & 0xFF;
        Tessellator tessellator = Tessellator.getInstance();
        WorldRenderer wr = tessellator.getWorldRenderer();
        wr.begin(GL11.GL_LINE_STRIP, DefaultVertexFormats.POSITION_COLOR);
        int oldest = (graphHead - graphCount + GRAPH_TICKS) % GRAPH_TICKS;
        int x = x0 + GRAPH_TICKS - graphCount;
        for (int i = 0; i < graphCount; i++, x++) {
            int at = (oldest + i) % GRAPH_TICKS;
            double y;
            if (delta) {
                double step = i == 0 ? 0.0 : series[at] - series[(at + GRAPH_TICKS - 1) % GRAPH_TICKS];
                if (wrap) {
                    step = MathHelper.wrapAngleTo180_double(step);
                }
                y = top + GRAPH_PLOT_H / 2.0 - MathHelper.clamp_double(step, -m, m) / m * (GRAPH_PLOT_H / 2.0);
            } else {
                y = top + GRAPH_PLOT_H - 1 - MathHelper.clamp_double(series[at], 0.0, m) / m * (GRAPH_PLOT_H - 1);
            }
            wr.pos(x + 0.5, y, 0.0).color(r, g, b, 255).endVertex();
        }
        tessellator.draw();
    }

    private static int label(CustomFont font, String text, int x, int y, int color) {
        font.drawStringWithShadow(text, x, y, color);
        return x + font.getStringWidth(text) + 6;
    }
}
