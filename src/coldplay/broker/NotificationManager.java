package coldplay.broker;

import coldplay.gui.Glass;
import coldplay.gui.GlassShader;
import coldplay.gui.Theme;
import coldplay.event.EventRender2D;
import coldplay.event.EventTarget;
import coldplay.util.Animation;
import coldplay.util.RenderUtil;
import coldplay.util.font.CustomFont;
import coldplay.util.font.FontRef;
import coldplay.util.font.Fonts;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.util.MathHelper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** On-screen toasts for module toggles, keyed by module name so a repeated toggle refreshes its row. */
public final class NotificationManager {

    private static final NotificationManager INSTANCE = new NotificationManager();

    public static NotificationManager getInstance() {
        return INSTANCE;
    }

    private static final int COLOR_NAME = 0xFFFFFFFF;
    private static final int COLOR_STATE = 0x99FFFFFF;
    private static final int COLOR_OFF = 0xFF6E727A;
    private static final int ACCENT = 0xFF84D2E3;

    private static final int MARGIN = 4;
    private static final float ROW_GAP = 4.5F;
    private static final float MIN_W = 172.5F;
    private static final float H = 39.0F;
    private static final float PAD = 9.0F;
    private static final float DOT = 6.0F;
    private static final float GLOW = 12.0F;
    private static final float DOT_GAP = 7.5F;
    private static final float LINE_GAP = 1.5F;
    private static final float BAR_H = 1.5F;
    private static final float RADIUS = 6.75F;
    private static final long HOLD_MS = 2500L;
    private static final double ANIM_SPEED = 13.0;
    private static final int MAX_VISIBLE = 6;

    private static final FontRef NAME = new FontRef(Fonts.GEIST_SEMIBOLD, 9.375F);
    private static final FontRef STATE = new FontRef(Fonts.GEIST, 8.625F);

    private final Map<String, Entry> entries = new LinkedHashMap<>(); // insertion order is stack order

    private NotificationManager() {
    }

    public void push(String moduleName, boolean enabled) {
        if (Minecraft.getMinecraft().thePlayer == null) {
            return; // config restore toggles modules before any world exists
        }
        Entry entry = entries.get(moduleName);
        if (entry == null) {
            entry = new Entry(moduleName);
            entries.put(moduleName, entry);
        }
        entry.enabled = enabled;
        entry.expiresAt = System.currentTimeMillis() + HOLD_MS;
    }

    @EventTarget
    public void onRender2D(EventRender2D event) {
        if (entries.isEmpty()) {
            return;
        }
        Fonts.load(event.getResolution().getScaleFactor()); // first-frame init, GL context exists here
        if (!Fonts.isLoaded()) {
            return;
        }

        long now = System.currentTimeMillis();
        Iterator<Entry> it = entries.values().iterator();
        while (it.hasNext()) {
            Entry entry = it.next();
            boolean alive = now < entry.expiresAt;
            entry.progress.update(alive ? 1.0 : 0.0);
            if (!alive && entry.progress.get() < 0.01) {
                it.remove();
            }
        }
        if (entries.isEmpty()) {
            return;
        }

        CustomFont nameFont = NAME.get();
        CustomFont stateFont = STATE.get();
        ScaledResolution resolution = event.getResolution();
        int rightEdge = resolution.getScaledWidth() - MARGIN;

        List<Entry> rows = new ArrayList<>(entries.values());
        Collections.reverse(rows);
        if (rows.size() > MAX_VISIBLE) {
            rows = rows.subList(0, MAX_VISIBLE);
        }
        GlassShader.capture();
        double y = resolution.getScaledHeight() - MARGIN;
        for (Entry entry : rows) {
            String state = entry.enabled ? "Enabled" : "Disabled";
            int textWidth = Math.max(nameFont.getStringWidth(entry.name), stateFont.getStringWidth(state));
            float fullWidth = Math.max(MIN_W, PAD * 2 + DOT + DOT_GAP + textWidth);
            double p = entry.progress.get();

            int bottom = (int) Math.round(y);
            int top = (int) Math.round(y - H * p);
            int left = rightEdge - (int) Math.round(fullWidth * p);
            if (bottom > top && rightEdge > left) {
                float alpha = (float) p;
                GlassShader.frost(left, top, rightEdge - left, bottom - top, RADIUS, Glass.SMOKE);
                // Content sits at its final place; the scissor lets the growing glass reveal it.
                RenderUtil.beginScissor(left, top, rightEdge - left, bottom - top, resolution.getScaleFactor());
                float boxLeft = rightEdge - fullWidth;
                float boxTop = bottom - H;
                float dotX = boxLeft + PAD;
                float dotY = boxTop + (H - DOT) / 2.0F;
                if (entry.enabled) {
                    int glow = Theme.applyAlpha(0x5584D2E3, alpha);
                    float g = (GLOW - DOT) / 2.0F;
                    GlassShader.rect(dotX - g, dotY - g, GLOW, GLOW, GLOW / 2.0F, glow, glow);
                }
                int dotColor = Theme.applyAlpha(entry.enabled ? ACCENT : COLOR_OFF, alpha);
                GlassShader.rect(dotX, dotY, DOT, DOT, DOT / 2.0F, dotColor, dotColor);
                float textX = dotX + DOT + DOT_GAP;
                float textTop = boxTop + (H - nameFont.getHeight() - LINE_GAP - stateFont.getHeight()) / 2.0F;
                nameFont.drawString(entry.name, textX, textTop, Theme.applyAlpha(COLOR_NAME, alpha));
                stateFont.drawString(state, textX, textTop + nameFont.getHeight() + LINE_GAP,
                        Theme.applyAlpha(COLOR_STATE, alpha));
                float remaining = MathHelper.clamp_float((entry.expiresAt - now) / (float) HOLD_MS, 0.0F, 1.0F);
                int bar = Theme.applyAlpha(ACCENT, alpha);
                GlassShader.rect(boxLeft + RADIUS / 2.0F, bottom - BAR_H, (fullWidth - RADIUS) * remaining, BAR_H,
                        BAR_H / 2.0F, bar, bar);
                RenderUtil.endScissor();
            }
            y = top - ROW_GAP * p;
        }

        GlStateManager.color(1f, 1f, 1f, 1f);
        GlStateManager.enableBlend();
    }

    private static final class Entry {
        final String name;
        boolean enabled;
        long expiresAt;
        final Animation progress = new Animation(0.0, ANIM_SPEED);

        Entry(String name) {
            this.name = name;
        }
    }
}
