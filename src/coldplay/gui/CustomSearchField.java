package coldplay.gui;

import coldplay.util.RenderUtil;
import coldplay.util.font.CustomFont;

/** Draws the custom-font search and text fields used by the GUI screens. */
public final class CustomSearchField {
    private CustomSearchField() {
    }

    public static void draw(CustomFont font, int x, int y, int width, int height,
                            String value, boolean focused, String placeholder, int cursorCounter) {
        draw(font, x, y, width, height, value, focused, placeholder, cursorCounter, false);
    }

    public static void draw(CustomFont font, int x, int y, int width, int height,
                            String value, boolean focused, String placeholder, int cursorCounter,
                            boolean masked) {
        RenderUtil.drawBorderedRect(x, y, x + width, y + height, Theme.WELL,
                focused ? Theme.FROST : Theme.SEP);

        if (font == null) {
            return;
        }

        String safeValue = value == null ? "" : value;
        boolean showPlaceholder = safeValue.isEmpty() && !focused;
        String shown = safeValue;
        if (masked) {
            char[] stars = new char[safeValue.length()];
            java.util.Arrays.fill(stars, '*');
            shown = new String(stars);
        }
        shown = tail(font, shown, width - 6);
        font.drawString(showPlaceholder ? placeholder : shown, x + 3,
                y + (height - font.getHeight()) / 2.0F,
                showPlaceholder ? Theme.TEXT_DIM : Theme.TEXT);

        if (focused && cursorCounter / 6 % 2 == 0) {
            int caretX = x + 3 + font.getStringWidth(shown);
            RenderUtil.rect(caretX, y + 2, 1, height - 4, Theme.FROST);
        }
    }

    /** Longest suffix that fits, so the caret stays visible. */
    private static String tail(CustomFont font, String value, int maxWidth) {
        int used = 0;
        for (int i = value.length(); i > 0; i--) {
            used += font.getStringWidth(value.substring(i - 1, i));
            if (used > maxWidth) {
                return value.substring(i);
            }
        }
        return value;
    }
}
