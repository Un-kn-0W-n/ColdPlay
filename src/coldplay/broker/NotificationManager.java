package coldplay.broker;

import coldplay.gui.Theme;
import coldplay.event.EventRender2D;
import coldplay.event.EventTarget;
import coldplay.util.Animation;
import coldplay.util.RenderUtil;
import coldplay.util.font.CustomFont;
import coldplay.util.font.Fonts;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;

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

    private static final int COLOR_BOX = 0xF0101014;
    private static final int COLOR_BORDER = 0xFFB9B9C2;
    private static final int COLOR_TEXT_ON = 0xFFFFFFFF;
    private static final int COLOR_TEXT_OFF = 0xFFB4B4BE;

    private static final int MARGIN = 4;
    private static final int ROW_GAP = 3;
    private static final int PAD_X = 5;
    private static final int PAD_Y = 2;
    private static final long HOLD_MS = 2500L;
    private static final double ANIM_SPEED = 13.0;
    private static final int MAX_VISIBLE = 6;

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

        CustomFont font = Fonts.list;
        ScaledResolution resolution = event.getResolution();
        int rightEdge = resolution.getScaledWidth() - MARGIN;
        int fullHeight = font.getHeight() + PAD_Y * 2;

        List<Entry> rows = new ArrayList<>(entries.values());
        Collections.reverse(rows);
        if (rows.size() > MAX_VISIBLE) {
            rows = rows.subList(0, MAX_VISIBLE);
        }
        double y = resolution.getScaledHeight() - MARGIN;
        for (Entry entry : rows) {
            String text = entry.name + (entry.enabled ? " Enabled" : " Disabled");
            int textWidth = font.getStringWidth(text);
            double p = entry.progress.get();

            int bottom = (int) Math.round(y);
            int top = (int) Math.round(y - fullHeight * p);
            int left = rightEdge - (int) Math.round((textWidth + PAD_X * 2) * p);
            if (bottom > top && rightEdge > left) {
                float alpha = (float) p;
                RenderUtil.drawBorderedRect(left, top, rightEdge, bottom,
                        Theme.applyAlpha(COLOR_BOX, alpha), Theme.applyAlpha(COLOR_BORDER, alpha));
                // Scissor so the wiping box reveals the text in place.
                RenderUtil.beginScissor(left, top, rightEdge - left, bottom - top, resolution.getScaleFactor());
                font.drawStringWithShadow(text, rightEdge - PAD_X - textWidth, bottom - PAD_Y - font.getHeight(),
                        Theme.applyAlpha(entry.enabled ? COLOR_TEXT_ON : COLOR_TEXT_OFF, alpha));
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
