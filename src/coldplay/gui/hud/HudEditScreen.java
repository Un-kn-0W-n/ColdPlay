package coldplay.gui.hud;

import coldplay.ColdPlay;
import coldplay.gui.Glass;
import coldplay.gui.GlassShader;
import coldplay.hud.HudState;
import coldplay.module.Module;
import coldplay.module.visual.Mirror;
import coldplay.setting.NumberSetting;
import coldplay.util.RenderUtil;
import coldplay.util.font.CustomFont;
import coldplay.util.font.FontRef;
import coldplay.util.font.Fonts;

import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.util.MathHelper;
import org.lwjglx.input.Keyboard;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Blueprint HUD editor: drag to move, drag a corner to resize, arrows nudge the selected element. */
public class HudEditScreen extends GuiScreen {

    private static final int FROST = 0xFF84D2E3;
    private static final int INK = 0xFF0B1A1E;
    private static final int GRID = 0x1284D2E3;
    private static final int THIRDS = 0x5984D2E3;
    private static final float TAB_H = 12.0F;
    private static final float TAB_GAP = 3.0F;
    private static final float PILL_H = 9.75F;
    private static final float BAR_H = 25.5F;
    private static final String NOTHING = "Select an element";
    private static final Glass BAR = new Glass(0xB80E1015, 0xB80E1015, 0x4D84D2E3, 10.5F, 1.4F, 24.0F, 9.0F, 0.3F);
    private static final FontRef TAB = new FontRef(Fonts.GEIST_SEMIBOLD, 7.875F);
    private static final FontRef SMALL_MONO = new FontRef(Fonts.GEIST_MONO_MEDIUM, 7.125F);
    private static final FontRef BAR_NAME = new FontRef(Fonts.GEIST_SEMIBOLD, 9.0F);
    private static final FontRef BAR_TEXT = new FontRef(Fonts.GEIST, 8.25F);
    private static final FontRef BAR_MONO = new FontRef(Fonts.GEIST_MONO, 8.25F);
    private static final FontRef KEY = new FontRef(Fonts.GEIST_MONO, 7.5F);
    private static final String[][] KEYS = {{"Arrows", "Nudge"}, {"Shift", "x10"}, {"R", "Reset"}, {"Esc", "Done"}};
    private static final String[] ROWS = {"Top", "Middle", "Bottom"};
    private static final String[] COLUMNS = {"left", "center", "right"};

    private final HudState hud;

    private String grabbed; // null when idle
    private String selected; // survives the release, null after a click on nothing
    private int grabDX;
    private int grabDY;
    private int grabW;
    private int grabH;
    private static final int RESIZE_HANDLE = 8;
    private Mirror mirror;
    private boolean resizing;
    private int pendingW; // applied on release
    private int pendingH;
    private boolean resizeLeft;
    private boolean resizeTop;
    private int fixedX; // the corner opposite the dragged one
    private int fixedY;
    private NumberSetting scaling; // null while resizing Mirror
    private double baseScale;
    private float pivotX; // anchor as a fraction of the box
    private float pivotY;

    public HudEditScreen() {
        this(ColdPlay.getInstance().getHudState(), null);
    }

    HudEditScreen(HudState hud, Mirror mirror) {
        this.hud = hud;
        this.mirror = mirror;
    }

    @Override
    public void initGui() {
        finishDrag();
        hud.rebase(this.width, this.height);
        hud.beginEditing();
        for (Module module : ColdPlay.getInstance().getModuleManager().getModules()) {
            if (module instanceof Mirror) {
                mirror = (Mirror) module;
            }
        }
    }

    /** Shade, grid and thirds under the HUD; GuiIngame calls this right after {@link #updateDrag}. */
    public void drawBlueprint() {
        float w = this.width, h = this.height;
        float px = w / this.mc.displayWidth;
        RenderUtil.rectBounds(0, 0, this.width, this.height, 0x8C000000);
        int[] anchor = anchor(selected);
        if (anchor != null) {
            GlassShader.rect(anchor[0] * w / 3.0F, anchor[1] * h / 3.0F, w / 3.0F, h / 3.0F, 0.0F, GRID, GRID);
        }
        for (float x = 0.0F; x < w; x += 8.0F) {
            GlassShader.rect(x, 0.0F, 0.75F, h, 0.0F, GRID, GRID);
        }
        for (float y = h - 0.75F; y > -0.75F; y -= 8.0F) {
            GlassShader.rect(0.0F, y, w, 0.75F, 0.0F, GRID, GRID);
        }
        WorldRenderer wr = RenderUtil.beginQuads();
        for (int i = 1; i <= 2; i++) {
            float x = Math.round(w * i / 3.0F / px) * px;
            float y = Math.round(h * i / 3.0F / px) * px;
            for (float d = 0.0F; d < h; d += 2.5F) {
                quad(wr, x, d, px, Math.min(1.5F, h - d), THIRDS);
            }
            for (float d = 0.0F; d < w; d += 2.5F) {
                quad(wr, d, y, Math.min(1.5F, w - d), px, THIRDS);
            }
        }
        RenderUtil.endQuads();
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        Fonts.load();
        float px = this.width / (float) this.mc.displayWidth;
        Map<String, int[]> boxes = hud.getBoxes();
        int[] sel = selected != null ? boxes.get(selected) : null;
        float[] bar = barRect(sel, boxes.values(), px);
        for (Map.Entry<String, int[]> entry : boxes.entrySet()) {
            drawBox(entry.getValue(), entry.getValue() == sel);
        }
        drawTabs(boxes, sel, bar);
        for (Map.Entry<String, int[]> entry : boxes.entrySet()) {
            drawPin(entry.getKey(), entry.getValue());
        }
        if (sel != null) {
            if (resizable(selected)) {
                for (int corner = 0; corner < 4; corner++) {
                    float cx = (corner & 1) == 0 ? sel[0] - 1.5F : sel[2] + 1.5F;
                    float cy = (corner & 2) == 0 ? sel[1] - 1.5F : sel[3] + 1.5F;
                    GlassShader.ellipse(cx, cy, 2.8125F, 2.8125F, 0xFFFFFFFF, FROST, 1.125F);
                }
            }
            drawMeasures(sel, px);
        }
        drawBar(sel, bar, px);
    }

    private static void drawBox(int[] b, boolean on) {
        float x = b[0] - 1.5F, y = b[1] - 1.5F, w = b[2] - b[0] + 3.0F, h = b[3] - b[1] + 3.0F;
        int fill = on ? 0x1A84D2E3 : 0x0F84D2E3;
        GlassShader.rect(x, y, w, h, 2.25F, fill, fill);
        if (on) {
            GlassShader.arc(x + 0.75F, y + 0.75F, w - 1.5F, h - 1.5F, 1.5F, 1.5F, 0.0F, 1.0F, FROST);
        } else {
            GlassShader.stroke(x, y, w, h, 2.25F, 0xD984D2E3);
        }
    }

    /** Each tab takes the first spot above, below, right or left of its box that covers the least. */
    private void drawTabs(Map<String, int[]> boxes, int[] sel, float[] bar) {
        List<float[]> placed = new ArrayList<float[]>();
        CustomFont font = TAB.get();
        CustomFont mono = SMALL_MONO.get();
        for (Map.Entry<String, int[]> entry : boxes.entrySet()) {
            String name = entry.getKey();
            int[] b = entry.getValue();
            float w = tabWidth(name);
            int[] anchor = anchor(name);
            float alignX = anchor != null && anchor[0] == 2 ? b[2] + 1.5F - w : b[0] - 1.5F;
            float[][] spots = {{alignX, b[1] - TAB_GAP - TAB_H}, {alignX, b[3] + TAB_GAP},
                    {b[2] + TAB_GAP, b[1] - 1.5F}, {b[0] - TAB_GAP - w, b[1] - 1.5F}};
            float[] tab = spots[0];
            int least = Integer.MAX_VALUE;
            for (float[] spot : spots) {
                spot[0] = MathHelper.clamp_float(spot[0], 0.0F, this.width - w);
                spot[1] = MathHelper.clamp_float(spot[1], 0.0F, this.height - TAB_H);
                int covered = overlaps(spot[0], spot[1], w, TAB_H, bar[0], bar[1], bar[2], BAR_H) ? 1 : 0;
                for (int[] other : boxes.values()) {
                    if (overlaps(spot[0], spot[1], w, TAB_H, other[0] - 1.5F, other[1] - 1.5F,
                            other[2] - other[0] + 3.0F, other[3] - other[1] + 3.0F)) {
                        covered++;
                    }
                }
                for (float[] other : placed) {
                    if (overlaps(spot[0], spot[1], w, TAB_H, other[0], other[1], other[2], TAB_H)) {
                        covered++;
                    }
                }
                if (covered < least) {
                    tab = spot;
                    least = covered;
                }
            }
            placed.add(new float[]{tab[0], tab[1], w});
            int fill = b == sel ? 0xFFFFFFFF : FROST;
            GlassShader.rect(tab[0], tab[1], w, TAB_H, 2.25F, fill, fill);
            font.drawString(name, tab[0] + 3.75F, tab[1] + (TAB_H - font.getHeight()) / 2.0F, INK);
            String scale = scaleText(name);
            if (!scale.isEmpty()) {
                mono.drawString(scale, tab[0] + 7.5F + font.getStringWidth(name),
                        tab[1] + (TAB_H - mono.getHeight()) / 2.0F, 0xB30B1A1E);
            }
        }
    }

    private static boolean overlaps(float ax, float ay, float aw, float ah, float bx, float by, float bw, float bh) {
        return ax < bx + bw && bx < ax + aw && ay < by + bh && by < ay + ah;
    }

    private float tabWidth(String name) {
        String scale = scaleText(name);
        float w = 7.5F + TAB.get().getStringWidth(name);
        return scale.isEmpty() ? w : w + 3.75F + SMALL_MONO.get().getStringWidth(scale);
    }

    private String scaleText(String name) {
        NumberSetting scale = hud.getScale(name);
        if (scale != null) {
            return String.format(Locale.ROOT, "%.2fx", scale.get());
        }
        return "Mirror".equals(name) && mirror != null ? mirror.getWidth() + "x" + mirror.getHeight() : "";
    }

    /** Diamond on the box edge the element is anchored to. */
    private void drawPin(String name, int[] b) {
        int[] anchor = anchor(name);
        if (anchor == null) {
            return;
        }
        float x = b[0] + anchor[0] * (b[2] - b[0]) / 2.0F + (anchor[0] - 1) * 1.5F;
        float y = b[1] + anchor[1] * (b[3] - b[1]) / 2.0F + (anchor[1] - 1) * 1.5F;
        diamond(x, y, 8.25F, 0xCC0B0D11);
        diamond(x, y, 5.25F, FROST);
    }

    private static void diamond(float cx, float cy, float side, int color) {
        GlStateManager.pushMatrix();
        GlStateManager.translate(cx, cy, 0.0F);
        GlStateManager.rotate(45.0F, 0.0F, 0.0F, 1.0F);
        GlassShader.rect(-side / 2.0F, -side / 2.0F, side, side, 0.0F, color, color);
        GlStateManager.popMatrix();
    }

    /** Gap to each screen edge the selected element is anchored to. */
    private void drawMeasures(int[] b, float px) {
        int[] anchor = anchor(selected);
        if (anchor == null) {
            return;
        }
        float cx = Math.round((b[0] + b[2]) / 2.0F / px) * px;
        float cy = Math.round((b[1] + b[3]) / 2.0F / px) * px;
        if (anchor[1] == 0) {
            GlassShader.rect(cx, 0.0F, px, b[1] - 2.25F, 0.0F, 0xFFFFFFFF, 0xFFFFFFFF);
            pill(String.valueOf(b[1]), cx + 3.0F, 0.75F);
        } else if (anchor[1] == 2) {
            GlassShader.rect(cx, b[3] + 2.25F, px, this.height - b[3] - 2.25F, 0.0F, 0xFFFFFFFF, 0xFFFFFFFF);
            pill(String.valueOf(this.height - b[3]), cx + 3.0F, this.height - 0.75F - PILL_H);
        }
        if (anchor[0] == 0) {
            GlassShader.rect(0.0F, cy, b[0] - 2.25F, px, 0.0F, 0xFFFFFFFF, 0xFFFFFFFF);
            pill(String.valueOf(b[0]), 3.75F, cy + 4.5F);
        } else if (anchor[0] == 2) {
            GlassShader.rect(b[2] + 2.25F, cy, this.width - b[2] - 2.25F, px, 0.0F, 0xFFFFFFFF, 0xFFFFFFFF);
            String gap = String.valueOf(this.width - b[2]);
            pill(gap, this.width - 3.75F - pillWidth(gap), cy + 4.5F);
        }
    }

    private static float pillWidth(String text) {
        return 6.0F + SMALL_MONO.get().getStringWidth(text);
    }

    private static void pill(String text, float x, float y) {
        CustomFont font = SMALL_MONO.get();
        GlassShader.rect(x, y, pillWidth(text), PILL_H, 2.25F, 0xFFFFFFFF, 0xFFFFFFFF);
        font.drawString(text, x + 3.0F, y + (PILL_H - font.getHeight()) / 2.0F, INK);
    }

    /** Status bar text: name, anchor, offset and scale of the selected element, null where it has none. */
    private String[] barParts(int[] b) {
        int[] anchor = b != null ? anchor(selected) : null;
        String place = null;
        String offset = null;
        if (anchor != null) {
            place = anchor[0] == 1 && anchor[1] == 1 ? "Center" : ROWS[anchor[1]] + " " + COLUMNS[anchor[0]];
            offset = offset(b[0], b[2], this.width, anchor[0]) + ", " + offset(b[1], b[3], this.height, anchor[1]);
        }
        String scale = b != null ? scaleText(selected) : "";
        return new String[]{b != null ? selected : null, place, offset, scale.isEmpty() ? null : scale};
    }

    /** Top center, or bottom center when the top would hide an element and the bottom would not. */
    private float[] barRect(int[] sel, Iterable<int[]> boxes, float px) {
        CustomFont text = BAR_TEXT.get();
        CustomFont mono = BAR_MONO.get();
        CustomFont key = KEY.get();
        String[] parts = barParts(sel);
        float w = 9.75F + 5.25F;
        w += parts[0] != null ? BAR_NAME.get().getStringWidth(parts[0]) + 7.5F : text.getStringWidth(NOTHING) + 7.5F;
        if (parts[1] != null) {
            w += 10.5F + text.getStringWidth(parts[1]) + 7.5F + mono.getStringWidth(parts[2]) + 7.5F;
        }
        if (parts[3] != null) {
            w += mono.getStringWidth(parts[3]) + 7.5F;
        }
        w += 0.75F + 7.5F;
        for (int i = 0; i < KEYS.length; i++) {
            w += 7.5F + key.getStringWidth(KEYS[i][0]) + 3.75F + text.getStringWidth(KEYS[i][1]) + (i + 1 < KEYS.length ? 7.5F : 0.0F);
        }
        float x = Math.round((this.width - w) / 2.0F / px) * px;
        float top = 9.0F;
        float bottom = this.height - 9.0F - BAR_H;
        return new float[]{x, hides(x, top, w, boxes) && !hides(x, bottom, w, boxes) ? bottom : top, w};
    }

    private static boolean hides(float x, float y, float w, Iterable<int[]> boxes) {
        for (int[] b : boxes) {
            if (overlaps(x, y, w, BAR_H, b[0], b[1], b[2] - b[0], b[3] - b[1])) {
                return true;
            }
        }
        return false;
    }

    private void drawBar(int[] b, float[] bar, float px) {
        CustomFont name = BAR_NAME.get();
        CustomFont text = BAR_TEXT.get();
        CustomFont mono = BAR_MONO.get();
        CustomFont key = KEY.get();
        String[] parts = barParts(b);
        float y = bar[1];
        GlassShader.panel(bar[0], y, bar[2], BAR_H, 6.75F, BAR);
        float cx = bar[0] + 9.75F;
        if (parts[0] != null) {
            name.drawString(parts[0], cx, y + (BAR_H - name.getHeight()) / 2.0F, 0xFFF4F6F8);
            cx += name.getStringWidth(parts[0]) + 7.5F;
        } else {
            text.drawString(NOTHING, cx, y + (BAR_H - text.getHeight()) / 2.0F, 0x9EFFFFFF);
            cx += text.getStringWidth(NOTHING) + 7.5F;
        }
        if (parts[1] != null) {
            float bw = 10.5F + text.getStringWidth(parts[1]);
            GlassShader.rect(cx, y + 5.25F, bw, 15.0F, 3.75F, 0x2984D2E3, 0x2984D2E3);
            text.drawString(parts[1], cx + 5.25F, y + 5.25F + (15.0F - text.getHeight()) / 2.0F, FROST);
            cx += bw + 7.5F;
            mono.drawString(parts[2], cx, y + (BAR_H - mono.getHeight()) / 2.0F, 0x9EFFFFFF);
            cx += mono.getStringWidth(parts[2]) + 7.5F;
        }
        if (parts[3] != null) {
            mono.drawString(parts[3], cx, y + (BAR_H - mono.getHeight()) / 2.0F, 0x9EFFFFFF);
            cx += mono.getStringWidth(parts[3]) + 7.5F;
        }
        GlassShader.rect(Math.round(cx / px) * px, y + 6.75F, px, 12.0F, 0.0F, 0x24FFFFFF, 0x24FFFFFF);
        cx += 0.75F + 7.5F;
        for (String[] pair : KEYS) {
            float kw = 7.5F + key.getStringWidth(pair[0]);
            GlassShader.rect(cx, y + 6.375F, kw, 12.75F, 3.0F, 0x0FFFFFFF, 0x0FFFFFFF);
            GlassShader.stroke(cx, y + 6.375F, kw, 12.75F, 3.0F, 0x2EFFFFFF);
            key.drawString(pair[0], cx + 3.75F, y + 6.375F + (12.75F - key.getHeight()) / 2.0F, 0xCCFFFFFF);
            cx += kw + 3.75F;
            text.drawString(pair[1], cx, y + (BAR_H - text.getHeight()) / 2.0F, 0x8CFFFFFF);
            cx += text.getStringWidth(pair[1]) + 7.5F;
        }
    }

    /** Distance from the anchored edge, or from the center line for a centered axis. */
    private static int offset(int low, int high, int size, int zone) {
        if (zone == 0) {
            return low;
        }
        return zone == 2 ? size - high : (low + high) / 2 - size / 2;
    }

    /** Column and row (0-2) of the screen part the element's stored position is anchored to. */
    private int[] anchor(String name) {
        HudState.Position position = name != null ? hud.getPositions().get(name) : null;
        if (position == null || hud.getBox(name) == null) {
            return null;
        }
        return new int[]{HudState.zone(position.x, this.width), HudState.zone(position.y, this.height)};
    }

    private static void quad(WorldRenderer wr, float x, float y, float w, float h, int argb) {
        int a = argb >>> 24, r = argb >> 16 & 0xFF, g = argb >> 8 & 0xFF, b = argb & 0xFF;
        wr.pos(x, y + h, 0.0D).color(r, g, b, a).endVertex();
        wr.pos(x + w, y + h, 0.0D).color(r, g, b, a).endVertex();
        wr.pos(x + w, y, 0.0D).color(r, g, b, a).endVertex();
        wr.pos(x, y, 0.0D).color(r, g, b, a).endVertex();
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) {
        if (mouseButton != 0 || grabbed != null) {
            return;
        }
        // resize handles win over any overlapping element; the element drawn last is on top
        List<Map.Entry<String, int[]>> entries = new ArrayList<Map.Entry<String, int[]>>(hud.getBoxes().entrySet());
        Collections.reverse(entries);
        for (Map.Entry<String, int[]> entry : entries) {
            int[] b = entry.getValue();
            if (resizable(entry.getKey()) && onHandle(b, mouseX, mouseY)) {
                startResize(entry.getKey(), b, mouseX, mouseY);
                selected = grabbed;
                return;
            }
        }
        selected = null;
        for (Map.Entry<String, int[]> entry : entries) {
            int[] b = entry.getValue();
            if (contains(b, mouseX, mouseY)) {
                grabbed = entry.getKey();
                selected = grabbed;
                grabDX = mouseX - b[0];
                grabDY = mouseY - b[1];
                grabW = b[2] - b[0];
                grabH = b[3] - b[1];
                pinMirror(grabbed, b);
                return;
            }
        }
    }

    /** Mirror stores its top left, which may sit off screen while the box is clamped on it. */
    private void pinMirror(String name, int[] b) {
        if ("Mirror".equals(name)) {
            HudState.Position position = hud.getOrCreate(name, b[0], b[1]);
            position.x = b[0];
            position.y = b[1];
        }
    }

    private void startResize(String name, int[] b, int mouseX, int mouseY) {
        int size = handle(b);
        grabbed = name;
        resizing = true;
        resizeLeft = mouseX < b[0] + size;
        resizeTop = mouseY < b[1] + size;
        fixedX = resizeLeft ? b[2] : b[0];
        fixedY = resizeTop ? b[3] : b[1];
        grabDX = mouseX - (resizeLeft ? b[0] : b[2]);
        grabDY = mouseY - (resizeTop ? b[1] : b[3]);
        grabW = b[2] - b[0];
        grabH = b[3] - b[1];
        HudState.Position position = hud.getOrCreate(name, b[0], b[1]);
        scaling = "Mirror".equals(name) ? null : hud.getScale(name);
        if (scaling == null) {
            pendingW = mirror.getWidth();
            pendingH = mirror.getHeight();
            // pin the anchor to the visible corner
            position.x = b[0];
            position.y = b[1];
        } else {
            baseScale = scaling.get();
            pivotX = (position.x - b[0]) / (float) Math.max(1, grabW);
            pivotY = (position.y - b[1]) / (float) Math.max(1, grabH);
        }
    }

    /** Called every frame before the HUD renders. */
    public void updateDrag(int mouseX, int mouseY) {
        int[] b = grabbed != null ? hud.getBox(grabbed) : null;
        if (b == null) {
            return;
        }
        if (resizing) {
            int edgeX = MathHelper.clamp_int(mouseX - grabDX, 0, this.width);
            int edgeY = MathHelper.clamp_int(mouseY - grabDY, 0, this.height);
            int w = resizeLeft ? fixedX - edgeX : edgeX - fixedX;
            int h = resizeTop ? fixedY - edgeY : edgeY - fixedY;
            if (scaling == null) {
                w = pendingW = mirror.clampWidth(w);
                h = pendingH = mirror.clampHeight(h);
            } else {
                int baseW = Math.max(1, grabW), baseH = Math.max(1, grabH);
                double ratio = Math.max(w / (double) baseW, h / (double) baseH);
                // stay on screen, unless the element already spilled past it
                double fit = Math.min((resizeLeft ? fixedX : this.width - fixedX) / (double) baseW,
                        (resizeTop ? fixedY : this.height - fixedY) / (double) baseH);
                scaling.set(baseScale * Math.min(ratio, Math.max(1.0, fit)));
                double applied = scaling.get() / baseScale;
                w = (int) Math.round(grabW * applied);
                h = (int) Math.round(grabH * applied);
            }
            int left = resizeLeft ? fixedX - w : fixedX;
            int top = resizeTop ? fixedY - h : fixedY;
            HudState.Position position = hud.getOrCreate(grabbed, left, top);
            if (scaling == null) {
                position.x = left;
                position.y = top;
                mirror.previewResize(left, top, w, h);
            } else if ("ArrayList".equals(grabbed)) {
                anchorCorner(position, left, top, w, h);
            } else {
                position.x = left + Math.round(pivotX * w);
                position.y = top + Math.round(pivotY * h);
            }
            hud.report(grabbed, left, top, left + w, top + h);
            return;
        }
        int newLeft = MathHelper.clamp_int(mouseX - grabDX, 0, Math.max(0, this.width - grabW));
        int newTop = MathHelper.clamp_int(mouseY - grabDY, 0, Math.max(0, this.height - grabH));
        move(grabbed, b, newLeft, newTop, grabW, grabH);
    }

    private void move(String name, int[] b, int left, int top, int w, int h) {
        int dx = left - b[0];
        int dy = top - b[1];
        HudState.Position state = hud.getOrCreate(name, left, top);
        if ("ArrayList".equals(name)) {
            anchorCorner(state, left, top, w, h);
        } else {
            state.x += dx;
            state.y += dy;
        }
        hud.translateBox(name, dx, dy);
    }

    /** ArrayList anchors to the nearest screen corner, so store that corner. */
    private void anchorCorner(HudState.Position position, int left, int top, int w, int h) {
        position.x = left + (left + w / 2 > this.width / 2 ? w : 0);
        position.y = top + (top + h / 2 < this.height / 2 ? 0 : h);
    }

    @Override
    protected void mouseReleased(int mouseX, int mouseY, int state) {
        if (state == 0 && grabbed != null) {
            updateDrag(mouseX, mouseY);
            finishDrag();
            ColdPlay.getInstance().saveConfig();
        }
    }

    private void finishDrag() {
        if (resizing && scaling == null) {
            mirror.resize(pendingW, pendingH);
        }
        grabbed = null;
        resizing = false;
        scaling = null;
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        int step = isShiftKeyDown() ? 10 : 1;
        if (keyCode == Keyboard.KEY_ESCAPE) {
            this.mc.displayGuiScreen(ColdPlay.getInstance().getConfigManager().getGuiStyle().createScreen());
        } else if (keyCode == Keyboard.KEY_LEFT) {
            nudge(-step, 0);
        } else if (keyCode == Keyboard.KEY_RIGHT) {
            nudge(step, 0);
        } else if (keyCode == Keyboard.KEY_UP) {
            nudge(0, -step);
        } else if (keyCode == Keyboard.KEY_DOWN) {
            nudge(0, step);
        } else if (keyCode == Keyboard.KEY_R) {
            reset();
        }
    }

    /** Moves the selected element like a drag would, kept on screen. */
    void nudge(int dx, int dy) {
        int[] b = selected != null && grabbed == null ? hud.getBox(selected) : null;
        if (b == null) {
            return;
        }
        int w = b[2] - b[0], h = b[3] - b[1];
        pinMirror(selected, b);
        move(selected, b, MathHelper.clamp_int(b[0] + dx, 0, Math.max(0, this.width - w)),
                MathHelper.clamp_int(b[1] + dy, 0, Math.max(0, this.height - h)), w, h);
    }

    /** Default position and size for the selected element. */
    void reset() {
        if (selected == null || grabbed != null) {
            return;
        }
        hud.remove(selected);
        NumberSetting scale = hud.getScale(selected);
        if (scale != null) {
            scale.set(1.0);
        } else if ("Mirror".equals(selected) && mirror != null) {
            mirror.resize(Mirror.WIDTH, Mirror.HEIGHT);
        }
    }

    String getSelected() {
        return selected;
    }

    @Override
    public void onGuiClosed() {
        finishDrag();
        ColdPlay.getInstance().saveConfig();
        hud.endEditing();
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    private boolean resizable(String name) {
        return "Mirror".equals(name) ? mirror != null : hud.getScale(name) != null;
    }

    /** Corner grab size, shrunk so small boxes keep a middle to drag by. */
    private static int handle(int[] b) {
        return Math.max(2, Math.min(RESIZE_HANDLE, Math.min(b[2] - b[0], b[3] - b[1]) / 3));
    }

    private static boolean onHandle(int[] b, int x, int y) {
        int size = handle(b);
        return contains(b, x, y)
                && (x < b[0] + size || x >= b[2] - size)
                && (y < b[1] + size || y >= b[3] - size);
    }

    private static boolean contains(int[] b, int x, int y) {
        return RenderUtil.hoveredExclusive(x, y, b[0], b[1], b[2] - b[0], b[3] - b[1]);
    }
}
