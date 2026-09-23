package coldplay.module.visual;

import coldplay.gui.Glass;
import coldplay.gui.GlassShader;
import coldplay.gui.Theme;
import coldplay.event.EventRender2D;
import coldplay.event.EventTarget;
import coldplay.hud.HudState;
import coldplay.module.Category;
import coldplay.module.Module;
import coldplay.module.ModuleView;
import coldplay.setting.BooleanSetting;
import coldplay.setting.NumberSetting;
import coldplay.util.Animation;
import coldplay.util.RenderUtil;
import coldplay.util.font.CustomFont;
import coldplay.util.font.FontRef;
import coldplay.util.font.Fonts;

import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** Enabled-module list plus watermark; rows linger after disable so they can fade out. */
public class HudModule extends Module {

    private static final int COLOR_TEXT = 0xFFFFFFFF;
    private static final int COLOR_SUFFIX = 0x8CFFFFFF; // mode suffix and watermark version
    private static final int ACCENT = 0xFF84D2E3;

    // Layout dimensions use scaled GUI pixels.
    private static final int MARGIN = 3;          // default gap from the screen edge
    private static final int WM_GAP = 3;          // default gap between watermark and list
    private static final float ROW_H = 16.5F;
    private static final float PAD_FREE = 7.5F;   // text inset from the rounded edge
    private static final float PAD_ANCHOR = 9.0F; // text inset from the accent edge
    private static final float SUFFIX_GAP = 3.75F;
    private static final float ROW_RADIUS = 3.75F;
    private static final float BAR_W = 1.5F;      // accent strip along the anchored edge
    private static final float CHIP_H = 22.5F;
    private static final float CHIP_PAD = 9.0F;
    private static final float CHIP_GAP = 4.5F;
    private static final float CHIP_RADIUS = 5.25F;
    private static final double ANIM_SPEED = 13.0; // higher = snappier ease

    private static final FontRef ROW_FONT = new FontRef(Fonts.GEIST, 9.375F);
    private static final FontRef NAME_FONT = new FontRef(Fonts.GEIST_SEMIBOLD, 11.25F);
    private static final FontRef VERSION_FONT = new FontRef(Fonts.GEIST_MONO, 8.25F);

    private final BooleanSetting arrayList = add(new BooleanSetting("ArrayList", true).describe("Show the list of enabled modules."));
    private final BooleanSetting suffixes = add(new BooleanSetting("Suffixes", true)
            .describe("Show a module's active mode next to its name."));
    private final NumberSetting listScale = add(HudState.scaleSetting("ArrayList Scale"));
    private final BooleanSetting watermark = add(new BooleanSetting("Watermark", true).describe("Show the ColdPlay watermark."));
    private final NumberSetting watermarkScale = add(HudState.scaleSetting("Watermark Scale"));
    private final BooleanSetting animations = add(new BooleanSetting("Animations", true).describe("Wipe and fade HUD entries in and out."));

    private final Supplier<List<ModuleView>> modules;
    private final HudState hud;
    private final String clientName;
    private final String clientVersion;
    private final Map<String, Row> progress = new LinkedHashMap<String, Row>();

    public HudModule(Supplier<List<ModuleView>> modules, HudState hud,
                     String clientName, String clientVersion) {
        super("HUD", Category.VISUAL, "On-screen HUD: a list of active modules plus a watermark.");
        this.modules = modules;
        this.hud = hud;
        this.clientName = clientName;
        this.clientVersion = clientVersion;
        suffixes.visibleWhen(arrayList::get).indent(1);
        listScale.visibleWhen(arrayList::get).indent(1);
        watermarkScale.visibleWhen(watermark::get).indent(1);
        hud.registerScale("ArrayList", listScale);
        hud.registerScale("Watermark", watermarkScale);
    }

    @EventTarget
    public void onRender2D(EventRender2D event) {
        boolean showList = arrayList.get();
        boolean showWatermark = watermark.get();
        if (!showList && !showWatermark) {
            return;
        }

        Fonts.load(event.getResolution().getScaleFactor()); // lazy init, needs a live GL context
        if (!Fonts.isLoaded()) {
            return;
        }
        CustomFont listFont = ROW_FONT.get();

        ScaledResolution resolution = event.getResolution();
        int screenWidth = resolution.getScaledWidth();
        int screenHeight = resolution.getScaledHeight();
        int scaleFactor = resolution.getScaleFactor();

        // The list anchor is the corner its rows pin to; which screen half it sits in picks the alignment.
        float wmScale = watermarkScale.get().floatValue();
        float scale = listScale.get().floatValue();
        int wmHeight = Math.round(CHIP_H * wmScale);
        HudState.Position wmState = hud.getOrCreate("Watermark", MARGIN, MARGIN,
                screenWidth, screenHeight);
        HudState.Position listState = hud.getOrCreate(
                "ArrayList", MARGIN, MARGIN + wmHeight + WM_GAP, screenWidth, screenHeight);
        boolean right = listState.x > screenWidth / 2;
        boolean top = listState.y < screenHeight / 2;

        updateAnimations(showList, animations.get());

        // Longest row hugs the anchored corner; the name tie-break stops equal widths from swapping.
        boolean showSuffixes = suffixes.get();
        List<Line> lines = new ArrayList<>();
        for (Map.Entry<String, Row> entry : progress.entrySet()) {
            Row row = entry.getValue();
            double p = row.animation.get();
            if (p <= 0.001) {
                continue;
            }
            lines.add(new Line(entry.getKey(), showSuffixes ? row.suffix : null, listFont, p));
        }
        lines.sort(BY_WIDTH_DESC);
        if (!top) {
            Collections.reverse(lines);
        }
        if (lines.isEmpty() && !showWatermark) {
            return;
        }

        // For a bottom anchor the stored y is the stack's bottom edge, so rows grow upward from it.
        double listHeight = 0;
        for (Line l : lines) {
            listHeight += slotHeight(l);
        }
        double rowsTop = top ? listState.y : listState.y - listHeight;

        resolveGeometry(lines, rowsTop, listState.x, right);
        GlassShader.capture();
        RenderUtil.pushScale(listState.x, listState.y, scale);
        drawGlass(lines, listState.x, listState.y, right, scaleFactor, scale);
        for (Line l : lines) {
            drawText(l, listState.x, listState.y, right, scaleFactor, scale);
        }
        GlStateManager.popMatrix();
        if (showWatermark) {
            drawWatermark(wmState.x, wmState.y, wmScale);
        }
        if (!lines.isEmpty() && hud.isEditing()) {
            int minLeft = Integer.MAX_VALUE;
            int maxRight = Integer.MIN_VALUE;
            for (Line l : lines) {
                minLeft = Math.min(minLeft, l.left);
                maxRight = Math.max(maxRight, l.right);
            }
            hud.report("ArrayList", minLeft, lines.get(0).top,
                    maxRight, lines.get(lines.size() - 1).bottom, listState.x, listState.y, scale);
        }

        GlStateManager.color(1f, 1f, 1f, 1f);
        GlStateManager.enableBlend();
    }

    private void updateAnimations(boolean showList, boolean anim) {
        Map<String, ModuleView> current = new LinkedHashMap<String, ModuleView>();
        for (ModuleView module : modules.get()) {
            current.put(module.getName(), module);
        }
        if (showList) {
            for (ModuleView module : current.values()) {
                if (module.getName().equals(getName())) {
                    continue; // never list the HUD module itself
                }
                if (module.isEnabled() && !progress.containsKey(module.getName())) {
                    progress.put(module.getName(), new Row(module.getSuffix()));
                }
            }
        }
        Iterator<Map.Entry<String, Row>> it = progress.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Row> entry = it.next();
            ModuleView module = current.get(entry.getKey());
            Row row = entry.getValue();
            boolean on = showList && module != null && module.isEnabled()
                    && !module.getName().equals(getName());
            if (module != null) {
                row.suffix = module.getSuffix();
            }
            double target = on ? 1.0 : 0.0;
            if (anim) {
                row.animation.update(target);
            } else {
                row.animation.set(target);
            }
            if (!on && row.animation.get() < 0.01) {
                it.remove();
            }
        }
    }

    private static double slotHeight(Line l) {
        return ROW_H * l.progress;
    }

    /** Rounds cumulative boundaries so adjacent rows share a pixel edge. */
    private static void resolveGeometry(List<Line> lines, double startY, int anchorX, boolean right) {
        double y = startY;
        for (Line l : lines) {
            double slotH = slotHeight(l);
            l.top = (int) Math.round(y);
            l.bottom = (int) Math.round(y + slotH);
            int boxWidth = (int) Math.round((l.width + PAD_FREE + PAD_ANCHOR) * l.progress);
            l.left = right ? anchorX - boxWidth : anchorX;
            l.right = l.left + boxWidth;
            y += slotH;
        }
    }

    /**
     * One glass slab per row, rounded on the free edge only: each slab runs past the anchored edge by its
     * radius and the scissor cuts that end off square.
     */
    private static void drawGlass(List<Line> lines, int anchorX, int anchorY, boolean right, int scaleFactor, float scale) {
        if (lines.isEmpty()) {
            return;
        }
        int left = anchorX;
        int rightEdge = anchorX;
        for (Line l : lines) {
            left = Math.min(left, l.left);
            rightEdge = Math.max(rightEdge, l.right);
        }
        int top = lines.get(0).top;
        int bottom = lines.get(lines.size() - 1).bottom;
        // scissor ignores the matrix, so scale the rect around the anchor by hand
        RenderUtil.beginScissor(anchorX + (left - anchorX) * scale, anchorY + (top - anchorY) * scale,
                (rightEdge - left) * scale, (bottom - top) * scale, scaleFactor);
        for (Line l : lines) {
            int w = l.right - l.left;
            if (w <= 0 || l.bottom <= l.top) {
                continue;
            }
            GlassShader.frost(right ? l.left : l.left - ROW_RADIUS, l.top, w + ROW_RADIUS, l.bottom - l.top,
                    ROW_RADIUS, Glass.SMOKE);
        }
        RenderUtil.endScissor();
        float barX = right ? anchorX - BAR_W : anchorX;
        for (Line l : lines) {
            int color = Theme.applyAlpha(ACCENT, (float) l.progress);
            GlassShader.rect(barX, l.top, BAR_W, l.bottom - l.top, 0.0F, color, color);
        }
    }

    /** Text is pinned at its fully-shown position and scissored to the box while the box wipes in. */
    private static void drawText(Line l, int anchorX, int anchorY, boolean right, int scaleFactor, float scale) {
        if (l.right <= l.left || l.bottom <= l.top) {
            return;
        }
        float p = (float) l.progress;
        float textX = right ? anchorX - PAD_ANCHOR - l.width : anchorX + PAD_ANCHOR;
        float textY = l.top + (ROW_H - l.font.getHeight()) / 2.0F;
        boolean clip = p < 0.999F; // fully shown rows need no scissor
        if (clip) {
            RenderUtil.beginScissor(anchorX + (l.left - anchorX) * scale, anchorY + (l.top - anchorY) * scale,
                    (l.right - l.left) * scale, (l.bottom - l.top) * scale, scaleFactor);
        }
        l.font.drawString(l.name, textX, textY, Theme.applyAlpha(COLOR_TEXT, p));
        if (l.suffix != null) {
            l.font.drawString(l.suffix, textX + l.nameWidth + SUFFIX_GAP, textY, Theme.applyAlpha(COLOR_SUFFIX, p));
        }
        if (clip) {
            RenderUtil.endScissor();
        }
    }

    private void drawWatermark(int left, int top, float scale) {
        CustomFont nameFont = NAME_FONT.get();
        CustomFont versionFont = VERSION_FONT.get();
        String version = "v" + clientVersion;
        int nameWidth = nameFont.getStringWidth(clientName, -0.11F);
        float chipWidth = CHIP_PAD + nameWidth + CHIP_GAP + versionFont.getStringWidth(version) + CHIP_PAD;

        RenderUtil.pushScale(left, top, scale);
        GlassShader.frost(left, top, chipWidth, CHIP_H, CHIP_RADIUS, Glass.SMOKE);
        float nameTop = top + (CHIP_H - nameFont.getHeight()) / 2.0F;
        nameFont.drawString(clientName, left + CHIP_PAD, nameTop, COLOR_TEXT, -0.11F);
        // share the name's baseline
        versionFont.drawString(version, left + CHIP_PAD + nameWidth + CHIP_GAP,
                nameTop + nameFont.getAscent() - versionFont.getAscent(), COLOR_SUFFIX);
        GlStateManager.popMatrix();
        if (hud.isEditing()) {
            hud.report("Watermark", left, top, Math.round(left + chipWidth), Math.round(top + CHIP_H), left, top, scale);
        }
    }

    private static final class Row {
        final Animation animation = new Animation(0.0, ANIM_SPEED);
        String suffix;

        Row(String suffix) {
            this.suffix = suffix;
        }
    }

    private static final Comparator<Line> BY_WIDTH_DESC =
            Comparator.comparingInt((Line l) -> l.width).reversed().thenComparing(l -> l.name);

    private static final class Line {
        final String name;
        final String suffix; // active mode value, or null
        final CustomFont font;
        final int nameWidth;
        final int width;     // full text width incl. suffix
        final double progress;

        int left;
        int right;
        int top;
        int bottom;

        Line(String name, String suffix, CustomFont font, double progress) {
            this.name = name;
            this.suffix = suffix;
            this.font = font;
            this.nameWidth = font.getStringWidth(name);
            this.width = nameWidth + (suffix != null ? Math.round(SUFFIX_GAP) + font.getStringWidth(suffix) : 0);
            this.progress = progress;
        }
    }
}
