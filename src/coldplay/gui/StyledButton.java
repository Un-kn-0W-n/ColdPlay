package coldplay.gui;

import coldplay.util.RenderUtil;
import coldplay.util.font.CustomFont;

/** Shared custom-font button chrome for ColdPlay screens and popup windows. */
public final class StyledButton {
    private StyledButton() {
    }

    public static boolean draw(CustomFont font, String label,
                               int x, int y, int width, int height,
                               int mouseX, int mouseY, boolean enabled,
                               int background, int text) {
        boolean hover = enabled && RenderUtil.hovered(mouseX, mouseY, x, y, width, height);

        RenderUtil.rect(x, y, width, height, background);
        RenderUtil.outline(x, y, x + width, y + height, 1, hover ? Theme.CONTOUR : Theme.SEP);

        if (font != null) {
            font.drawCenteredInRect(label, x, y, width, height,
                    enabled ? text : Theme.TEXT_MUTE);
        }
        return hover;
    }
}
