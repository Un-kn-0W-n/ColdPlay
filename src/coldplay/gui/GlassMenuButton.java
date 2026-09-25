package coldplay.gui;

import coldplay.util.Animation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;

/** Smoke glass button with float geometry; the look comes from {@link GlassUi}. GUI px. */
public class GlassMenuButton extends GuiButton {

    public enum Style {
        ROW,
        CHIP,       // content packed from the left, for fixed-width grids
        CHIP_FIT,   // content centered, for auto-width and stretched chips
        PRIMARY,
        ICON
    }

    private final float x;
    private final float y;
    private final float w;
    private final float h;
    private final Icons.Icon icon;
    private final Style style;
    private final boolean danger;
    private final Animation hover = new Animation(0.0, Theme.WIPE_SPEED);

    public GlassMenuButton(int id, float x, float y, float w, float h, String label, Icons.Icon icon,
                           Style style, boolean danger) {
        super(id, Math.round(x), Math.round(y), Math.round(w), Math.round(h), label);
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;
        this.icon = icon;
        this.style = style;
        this.danger = danger;
    }

    public float right() {
        return this.x + this.w;
    }

    @Override
    public boolean mousePressed(Minecraft mc, int mouseX, int mouseY) {
        return this.enabled && this.visible && contains(mouseX, mouseY);
    }

    private boolean contains(int mouseX, int mouseY) {
        return mouseX >= x && mouseY >= y && mouseX < x + w && mouseY < y + h;
    }

    @Override
    public void drawButton(Minecraft mc, int mouseX, int mouseY) {
        if (!this.visible) {
            return;
        }
        this.hovered = this.enabled && contains(mouseX, mouseY);
        float t = (float) Theme.step(hover, this.hovered ? 1.0 : 0.0);
        switch (style) {
            case ROW:
                GlassUi.row(x, y, w, h, displayString, icon, t, enabled, danger);
                break;
            case PRIMARY:
                GlassUi.primary(x, y, w, h, displayString, icon, t, enabled);
                break;
            case ICON:
                GlassUi.chip(x, y, w, h, null, icon, t, enabled, danger, false);
                break;
            default:
                GlassUi.chip(x, y, w, h, displayString, icon, t, enabled, danger, style == Style.CHIP);
                break;
        }
    }
}
