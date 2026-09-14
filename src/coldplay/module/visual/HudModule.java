package coldplay.module.visual;

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

    private static final int COLOR_BOX = 0xF0101014;
    private static final int COLOR_BORDER = 0xFFB9B9C2;
    private static final int COLOR_TEXT = 0xFFFFFFFF;
    private static final int COLOR_SUFFIX = 0xFFB4B4BE; // gray mode suffix / watermark version

    // Layout dimensions use scaled GUI pixels.
    private static final int MARGIN = 3;   // default gap from the screen edge
    private static final int WM_GAP = 3;   // default gap between watermark and list
    private static final int PAD_X = 5;    // horizontal text padding inside a box
    private static final int PAD_Y = 2;    // vertical text padding inside a box
    private static final double ANIM_SPEED = 13.0; // higher = snappier ease

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
        CustomFont listFont = Fonts.list;
        CustomFont titleFont = Fonts.title;

        ScaledResolution resolution = event.getResolution();
        int screenWidth = resolution.getScaledWidth();
        int screenHeight = resolution.getScaledHeight();
        int scaleFactor = resolution.getScaleFactor();

        // The list anchor is the corner its rows pin to; which screen half it sits in picks the alignment.
        float wmScale = watermarkScale.get().floatValue();
        float scale = listScale.get().floatValue();
        int wmHeight = Math.round((titleFont.getHeight() + PAD_Y * 2) * wmScale);
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
        RenderUtil.pushScale(listState.x, listState.y, scale);
        for (Line l : lines) {
            RenderUtil.rectBounds(l.left, l.top, l.right, l.bottom, Theme.applyAlpha(COLOR_BOX, (float) l.progress));
        }
        drawContour(lines, listState.x, right);
        for (Line l : lines) {
            drawText(l, listState.x, listState.y, right, scaleFactor, scale);
        }
        GlStateManager.popMatrix();
        if (showWatermark) {
            drawWatermark(titleFont, listFont, wmState.x, wmState.y, wmScale);
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
        return (l.font.getHeight() + PAD_Y * 2) * l.progress;
    }

    /** Rounds cumulative boundaries so adjacent rows share a pixel edge. */
    private static void resolveGeometry(List<Line> lines, double startY, int anchorX, boolean right) {
        double y = startY;
        for (Line l : lines) {
            double slotH = slotHeight(l);
            l.top = (int) Math.round(y);
            l.bottom = (int) Math.round(y + slotH);
            int boxWidth = (int) Math.round((l.width + PAD_X * 2) * l.progress);
            l.left = right ? anchorX - boxWidth : anchorX;
            l.right = l.left + boxWidth;
            y += slotH;
        }
    }

    /** Draws the shared border; connectors between rows of different width sit inside the wider row's fill. */
    private static void drawContour(List<Line> lines, int anchorX, boolean right) {
        if (lines.isEmpty()) {
            return;
        }
        int outer = anchorX;                      // shared straight edge (fill boundary)
        int outerCol = right ? outer - 1 : outer; // 1px line column just inside the fills
        Line prev = null;
        for (int i = 0; i < lines.size(); i++) {
            Line l = lines.get(i);
            int inner = right ? l.left : l.right;          // this row's free-edge fill boundary
            int innerCol = right ? inner : inner - 1;
            int color = Theme.applyAlpha(COLOR_BORDER, (float) l.progress);

            RenderUtil.vLine(outerCol, l.top, l.bottom, color);
            RenderUtil.vLine(innerCol, l.top, l.bottom, color);
            if (i == 0) {
                RenderUtil.hLine(outer, inner, l.top, color);
            }
            if (i == lines.size() - 1) {
                RenderUtil.hLine(outer, inner, l.bottom - 1, color);
            }

            if (prev != null) {
                int innerPrev = right ? prev.left : prev.right;
                if (innerPrev != inner) { // equal widths merge into one slab
                    boolean prevWider = Math.abs(innerPrev - outer) > Math.abs(inner - outer);
                    Line wide = prevWider ? prev : l;
                    int bandY = prevWider ? l.top - 1 : l.top; // sits inside the wider row's fill
                    int x0 = Math.min(innerPrev, inner);
                    int x1 = Math.max(innerPrev, inner);
                    // Extend one px toward the narrower row so the connector butt-joins its inner line.
                    if (right) {
                        x1 += 1;
                    } else {
                        x0 -= 1;
                    }
                    RenderUtil.rectBounds(x0, bandY, x1, bandY + 1,
                            Theme.applyAlpha(COLOR_BORDER, (float) wide.progress));
                }
            }
            prev = l;
        }
    }

    /** Text is pinned at its fully-shown position and scissored to the box while the box wipes in. */
    private static void drawText(Line l, int anchorX, int anchorY, boolean right, int scaleFactor, float scale) {
        if (l.right <= l.left || l.bottom <= l.top) {
            return;
        }
        float p = (float) l.progress;
        int textX = right ? anchorX - l.width - PAD_X : anchorX + PAD_X;
        int textY = l.top + PAD_Y;
        boolean clip = p < 0.999F; // fully shown rows need no scissor
        if (clip) {
            // scissor ignores the matrix, so scale the rect around the anchor by hand
            RenderUtil.beginScissor(anchorX + (l.left - anchorX) * scale, anchorY + (l.top - anchorY) * scale,
                    (l.right - l.left) * scale, (l.bottom - l.top) * scale, scaleFactor);
        }
        l.font.drawStringWithShadow(l.name, textX, textY, Theme.applyAlpha(COLOR_TEXT, p));
        if (l.suffix != null) {
            l.font.drawStringWithShadow(" " + l.suffix, textX + l.nameWidth, textY, Theme.applyAlpha(COLOR_SUFFIX, p));
        }
        if (clip) {
            RenderUtil.endScissor();
        }
    }

    private void drawWatermark(CustomFont titleFont, CustomFont listFont, int left, int top, float scale) {
        String name = clientName;
        String version = " v" + clientVersion;
        int nameWidth = titleFont.getStringWidth(name);
        int chipWidth = nameWidth + listFont.getStringWidth(version) + PAD_X * 2;
        int chipHeight = titleFont.getHeight() + PAD_Y * 2;

        RenderUtil.pushScale(left, top, scale);
        RenderUtil.drawBorderedRect(left, top, left + chipWidth, top + chipHeight, COLOR_BOX, COLOR_BORDER);
        titleFont.drawStringWithShadow(name, left + PAD_X, top + PAD_Y, COLOR_TEXT);
        listFont.drawStringWithShadow(version, left + PAD_X + nameWidth,
                top + PAD_Y + (titleFont.getAscent() - listFont.getAscent()), COLOR_SUFFIX);
        GlStateManager.popMatrix();
        if (hud.isEditing()) {
            hud.report("Watermark", left, top, left + chipWidth, top + chipHeight, left, top, scale);
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
            this.width = nameWidth + (suffix != null ? font.getStringWidth(" " + suffix) : 0);
            this.progress = progress;
        }
    }
}
