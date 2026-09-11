package coldplay.gui.hud;

import coldplay.ColdPlay;
import coldplay.gui.Theme;
import coldplay.gui.click.ClickGuiScreen;
import coldplay.hud.HudState;
import coldplay.module.Module;
import coldplay.module.visual.Mirror;
import coldplay.util.RenderUtil;
import coldplay.util.font.CustomFont;
import coldplay.util.font.Fonts;

import net.minecraft.client.gui.GuiScreen;
import net.minecraft.util.MathHelper;
import org.lwjglx.input.Keyboard;

import java.util.Map;

/**
 * Edits the live HUD. The overlay renders before this screen, so reported boxes match this frame.
 * Drags translate the stored anchor by the box delta, regardless of each element's anchor semantics.
 */
public class HudEditScreen extends GuiScreen {

    private final HudState hud;

    private String grabbed;   // element being dragged, null when idle
    private int grabDX;       // mouse offset into the grabbed box, so it doesn't snap to the cursor
    private int grabDY;
    private int grabW;        // grabbed box size at mouse-down, for screen clamping
    private int grabH;
    private static final int RESIZE_HANDLE = 8;
    private Mirror mirror;
    private boolean resizing;
    private int pendingW;     // previewed mirror size, applied once on release
    private int pendingH;
    private boolean resizeLeft;
    private boolean resizeTop;
    private int fixedX;       // opposite corner stays fixed throughout a resize
    private int fixedY;

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
        hud.rebase(this.width, this.height); // drags below write anchors in this screen space
        hud.beginEditing();
        for (Module module : ColdPlay.getInstance().getModuleManager().getModules()) {
            if (module instanceof Mirror) mirror = (Mirror) module;
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
            if (mirror != null && "Mirror".equals(entry.getKey())) {
                for (int corner = 0; corner < 4; corner++) {
                    boolean left = (corner & 1) == 0, top = (corner & 2) == 0;
                    RenderUtil.rect(left ? b[0] : b[2] - RESIZE_HANDLE,
                            top ? b[1] : b[3] - 2, RESIZE_HANDLE, 2, Theme.FROST);
                    RenderUtil.rect(left ? b[0] : b[2] - 2,
                            top ? b[1] : b[3] - RESIZE_HANDLE, 2, RESIZE_HANDLE, Theme.FROST);
                }
            }
        }
        CustomFont font = Fonts.medium;
        if (font != null) {
            String hint = "Drag HUD elements - Any Mirror corner to resize - Esc to finish";
            font.drawCenteredWithShadow(hint, this.width / 2.0F, 4, Theme.TEXT_DIM);
        }
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) {
        if (mouseButton != 0 || grabbed != null) {
            return;
        }
        // The handle wins over whatever else is parked on it, whatever order the boxes iterate in.
        int[] m = mirror != null ? hud.getBox("Mirror") : null;
        if (m != null && contains(m, mouseX, mouseY)
                && (mouseX < m[0] + RESIZE_HANDLE || mouseX >= m[2] - RESIZE_HANDLE)
                && (mouseY < m[1] + RESIZE_HANDLE || mouseY >= m[3] - RESIZE_HANDLE)) {
            grabbed = "Mirror";
            resizing = true;
            resizeLeft = mouseX < m[0] + RESIZE_HANDLE;
            resizeTop = mouseY < m[1] + RESIZE_HANDLE;
            fixedX = resizeLeft ? m[2] : m[0];
            fixedY = resizeTop ? m[3] : m[1];
            grabDX = mouseX - (resizeLeft ? m[0] : m[2]);
            grabDY = mouseY - (resizeTop ? m[1] : m[3]);
            pendingW = mirror.getWidth();
            pendingH = mirror.getHeight();
            // A screen-clamped anchor must stay at the visible corner while resizing.
            HudState.Position position = hud.getOrCreate(grabbed, m[0], m[1]);
            position.x = m[0];
            position.y = m[1];
            return;
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

    /** Called before the HUD renders, at frame rate instead of the 20 Hz input-event tick. */
    public void updateDrag(int mouseX, int mouseY) {
        int[] b = grabbed != null ? hud.getBox(grabbed) : null;
        if (b == null) {
            return;
        }
        if (resizing) {
            int edgeX = MathHelper.clamp_int(mouseX - grabDX, 0, this.width);
            int edgeY = MathHelper.clamp_int(mouseY - grabDY, 0, this.height);
            pendingW = mirror.clampWidth(resizeLeft ? fixedX - edgeX : edgeX - fixedX);
            pendingH = mirror.clampHeight(resizeTop ? fixedY - edgeY : edgeY - fixedY);
            int left = resizeLeft ? fixedX - pendingW : fixedX;
            int top = resizeTop ? fixedY - pendingH : fixedY;
            HudState.Position position = hud.getOrCreate(grabbed, left, top);
            position.x = left;
            position.y = top;
            mirror.previewResize(left, top, pendingW, pendingH);
            hud.report(grabbed, left, top, left + pendingW, top + pendingH);
            return;
        }
        int newLeft = MathHelper.clamp_int(mouseX - grabDX, 0, Math.max(0, this.width - grabW));
        int newTop = MathHelper.clamp_int(mouseY - grabDY, 0, Math.max(0, this.height - grabH));
        int dx = newLeft - b[0];
        int dy = newTop - b[1];
        // Reporting the box already created this anchor; the fallback coordinates are unused.
        HudState.Position state = hud.getOrCreate(grabbed, newLeft, newTop);
        if ("ArrayList".equals(grabbed)) {
            // Its renderer pins rows to the nearest screen half. Choose that corner from the
            // desired box, or crossing the midpoint flips the box back and forth each frame.
            state.x = newLeft + (newLeft + grabW / 2 > this.width / 2 ? grabW : 0);
            state.y = newTop + (newTop + grabH / 2 < this.height / 2 ? 0 : grabH);
        } else {
            state.x += dx;
            state.y += dy;
        }
        // Keep the cached box in sync until the overlay reports its next bounds.
        hud.translateBox(grabbed, dx, dy);
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
        if (resizing) mirror.resize(pendingW, pendingH);
        grabbed = null;
        resizing = false;
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
        finishDrag(); // also commits an Esc or screen-size change mid-drag
        ColdPlay.getInstance().saveConfig();
        hud.endEditing();
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    private static boolean contains(int[] b, int x, int y) {
        return RenderUtil.hoveredExclusive(x, y, b[0], b[1], b[2] - b[0], b[3] - b[1]);
    }
}
