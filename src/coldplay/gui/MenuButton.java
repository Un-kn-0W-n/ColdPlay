package coldplay.gui;

import coldplay.util.font.CustomFont;
import coldplay.util.font.Fonts;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;

/** ColdPlay menu button styling. */
public final class MenuButton extends GuiButton {

    private final boolean danger;

    public MenuButton(int id, int x, int y, int width, int height, String label) {
        this(id, x, y, width, height, label, false);
    }

    public MenuButton(int id, int x, int y, int width, int height, String label, boolean danger) {
        super(id, x, y, width, height, label);
        this.danger = danger;
    }

    @Override
    public void drawButton(Minecraft mc, int mouseX, int mouseY) {
        if (!this.visible) {
            return;
        }
        Fonts.load();
        final CustomFont font = Fonts.medium;
        // CustomFont supports ASCII 32-126; use the vanilla font for localized or oversized labels.
        final boolean custom = font != null
                && this.displayString.chars().allMatch(c -> c >= 32 && c <= 126)
                && font.getStringWidth(this.displayString) <= this.width - 8;
        this.hovered = StyledButton.draw(custom ? font : null, this.displayString,
                this.xPosition, this.yPosition, this.width, this.height,
                mouseX, mouseY, this.enabled, Theme.WELL,
                this.danger ? Theme.DANGER : Theme.TEXT);
        if (!custom) {
            this.drawCenteredString(mc.fontRendererObj,
                    mc.fontRendererObj.trimStringToWidth(this.displayString, this.width - 8),
                    this.xPosition + this.width / 2, this.yPosition + (this.height - 8) / 2,
                    !this.enabled ? Theme.TEXT_MUTE : this.danger ? Theme.DANGER : Theme.TEXT);
        }
    }
}
