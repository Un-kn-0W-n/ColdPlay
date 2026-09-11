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

/**
 * Module-state toasts keyed by name so repeated toggles refresh the existing row. A single animation
 * per row keeps its wipe, fade and stack reflow synchronized.
 */
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
    /** Render cap only (expiry still prunes every row): newest rows win the visible column. */
    private static final int MAX_VISIBLE = 6;

    /** Insertion-ordered so rows keep their stack slot; refreshing a key never moves it. */
    private final Map<String, Entry> entries = new LinkedHashMap<>();

    private NotificationManager() {
    }

    /** Shows (or refreshes) the toast for {@code moduleName}. Client thread only, like all toggles. */
    public void push(String moduleName, boolean enabled) {
        if (Minecraft.getMinecraft().thePlayer == null) {
            return; // startup config restore toggles modules before any world exists
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
        Fonts.load(event.getResolution().getScaleFactor()); // lazy, first-frame init (GL context guaranteed here)
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
            // Cap the drawn column so a mass toggle (e.g. config load) can't stack off the top edge.
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
                // Text pinned where it sits fully shown, scissored so the wiping box reveals it.
                RenderUtil.beginScissor(left, top, rightEdge - left, bottom - top, resolution.getScaleFactor());
                font.drawStringWithShadow(text, rightEdge - PAD_X - textWidth, bottom - PAD_Y - font.getHeight(),
                        Theme.applyAlpha(entry.enabled ? COLOR_TEXT_ON : COLOR_TEXT_OFF, alpha));
                RenderUtil.endScissor();
            }
            y = top - ROW_GAP * p;
        }

        // Restore a clean state for the next textured draw / next frame.
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
