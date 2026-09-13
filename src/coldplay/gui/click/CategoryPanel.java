package coldplay.gui.click;

import coldplay.ColdPlay;
import coldplay.gui.Theme;
import coldplay.module.Category;
import coldplay.module.Module;
import coldplay.util.Animation;
import coldplay.util.RenderUtil;
import coldplay.util.font.CustomFont;
import coldplay.util.font.Fonts;

import net.minecraft.util.MathHelper;

import java.util.ArrayList;
import java.util.List;

/** Draggable Click GUI panel listing one category's modules. */
public class CategoryPanel {

    private static final int ARROW_X = 5;
    private static final int NAME_X = 14;
    private static final int RIGHT_PAD = 8;
    private static final int MIN_WIDTH = 78;

    private final Category category;
    private final List<ModuleButton> buttons = new ArrayList<ModuleButton>();
    private final int width;

    private int x;
    private int y;
    private boolean collapsed;

    private final Animation reveal = new Animation(0.0, Theme.WIPE_SPEED); // 0 collapsed, 1 expanded
    private int visualHeight = ClickGuiScreen.HEADER_HEIGHT;

    private final WindowDrag windowDrag = new WindowDrag();

    public CategoryPanel(Category category, int x, int y) {
        this.category = category;
        this.x = x;
        this.y = y;
        for (Module module : ColdPlay.getInstance().getModuleManager().getModulesInCategory(category)) {
            buttons.add(new ModuleButton(module));
        }
        this.width = computeWidth();
    }

    private int computeWidth() {
        CustomFont font = Fonts.medium;
        if (font == null) {
            return ClickGuiScreen.FALLBACK_WIDTH;
        }
        int w = NAME_X + font.getStringWidth(category.name()) + RIGHT_PAD;
        for (ModuleButton b : buttons) {
            w = Math.max(w, ModuleButton.preferredWidth(b.getModule(), font));
        }
        return Math.max(w, MIN_WIDTH);
    }

    public void render(int mouseX, int mouseY, Module listeningModule, int scaleFactor) {
        renderContent(mouseX, mouseY, listeningModule, scaleFactor);
        renderChrome();
    }

    /** Split from renderChrome so docked panels can share one border. */
    public void renderContent(int mouseX, int mouseY, Module listeningModule, int scaleFactor) {
        CustomFont font = Fonts.medium;
        if (font == null) {
            return;
        }
        double p = Theme.step(reveal, collapsed ? 0.0 : 1.0);
        int visRowsH = (int) Math.round(visibleCount() * ClickGuiScreen.ROW_HEIGHT * p);
        visualHeight = ClickGuiScreen.HEADER_HEIGHT + visRowsH;

        ClickGuiScreen.drawWindowBase(x, y, width, visualHeight);
        int textY = y + (ClickGuiScreen.HEADER_HEIGHT - font.getHeight()) / 2;
        drawArrow(x + ARROW_X, y + (ClickGuiScreen.HEADER_HEIGHT - 5) / 2, collapsed, Theme.TEXT_DIM);
        font.drawString(category.name(), x + NAME_X, textY, Theme.TEXT);

        if (visRowsH > 0) {
            RenderUtil.beginScissor(x, y + ClickGuiScreen.HEADER_HEIGHT, width, visRowsH, scaleFactor);
            int rowY = y + ClickGuiScreen.HEADER_HEIGHT;
            for (ModuleButton button : buttons) {
                if (!ClickGuiScreen.matchesSearch(button.getModule())) {
                    continue;
                }
                button.render(x, rowY, width, mouseX, mouseY, button.getModule() == listeningModule);
                if (rowY > y + ClickGuiScreen.HEADER_HEIGHT) {
                    RenderUtil.rectBounds(x, rowY, x + width, rowY + 1, Theme.SEP);
                }
                rowY += ClickGuiScreen.ROW_HEIGHT;
            }
            RenderUtil.endScissor();
        }
    }

    /** Drawn after content so row separators do not cover the border. */
    public void renderChrome() {
        ClickGuiScreen.drawWindowFrame(x, y, width, visualHeight);
    }

    /** 5x5 pixel triangle; the font has no arrow glyph. */
    private static void drawArrow(int ax, int ay, boolean collapsed, int color) {
        for (int i = 0; i < 3; i++) {
            if (collapsed) {
                RenderUtil.rectBounds(ax + i, ay + i, ax + i + 1, ay + 5 - i, color);
            } else {
                RenderUtil.rectBounds(ax + i, ay + i, ax + 5 - i, ay + i + 1, color);
            }
        }
    }

    public boolean mouseClicked(int mouseX, int mouseY, int button, ClickGuiScreen screen) {
        if (button == 0 && RenderUtil.hovered(mouseX, mouseY, x, y, NAME_X, ClickGuiScreen.HEADER_HEIGHT)) {
            collapsed = !collapsed;
            persistAndSave();
            return true;
        }
        // Consume header clicks even when they do not start a drag.
        if (RenderUtil.hovered(mouseX, mouseY, x, y, width, ClickGuiScreen.HEADER_HEIGHT)) {
            if (button == 0) {
                windowDrag.begin(mouseX, mouseY, x, y);
            }
            return true;
        }
        if (collapsed) {
            return false;
        }
        int rowY = y + ClickGuiScreen.HEADER_HEIGHT;
        for (ModuleButton b : buttons) {
            if (!ClickGuiScreen.matchesSearch(b.getModule())) {
                continue;
            }
            if (RenderUtil.hovered(mouseX, mouseY, x, rowY, width, ClickGuiScreen.ROW_HEIGHT)) {
                boolean qmark = button == 0 && b.isOverQmark(mouseX, mouseY, x, rowY, width);
                screen.handleModuleClick(this, b.getModule(), qmark ? 1 : button);
                return true;
            }
            rowY += ClickGuiScreen.ROW_HEIGHT;
        }
        return false;
    }

    public void drag(int mouseX, int mouseY, int screenWidth, int screenHeight) {
        int[] pos = windowDrag.update(mouseX, mouseY, width, ClickGuiScreen.HEADER_HEIGHT, screenWidth, screenHeight);
        if (pos != null) {
            x = pos[0];
            y = pos[1];
        }
    }

    public void mouseReleased() {
        if (windowDrag.isDragging()) {
            windowDrag.end();
            persistAndSave();
        }
    }

    /** Updates the config without writing it to disk. */
    public void persist() {
        ColdPlay.getInstance().getConfigManager().putPanelState(category, x, y, collapsed);
    }

    private void persistAndSave() {
        persist();
        ColdPlay.getInstance().saveConfig();
    }

    public void clampToScreen(int screenWidth, int screenHeight) {
        x = MathHelper.clamp_int(x, 0, Math.max(0, screenWidth - width));
        y = MathHelper.clamp_int(y, 0, Math.max(0, screenHeight - ClickGuiScreen.HEADER_HEIGHT));
    }

    public int getWidth() {
        return width;
    }

    public int getX() {
        return x;
    }

    public int getY() {
        return y;
    }

    public boolean isCollapsed() {
        return collapsed;
    }

    /** Logical height, not the animated one. */
    public int getHeight() {
        return totalHeight();
    }

    public int getVisualHeight() {
        return visualHeight;
    }

    /** -1 when the row is hidden or absent. */
    public int rowTop(Module module) {
        if (collapsed) {
            return -1;
        }
        int rowY = y + ClickGuiScreen.HEADER_HEIGHT;
        for (ModuleButton b : buttons) {
            if (!ClickGuiScreen.matchesSearch(b.getModule())) {
                continue;
            }
            if (b.getModule() == module) {
                return rowY;
            }
            rowY += ClickGuiScreen.ROW_HEIGHT;
        }
        return -1;
    }

    public boolean contains(int mouseX, int mouseY) {
        return RenderUtil.hovered(mouseX, mouseY, x, y, width, totalHeight());
    }

    public String getTooltipAt(int mouseX, int mouseY) {
        if (collapsed) {
            return null;
        }
        int rowY = y + ClickGuiScreen.HEADER_HEIGHT;
        for (ModuleButton b : buttons) {
            if (!ClickGuiScreen.matchesSearch(b.getModule())) {
                continue;
            }
            if (RenderUtil.hovered(mouseX, mouseY, x, rowY, width, ClickGuiScreen.ROW_HEIGHT)) {
                return b.getModule().getDescription();
            }
            rowY += ClickGuiScreen.ROW_HEIGHT;
        }
        return null;
    }

    public void restoreState(int x, int y, boolean collapsed) {
        this.x = x;
        this.y = y;
        this.collapsed = collapsed;
    }

    private int totalHeight() {
        return ClickGuiScreen.HEADER_HEIGHT + (collapsed ? 0 : visibleCount() * ClickGuiScreen.ROW_HEIGHT);
    }

    private int visibleCount() {
        int n = 0;
        for (ModuleButton b : buttons) {
            if (ClickGuiScreen.matchesSearch(b.getModule())) {
                n++;
            }
        }
        return n;
    }
}
