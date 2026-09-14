package coldplay.gui.hud;

import coldplay.ColdPlay;
import coldplay.gui.Theme;
import coldplay.gui.click.ClickGuiScreen;
import coldplay.hud.HudState;
import coldplay.module.Module;
import coldplay.module.visual.Mirror;
import coldplay.setting.NumberSetting;
import coldplay.util.RenderUtil;
import coldplay.util.font.CustomFont;
import coldplay.util.font.Fonts;

import net.minecraft.client.gui.GuiScreen;
import net.minecraft.util.MathHelper;
import org.lwjglx.input.Keyboard;

import java.util.Map;

/** Drag-to-move editor for the HUD elements; any corner of a sizable element resizes it. */
public class HudEditScreen extends GuiScreen {

    private final HudState hud;

    private String grabbed; // null when idle
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

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        Fonts.load();
        for (Map.Entry<String, int[]> entry : hud.getBoxes().entrySet()) {
            int[] b = entry.getValue();
            boolean active = entry.getKey().equals(grabbed)
                    || (grabbed == null && contains(b, mouseX, mouseY));
            // Keep the outline outside the element's own border.
            RenderUtil.outline(b[0] - 1, b[1] - 1, b[2] + 1, b[3] + 1, Theme.CONTOUR_PX,
                    active ? Theme.FROST : Theme.CONTOUR);
            if (resizable(entry.getKey())) {
                int size = handle(b);
                for (int corner = 0; corner < 4; corner++) {
                    boolean left = (corner & 1) == 0, top = (corner & 2) == 0;
                    RenderUtil.rect(left ? b[0] : b[2] - size, top ? b[1] : b[3] - 2, size, 2, Theme.FROST);
                    RenderUtil.rect(left ? b[0] : b[2] - 2, top ? b[1] : b[3] - size, 2, size, Theme.FROST);
                }
            }
        }
        CustomFont font = Fonts.medium;
        if (font != null) {
            String hint = "Drag HUD elements - Drag a corner to resize - Esc to finish";
            font.drawCenteredWithShadow(hint, this.width / 2.0F, 4, Theme.TEXT_DIM);
        }
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) {
        if (mouseButton != 0 || grabbed != null) {
            return;
        }
        // resize handles win over any overlapping element
        for (Map.Entry<String, int[]> entry : hud.getBoxes().entrySet()) {
            int[] b = entry.getValue();
            if (resizable(entry.getKey()) && onHandle(b, mouseX, mouseY)) {
                startResize(entry.getKey(), b, mouseX, mouseY);
                return;
            }
        }
        for (Map.Entry<String, int[]> entry : hud.getBoxes().entrySet()) {
            int[] b = entry.getValue();
            if (contains(b, mouseX, mouseY)) {
                grabbed = entry.getKey();
                grabDX = mouseX - b[0];
                grabDY = mouseY - b[1];
                grabW = b[2] - b[0];
                grabH = b[3] - b[1];
                if ("Mirror".equals(grabbed)) {
                    HudState.Position position = hud.getOrCreate(grabbed, b[0], b[1]);
                    position.x = b[0];
                    position.y = b[1];
                }
                return;
            }
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
        int dx = newLeft - b[0];
        int dy = newTop - b[1];
        HudState.Position state = hud.getOrCreate(grabbed, newLeft, newTop);
        if ("ArrayList".equals(grabbed)) {
            anchorCorner(state, newLeft, newTop, grabW, grabH);
        } else {
            state.x += dx;
            state.y += dy;
        }
        hud.translateBox(grabbed, dx, dy);
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
        if (keyCode == Keyboard.KEY_ESCAPE
                || keyCode == ColdPlay.getInstance().getConfigManager().getGuiOpenKey()) {
            this.mc.displayGuiScreen(new ClickGuiScreen());
        }
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
