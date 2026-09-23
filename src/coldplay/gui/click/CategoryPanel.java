package coldplay.gui.click;

import coldplay.ColdPlay;
import coldplay.gui.Glass;
import coldplay.gui.GlassShader;
import coldplay.gui.Icons;
import coldplay.gui.Theme;
import coldplay.module.Category;
import coldplay.module.Module;
import coldplay.util.Animation;
import coldplay.util.RenderUtil;
import coldplay.util.font.CustomFont;
import coldplay.util.font.FontRef;
import coldplay.util.font.Fonts;

import net.minecraft.util.MathHelper;

import java.util.ArrayList;
import java.util.List;

/** Draggable glass panel listing one category's modules; the count at the right of the header folds it. */
public class CategoryPanel {

    private static final int PAD = 9;
    private static final int MIN_WIDTH = 132;
    private static final int BOTTOM_PAD = 4;
    private static final int FOLD_W = 40; // header strip at the right that folds the panel
    private static final float CHEVRON = 6.0F;
    private static final float RADIUS = 6.0F;
    private static final FontRef COUNT = new FontRef(Fonts.GEIST_MONO, 8.25F);

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
        int w = MIN_WIDTH;
        for (ModuleButton b : buttons) {
            w = Math.max(w, ModuleButton.preferredWidth(b.getModule()));
        }
        return w;
    }

    static String label(Category category) {
        String name = category.name();
        return name.charAt(0) + name.substring(1).toLowerCase();
    }

    public void render(int mouseX, int mouseY, Module listeningModule, Module selected, int scaleFactor) {
        Skin skin = Skin.SMOKE;
        double p = Theme.step(reveal, collapsed ? 0.0 : 1.0);
        int rowsH = (int) Math.round(visibleCount() * ClickGuiScreen.ROW_HEIGHT * p);
        visualHeight = ClickGuiScreen.HEADER_HEIGHT + rowsH + (int) Math.round(BOTTOM_PAD * p);

        GlassShader.panel(x, y, width, visualHeight, RADIUS, Glass.SMOKE_PANEL);
        int header = ClickGuiScreen.HEADER_HEIGHT;
        CustomFont title = skin.title.get();
        title.drawString(label(category), x + PAD, y + (header - title.getHeight()) / 2.0F, skin.strong);
        CustomFont count = COUNT.get();
        String text = enabledCount() + "/" + buttons.size();
        float chevX = x + width - PAD - CHEVRON;
        count.drawString(text, chevX - 4 - count.getStringWidth(text), y + (header - count.getHeight()) / 2.0F,
                0x80FFFFFF);
        boolean foldHover = RenderUtil.hovered(mouseX, mouseY, x + width - FOLD_W, y, FOLD_W, header);
        Icons.draw(collapsed ? Icons.Icon.CHEVRON_RIGHT : Icons.Icon.CHEVRON_DOWN, chevX,
                y + (header - CHEVRON) / 2.0F, CHEVRON, 3.0F, foldHover ? 0xCCFFFFFF : 0x66FFFFFF);

        if (rowsH > 0) {
            GlassShader.rect(x, y + header - 0.75F, width, 0.75F, 0.0F, skin.line, skin.line);
            RenderUtil.beginScissor(x, y + header, width, rowsH, scaleFactor);
            int rowY = y + header;
            for (ModuleButton button : buttons) {
                if (!ClickGuiScreen.matchesSearch(button.getModule())) {
                    continue;
                }
                button.render(x, rowY, width, mouseX, mouseY, button.getModule() == listeningModule,
                        button.getModule() == selected);
                rowY += ClickGuiScreen.ROW_HEIGHT;
            }
            RenderUtil.endScissor();
        }
    }

    private int enabledCount() {
        int n = 0;
        for (ModuleButton b : buttons) {
            if (b.getModule().isEnabled()) {
                n++;
            }
        }
        return n;
    }

    public boolean mouseClicked(int mouseX, int mouseY, int button, ClickGuiScreen screen) {
        int header = ClickGuiScreen.HEADER_HEIGHT;
        if (button == 0 && RenderUtil.hovered(mouseX, mouseY, x + width - FOLD_W, y, FOLD_W, header)) {
            collapsed = !collapsed;
            persistAndSave();
            return true;
        }
        // Consume header clicks even when they do not start a drag.
        if (RenderUtil.hovered(mouseX, mouseY, x, y, width, header)) {
            if (button == 0) {
                windowDrag.begin(mouseX, mouseY, x, y);
            }
            return true;
        }
        if (collapsed) {
            return false;
        }
        int rowY = y + header;
        for (ModuleButton b : buttons) {
            if (!ClickGuiScreen.matchesSearch(b.getModule())) {
                continue;
            }
            if (RenderUtil.hovered(mouseX, mouseY, x, rowY, width, ClickGuiScreen.ROW_HEIGHT)) {
                screen.handleModuleClick(this, b.getModule(), button);
                return true;
            }
            rowY += ClickGuiScreen.ROW_HEIGHT;
        }
        return RenderUtil.hovered(mouseX, mouseY, x, y, width, totalHeight());
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
        return ClickGuiScreen.HEADER_HEIGHT
                + (collapsed ? 0 : visibleCount() * ClickGuiScreen.ROW_HEIGHT + BOTTOM_PAD);
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
