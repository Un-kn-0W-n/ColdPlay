package coldplay.gui.click;

import coldplay.ColdPlay;
import coldplay.config.ConfigManager;
import coldplay.gui.CustomTextInput;
import coldplay.gui.Glass;
import coldplay.gui.GlassShader;
import coldplay.gui.GuiStyle;
import coldplay.gui.Icons;
import coldplay.gui.hud.HudEditScreen;
import coldplay.module.Category;
import coldplay.module.Module;
import coldplay.util.RenderUtil;
import coldplay.util.font.CustomFont;
import coldplay.util.font.Fonts;

import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import org.lwjglx.input.Keyboard;
import org.lwjglx.input.Mouse;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Smoke Click GUI: one glass panel per category, settings docked beside the clicked module. */
public class ClickGuiScreen extends GuiScreen {

    public static final int HEADER_HEIGHT = 24;
    public static final int ROW_HEIGHT = 19;
    private static final int MARGIN = 18;
    private static final int PANEL_GAP = 12;
    private static final int BUTTON_H = 22;
    private static final int SEARCH_W = 150;
    private static final int FLYOUT_W = 104;
    private static final int FLYOUT_ROW = 20;
    private static final float RADIUS = 6.0F;
    private static final int DIM = 0x38000000;
    // top to bottom; Config sits nearest the button
    private static final String[] FLYOUT_ROWS = {"Milk GUI", "Edit GUI", "Config"};
    private static final Icons.Icon[] FLYOUT_ICONS = {Icons.Icon.SNOWFLAKE, Icons.Icon.LAYOUT, Icons.Icon.SLIDERS};

    private final List<CategoryPanel> panels = new ArrayList<CategoryPanel>();
    private SettingsPanel settingsPanel;
    private Module listeningModule;
    private boolean settingsFlyoutOpen;
    private ConfigWindow configWindow;

    private static String search = "";
    private boolean searchFocused;
    private int searchCursorCounter;

    @Override
    public void initGui() {
        panels.clear();
        settingsPanel = null;
        listeningModule = null;
        settingsFlyoutOpen = false;
        configWindow = null;
        search = "";
        searchFocused = false;

        Fonts.load(); // panel widths depend on font metrics

        ConfigManager config = ColdPlay.getInstance().getConfigManager();
        config.reflowPanels(this.width, this.height);
        int cursorX = MARGIN;
        for (Category category : Category.values()) {
            CategoryPanel panel = new CategoryPanel(category, cursorX, MARGIN);
            ConfigManager.PanelState state = config.getPanelState(category);
            if (state != null) {
                panel.restoreState(state.x, state.y, state.collapsed);
            }
            panel.clampToScreen(this.width, this.height);
            panels.add(panel);
            cursorX += panel.getWidth() + PANEL_GAP;
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        Fonts.load();
        updateActiveDrags(mouseX, mouseY);
        int scaleFactor = new ScaledResolution(this.mc).getScaleFactor();
        RenderUtil.rectBounds(0, 0, this.width, this.height, DIM);
        // the docked pair renders last, on top
        CategoryPanel dockHost = settingsPanel != null ? settingsPanel.getDockHost() : null;
        Module selected = settingsPanel != null ? settingsPanel.getModule() : null;
        for (CategoryPanel panel : panels) {
            if (panel != dockHost) {
                panel.render(mouseX, mouseY, listeningModule, selected, scaleFactor);
            }
        }
        if (dockHost != null) {
            dockHost.render(mouseX, mouseY, listeningModule, selected, scaleFactor);
        }
        if (settingsPanel != null) {
            settingsPanel.render(mouseX, mouseY, scaleFactor);
            // overlays go above the cell fills
            settingsPanel.renderItems(mouseX, mouseY);
            settingsPanel.renderLayoutWindow(mouseX, mouseY);
            settingsPanel.renderColorPicker(mouseX, mouseY);
        }
        drawSettingsButton(mouseX, mouseY);
        drawSearchBox();
        if (configWindow != null) {
            configWindow.render(mouseX, mouseY);
            return;
        }
        String tip = resolveTooltip(mouseX, mouseY);
        if (tip != null && !tip.isEmpty()) {
            Widgets.tooltip(Skin.SMOKE, tip, mouseX, mouseY, this.width, this.height);
        }
    }

    // hosts move before their docked settings panel
    private void updateActiveDrags(int mouseX, int mouseY) {
        for (CategoryPanel panel : panels) {
            panel.drag(mouseX, mouseY, this.width, this.height);
        }
        if (settingsPanel != null) {
            settingsPanel.drag(mouseX, mouseY, this.width, this.height);
            if (!settingsPanel.layoutDocked(this.width, this.height)) {
                settingsPanel = null;
            }
        }
    }

    private int buttonWidth() {
        return Math.round(9 + 10.5F + 6 + Skin.SMOKE.label.get().getStringWidth("Settings") + 9);
    }

    private int buttonY() {
        return this.height - MARGIN - BUTTON_H;
    }

    private int flyoutY() {
        return buttonY() - 4 - FLYOUT_ROWS.length * FLYOUT_ROW - 6;
    }

    private void drawSettingsButton(int mouseX, int mouseY) {
        Skin skin = Skin.SMOKE;
        CustomFont font = skin.label.get();
        int by = buttonY();
        int bw = buttonWidth();
        GlassShader.panel(MARGIN, by, bw, BUTTON_H, RADIUS, Glass.SMOKE_PANEL);
        if (settingsFlyoutOpen || RenderUtil.hovered(mouseX, mouseY, MARGIN, by, bw, BUTTON_H)) {
            GlassShader.rect(MARGIN, by, bw, BUTTON_H, RADIUS, 0x0FFFFFFF, 0x0FFFFFFF);
        }
        Icons.draw(Icons.Icon.GEAR, MARGIN + 9, by + (BUTTON_H - 10.5F) / 2.0F, 10.5F, 2.2F, 0xBFFFFFFF);
        font.drawString("Settings", MARGIN + 9 + 10.5F + 6, by + (BUTTON_H - font.getHeight()) / 2.0F, skin.text);
        if (!settingsFlyoutOpen) {
            return;
        }
        int fy = flyoutY();
        GlassShader.panel(MARGIN, fy, FLYOUT_W, FLYOUT_ROWS.length * FLYOUT_ROW + 6, RADIUS, Glass.SMOKE_PANEL);
        for (int i = 0; i < FLYOUT_ROWS.length; i++) {
            int ry = fy + 3 + i * FLYOUT_ROW;
            if (RenderUtil.hovered(mouseX, mouseY, MARGIN, ry, FLYOUT_W, FLYOUT_ROW)) {
                GlassShader.rect(MARGIN + 3, ry, FLYOUT_W - 6, FLYOUT_ROW, 4.5F, 0x14FFFFFF, 0x14FFFFFF);
            }
            Icons.draw(FLYOUT_ICONS[i], MARGIN + 9, ry + (FLYOUT_ROW - 9.75F) / 2.0F, 9.75F, 2.2F, 0xBFFFFFFF);
            font.drawString(FLYOUT_ROWS[i], MARGIN + 9 + 9.75F + 6, ry + (FLYOUT_ROW - font.getHeight()) / 2.0F,
                    skin.text);
        }
    }

    public static boolean matchesSearch(Module module) {
        return search.isEmpty() || module.getName().toLowerCase().contains(search.toLowerCase());
    }

    private int searchX() {
        return this.width - MARGIN - SEARCH_W;
    }

    private void drawSearchBox() {
        searchCursorCounter++;
        int sx = searchX();
        int sy = buttonY();
        GlassShader.panel(sx, sy, SEARCH_W, BUTTON_H, RADIUS, Glass.SMOKE_PANEL);
        if (searchFocused) {
            GlassShader.stroke(sx, sy, SEARCH_W, BUTTON_H, RADIUS, 0x8084D2E3);
        }
        Icons.draw(Icons.Icon.SEARCH, sx + 7.5F, sy + (BUTTON_H - 9.75F) / 2.0F, 9.75F, 2.4F, 0x99FFFFFF);
        Widgets.text(Skin.SMOKE.label.get(), sx + 7.5F + 9.75F + 6, sy, BUTTON_H, search, searchFocused,
                "Search...", searchCursorCounter, Skin.SMOKE);
    }

    // the topmost containing panel wins even if it has no tooltip
    private String resolveTooltip(int mouseX, int mouseY) {
        if (settingsPanel != null && settingsPanel.contains(mouseX, mouseY)) {
            return settingsPanel.getTooltipAt(mouseX, mouseY);
        }
        CategoryPanel dockHost = settingsPanel != null ? settingsPanel.getDockHost() : null;
        if (dockHost != null && dockHost.contains(mouseX, mouseY)) {
            return dockHost.getTooltipAt(mouseX, mouseY);
        }
        for (int i = panels.size() - 1; i >= 0; i--) {
            CategoryPanel panel = panels.get(i);
            if (panel != dockHost && panel.contains(mouseX, mouseY)) {
                return panel.getTooltipAt(mouseX, mouseY);
            }
        }
        return null;
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        if (mouseButton != 2) {
            listeningModule = null;
        }
        searchFocused = false;
        // a click that dismisses the modal is swallowed
        if (configWindow != null) {
            if (!configWindow.mouseClicked(mouseX, mouseY, mouseButton)) {
                configWindow = null;
            }
            return;
        }
        if (settingsPanel != null && settingsPanel.mouseClicked(mouseX, mouseY, mouseButton)) {
            return;
        }
        if (mouseButton == 0 && RenderUtil.hovered(mouseX, mouseY, searchX(), buttonY(), SEARCH_W, BUTTON_H)) {
            searchFocused = true;
            searchCursorCounter = 0;
            return;
        }
        if (mouseButton == 0) {
            if (RenderUtil.hovered(mouseX, mouseY, MARGIN, buttonY(), buttonWidth(), BUTTON_H)) {
                settingsFlyoutOpen = !settingsFlyoutOpen;
                return;
            }
            if (settingsFlyoutOpen && clickFlyout(mouseX, mouseY)) {
                return;
            }
        }
        // hit-test in draw order, docked pair first
        CategoryPanel dockHost = settingsPanel != null ? settingsPanel.getDockHost() : null;
        if (dockHost != null && dockHost.mouseClicked(mouseX, mouseY, mouseButton, this)) {
            raisePanel(dockHost);
            if (settingsPanel != null && settingsPanel.isDockedTo(dockHost) && dockHost.isCollapsed()) {
                settingsPanel = null;
            }
            return;
        }
        for (int i = panels.size() - 1; i >= 0; i--) {
            CategoryPanel panel = panels.get(i);
            if (panel == dockHost) {
                continue;
            }
            if (panel.mouseClicked(mouseX, mouseY, mouseButton, this)) {
                if (i != panels.size() - 1) {
                    panels.remove(i);
                    panels.add(panel);
                }
                return;
            }
        }
        if (mouseButton == 0) {
            settingsPanel = null;
            listeningModule = null;
            settingsFlyoutOpen = false;
        }
    }

    private boolean clickFlyout(int mouseX, int mouseY) {
        int fy = flyoutY();
        for (int i = 0; i < FLYOUT_ROWS.length; i++) {
            if (!RenderUtil.hovered(mouseX, mouseY, MARGIN, fy + 3 + i * FLYOUT_ROW, FLYOUT_W, FLYOUT_ROW)) {
                continue;
            }
            settingsFlyoutOpen = false;
            if (i == 0) {
                ColdPlay.getInstance().getConfigManager().setGuiStyle(GuiStyle.MILK);
                this.mc.displayGuiScreen(GuiStyle.MILK.createScreen());
            } else if (i == 1) {
                this.mc.displayGuiScreen(new HudEditScreen());
            } else {
                configWindow = new ConfigWindow(this::closeConfigWindow, this.width, this.height, Skin.SMOKE);
            }
            return true;
        }
        return false;
    }

    @Override
    protected void mouseClickMove(int mouseX, int mouseY, int clickedMouseButton, long timeSinceLastClick) {
        updateActiveDrags(mouseX, mouseY);
    }

    @Override
    protected void mouseReleased(int mouseX, int mouseY, int state) {
        if (settingsPanel != null) {
            settingsPanel.mouseReleased();
        }
        for (CategoryPanel panel : panels) {
            panel.mouseReleased();
        }
    }

    @Override
    public void handleMouseInput() throws IOException {
        // event coordinates, not the last frame's cursor
        int mouseX = Mouse.getEventX() * this.width / this.mc.displayWidth;
        int mouseY = this.height - Mouse.getEventY() * this.height / this.mc.displayHeight - 1;
        int wheel = Mouse.getEventDWheel();
        super.handleMouseInput();
        if (wheel == 0) {
            return;
        }
        if (configWindow != null && configWindow.contains(mouseX, mouseY)) {
            configWindow.scroll(wheel, mouseX, mouseY);
        } else if (settingsPanel != null) {
            settingsPanel.scroll(wheel, mouseX, mouseY);
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (configWindow != null) {
            if (configWindow.isFieldFocused()) {
                configWindow.charTyped(typedChar, keyCode);
                return;
            }
            if (keyCode == Keyboard.KEY_ESCAPE) {
                configWindow = null;
                return;
            }
        }
        if (settingsPanel != null && settingsPanel.isSearching()) {
            settingsPanel.charTyped(typedChar, keyCode);
            return;
        }
        if (searchFocused) {
            CustomTextInput.EditResult edit = CustomTextInput.edit(search, typedChar, keyCode, -1);
            search = edit.getValue();
            searchFocused = edit.isFocused();
            return;
        }
        if (listeningModule != null) {
            // InputManager eats the GUI-open key before module binds see it
            int guiOpenKey = ColdPlay.getInstance().getConfigManager().getGuiOpenKey();
            boolean unusable = keyCode == Keyboard.KEY_ESCAPE || keyCode == guiOpenKey;
            listeningModule.setKeyBind(unusable ? Keyboard.KEY_NONE : keyCode);
            listeningModule = null;
            ColdPlay.getInstance().saveConfig();
            return;
        }
        if (keyCode == Keyboard.KEY_ESCAPE) {
            if (settingsPanel != null) {
                if (settingsPanel.closeOverlay()) {
                    return;
                }
                settingsPanel = null;
                return;
            }
            this.mc.displayGuiScreen(null);
            return;
        }
        if (keyCode == ColdPlay.getInstance().getConfigManager().getGuiOpenKey()) {
            this.mc.displayGuiScreen(null);
        }
    }

    @Override
    public void onGuiClosed() {
        for (CategoryPanel panel : panels) {
            panel.persist();
        }
        ColdPlay.getInstance().saveConfig();
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    private void raisePanel(CategoryPanel panel) {
        if (panels.remove(panel)) {
            panels.add(panel);
        }
    }

    /** Mouse buttons: 0 = toggle, 1 = settings, 2 = keybind. */
    public void handleModuleClick(CategoryPanel host, Module module, int button) {
        if (button == 0) {
            ColdPlay.getInstance().getModuleManager().toggle(module);
            ColdPlay.getInstance().saveConfig();
        } else if (button == 1) {
            if (settingsPanel != null && settingsPanel.getModule() == module) {
                settingsPanel = null;
            } else {
                settingsPanel = new SettingsPanel(this, this::closeSettings, module, host, this.width, this.height);
            }
        } else if (button == 2) {
            listeningModule = (listeningModule == module) ? null : module;
        }
    }

    public void closeSettings() {
        settingsPanel = null;
    }

    public void closeConfigWindow() {
        configWindow = null;
    }
}
