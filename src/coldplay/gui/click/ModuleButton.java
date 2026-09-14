package coldplay.gui.click;

import coldplay.gui.Theme;
import coldplay.module.Module;
import coldplay.util.RenderUtil;
import coldplay.util.font.CustomFont;
import coldplay.util.font.Fonts;
import net.minecraft.client.settings.GameSettings;
import org.lwjglx.input.Keyboard;

/** One module row inside a CategoryPanel. */
public class ModuleButton {

    private static final int NAME_X = 6;
    private static final int QMARK_SIZE = 12;
    private static final int QMARK_MARGIN = 4;
    private static final int NAME_GAP = 8;

    private final Module module;

    public ModuleButton(Module module) {
        this.module = module;
    }

    public Module getModule() {
        return module;
    }

    public void render(int x, int rowY, int width, int mouseX, int mouseY, boolean listening) {
        CustomFont font = Fonts.medium;
        boolean hovered = RenderUtil.hovered(mouseX, mouseY, x, rowY, width, ClickGuiScreen.ROW_HEIGHT);
        boolean on = module.isEnabled();

        if (hovered) {
            RenderUtil.rect(x, rowY, width, ClickGuiScreen.ROW_HEIGHT, Theme.HOVER_LIFT);
        }
        if (on) {
            RenderUtil.rect(x + Theme.CONTOUR_PX, rowY, Theme.TICK_PX, ClickGuiScreen.ROW_HEIGHT, Theme.FROST);
        }

        int textY = rowY + (ClickGuiScreen.ROW_HEIGHT - font.getHeight()) / 2;
        font.drawString(module.getName(), x + NAME_X, textY, on ? Theme.TEXT : Theme.TEXT_DIM);

        String glyph = listening ? "_" : glyph();
        int qw = qmarkWidth(font, glyph);
        int qx = x + width - QMARK_MARGIN - qw;
        int qy = qmarkTop(rowY);
        boolean qHover = RenderUtil.hovered(mouseX, mouseY, qx, qy, qw, QMARK_SIZE);
        RenderUtil.rect(qx, qy, qw, QMARK_SIZE, listening ? Theme.lighten(Theme.WELL, 70) : Theme.WELL);
        if (qHover) {
            RenderUtil.outline(qx, qy, qx + qw, qy + QMARK_SIZE, 1, Theme.CONTOUR);
        }
        font.drawCenteredInRect(glyph, qx, qy, qw, QMARK_SIZE,
                qHover ? Theme.TEXT : Theme.TEXT_DIM);
    }

    private String glyph() {
        int key = module.getKeyBind();
        if (key == Keyboard.KEY_NONE) {
            return "?";
        }
        String name = GameSettings.getKeyDisplayString(key);
        return name == null || name.isEmpty() ? "?" : name;
    }

    private static int qmarkWidth(CustomFont font, String glyph) {
        return Math.max(QMARK_SIZE, font.getStringWidth(glyph) + 4);
    }

    static int qmarkTop(int rowY) {
        return rowY + (ClickGuiScreen.ROW_HEIGHT - QMARK_SIZE) / 2;
    }

    boolean isOverQmark(int mouseX, int mouseY, int rowX, int rowY, int width) {
        int qw = qmarkWidth(Fonts.medium, glyph());
        return RenderUtil.hovered(mouseX, mouseY, rowX + width - QMARK_MARGIN - qw, qmarkTop(rowY), qw, QMARK_SIZE);
    }

    static int preferredWidth(Module module, CustomFont font) {
        // TODO: long key names can overlap the module name
        return NAME_X + font.getStringWidth(module.getName()) + NAME_GAP + QMARK_SIZE + QMARK_MARGIN;
    }
}
