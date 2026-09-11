package coldplay.gui.click;

import coldplay.ColdPlay;
import coldplay.config.ConfigManager;
import coldplay.gui.Theme;
import coldplay.gui.CustomSearchField;
import coldplay.gui.CustomTextInput;
import coldplay.gui.hud.HudEditScreen;
import coldplay.module.Category;
import coldplay.module.Module;
import coldplay.util.RenderUtil;
import coldplay.util.font.CustomFont;
import coldplay.util.font.Fonts;

import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.util.MathHelper;
import org.lwjglx.input.Keyboard;
import org.lwjglx.input.Mouse;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class ClickGuiScreen extends GuiScreen {

    public static final int HEADER_HEIGHT = 17;
    public static final int ROW_HEIGHT = 16;
    public static final int FALLBACK_WIDTH = 116; // used only if the font failed to load
    private static final int MARGIN = 6;
    private static final int SETTINGS_BTN_WIDTH = 70;
    private static final int SEARCH_BOX_WIDTH = 90;
    /** Settings flyout rows, bottom-up over the button. Click handling in mouseClicked must match. */
    private static final String[] FLYOUT_ROWS = {"Config", "Edit GUI"};

    public static final int CLOSE_W = 13;

    private static final int TOOLTIP_PAD = 5;
    private static final int TOOLTIP_MAX_WIDTH = 160;
    private static final int TOOLTIP_LINE_GAP = 1;

    private final List<CategoryPanel> panels = new ArrayList<CategoryPanel>();
    private SettingsPanel settingsPanel;
    private Module listeningModule;
    private boolean settingsFlyoutOpen;
    private ConfigWindow configWindow;

    // CategoryPanel reads the shared search query without a screen reference.
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

        Fonts.load(); // The GL context is live here; panel widths depend on font metrics.

        ConfigManager config = ColdPlay.getInstance().getConfigManager();
        // Reflow saved GUI-pixel anchors after a scale change while the screen was closed.
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
            cursorX += panel.getWidth() + MARGIN;
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        Fonts.load();
        updateActiveDrags(mouseX, mouseY);
        int scaleFactor = new ScaledResolution(this.mc).getScaleFactor();
        RenderUtil.rectBounds(0, 0, this.width, this.height, Theme.DIM_SCREEN);
        // Finish each panel before the next to preserve z-order; the docked pair renders on top.
        CategoryPanel dockHost = settingsPanel != null ? settingsPanel.getDockHost() : null;
        for (CategoryPanel panel : panels) {
            if (panel != dockHost) {
                panel.render(mouseX, mouseY, listeningModule, scaleFactor);
            }
        }
        if (settingsPanel != null) {
            if (dockHost != null) {
                renderDockedPair(dockHost, mouseX, mouseY, scaleFactor);
            } else {
                settingsPanel.render(mouseX, mouseY, scaleFactor);
            }
            // Textured icons and drag overlays must render above the cell fills.
            settingsPanel.renderItems(mouseX, mouseY);
            settingsPanel.renderDragGhost();
            settingsPanel.renderColorPicker(mouseX, mouseY);
        }
        drawSettingsButton(mouseX, mouseY);
        drawSearchBox(mouseX, mouseY);
        // Tooltips overlay panels; the modal config window overlays tooltips.
        String tip = resolveTooltip(mouseX, mouseY);
        if (tip != null && !tip.isEmpty()) {
            drawTooltip(tip, mouseX, mouseY);
        }
        if (configWindow != null) {
            configWindow.render(mouseX, mouseY);
        }
    }

    // Use the current frame's cursor, moving hosts before their docked settings panels.
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

    private void renderDockedPair(CategoryPanel host, int mouseX, int mouseY, int scaleFactor) {
        host.renderContent(mouseX, mouseY, listeningModule, scaleFactor);
        settingsPanel.renderContent(mouseX, mouseY, scaleFactor);
        if (!settingsPanel.isFlush()) {
            host.renderChrome();
            settingsPanel.renderChrome();
            return;
        }
        drawHeaderRule(host.getX(), host.getY(), host.getWidth());
        if (settingsPanel.getEffectiveWidth() > 0) {
            drawHeaderRule(settingsPanel.getEffectiveX(), settingsPanel.getY(),
                    settingsPanel.getEffectiveWidth());
        }
        drawDockedContour(host.getX(), host.getY(), host.getWidth(), host.getVisualHeight(),
                settingsPanel.getEffectiveX(), settingsPanel.getY(),
                settingsPanel.getEffectiveWidth(), settingsPanel.getHeight(),
                settingsPanel.isDockRight(), Theme.CONTOUR);
        if (settingsPanel.getEffectiveWidth() > 0) {
            int seam = settingsPanel.isDockRight() ? host.getX() + host.getWidth() : host.getX();
            RenderUtil.rect(seam - 1, settingsPanel.getY(), Theme.TICK_PX, ROW_HEIGHT, Theme.FROST);
        }
    }

    // Panels meet flush with sy > cy. Keep the contour inside the fills and overlap corners
    // by one pixel to avoid gaps; never draw the shared seam.
    private static void drawDockedContour(int cx, int cy, int cw, int ch,
                                          int sx, int sy, int sw, int sh,
                                          boolean rightDock, int color) {
        int cRight = cx + cw;
        int cBot = cy + ch;
        if (sw <= 0) {
            RenderUtil.outline(cx, cy, cRight, cBot, 1, color);
            return;
        }
        int sRight = sx + sw;
        int sBot = sy + sh;

        RenderUtil.hLine(cx, cRight, cy, color);
        if (rightDock) {
            RenderUtil.vLine(cx, cy, cBot, color);
            RenderUtil.vLine(cRight - 1, cy, sy, color);
            RenderUtil.hLine(cRight - 1, sRight, sy, color);
            RenderUtil.vLine(sRight - 1, sy, sBot, color);
            if (sBot < cBot) {
                RenderUtil.hLine(cRight - 1, sRight, sBot - 1, color);
                RenderUtil.vLine(cRight - 1, sBot, cBot, color);
                RenderUtil.hLine(cx, cRight, cBot - 1, color);
            } else if (sBot > cBot) {
                RenderUtil.hLine(cx, cRight, cBot - 1, color);
                RenderUtil.vLine(sx, cBot - 1, sBot, color);
                RenderUtil.hLine(sx, sRight, sBot - 1, color);
            } else {
                RenderUtil.hLine(cx, sRight, cBot - 1, color);
            }
        } else {
            RenderUtil.vLine(cRight - 1, cy, cBot, color);
            RenderUtil.vLine(cx, cy, sy, color);
            RenderUtil.hLine(sx, cx + 1, sy, color);
            RenderUtil.vLine(sx, sy, sBot, color);
            if (sBot < cBot) {
                RenderUtil.hLine(sx, cx + 1, sBot - 1, color);
                RenderUtil.vLine(cx, sBot, cBot, color);
                RenderUtil.hLine(cx, cRight, cBot - 1, color);
            } else if (sBot > cBot) {
                RenderUtil.hLine(cx, cRight, cBot - 1, color);
                RenderUtil.vLine(cx - 1, cBot - 1, sBot, color);
                RenderUtil.hLine(sx, cx, sBot - 1, color);
            } else {
                RenderUtil.hLine(sx, cRight, cBot - 1, color);
            }
        }
    }

    private void drawSettingsButton(int mouseX, int mouseY) {
        CustomFont font = Fonts.medium;
        int btnX = MARGIN;
        int btnY = this.height - HEADER_HEIGHT - MARGIN;
        boolean hover = RenderUtil.hovered(mouseX, mouseY, btnX, btnY, SETTINGS_BTN_WIDTH, HEADER_HEIGHT);
        RenderUtil.rect(btnX, btnY, SETTINGS_BTN_WIDTH, HEADER_HEIGHT, Theme.BODY);
        if (hover) {
            RenderUtil.rect(btnX, btnY, SETTINGS_BTN_WIDTH, HEADER_HEIGHT, Theme.HOVER_LIFT);
        }
        Theme.contour(btnX, btnY, SETTINGS_BTN_WIDTH, HEADER_HEIGHT);
        if (font != null) {
            font.drawCenteredInRect("Settings", btnX, btnY,
                    SETTINGS_BTN_WIDTH, HEADER_HEIGHT, Theme.TEXT);
        }
        if (settingsFlyoutOpen) {
            for (int i = 0; i < FLYOUT_ROWS.length; i++) {
                int rowY = btnY - ROW_HEIGHT * (i + 1);
                boolean rowHover = RenderUtil.hovered(mouseX, mouseY, btnX, rowY, SETTINGS_BTN_WIDTH, ROW_HEIGHT);
                RenderUtil.rect(btnX, rowY, SETTINGS_BTN_WIDTH, ROW_HEIGHT, Theme.BODY);
                if (rowHover) {
                    RenderUtil.rect(btnX, rowY, SETTINGS_BTN_WIDTH, ROW_HEIGHT, Theme.HOVER_LIFT);
                }
                Theme.contour(btnX, rowY, SETTINGS_BTN_WIDTH, ROW_HEIGHT);
                if (font != null) {
                    font.drawCenteredInRect(FLYOUT_ROWS[i], btnX, rowY,
                            SETTINGS_BTN_WIDTH, ROW_HEIGHT, Theme.TEXT);
                }
            }
        }
    }

    public static boolean matchesSearch(Module module) {
        return search.isEmpty() || module.getName().toLowerCase().contains(search.toLowerCase());
    }

    private void drawSearchBox(int mouseX, int mouseY) {
        searchCursorCounter++;
        int boxX = this.width - MARGIN - SEARCH_BOX_WIDTH;
        int boxY = this.height - HEADER_HEIGHT - MARGIN;
        CustomSearchField.draw(Fonts.medium, boxX, boxY, SEARCH_BOX_WIDTH, HEADER_HEIGHT,
                search, searchFocused, "Search...", searchCursorCounter);
    }

    // Stop at the topmost containing panel even if it has no tooltip, so covered panels cannot leak one.
    private String resolveTooltip(int mouseX, int mouseY) {
        if (settingsPanel != null && settingsPanel.contains(mouseX, mouseY)) {
            return settingsPanel.getTooltipAt(mouseX, mouseY);
        }
        // the dock host renders with the docked composite (topmost), so it wins over the loop
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

    private void drawTooltip(String text, int mouseX, int mouseY) {
        CustomFont font = Fonts.medium;
        if (font == null) {
            return;
        }
        List<String> lines = font.wrapToWidth(text, TOOLTIP_MAX_WIDTH);
        int lineH = font.getHeight();
        int contentW = 0;
        for (String line : lines) {
            contentW = Math.max(contentW, font.getStringWidth(line));
        }
        int boxW = contentW + TOOLTIP_PAD * 2;
        int boxH = lines.size() * lineH + (lines.size() - 1) * TOOLTIP_LINE_GAP + TOOLTIP_PAD * 2;

        int left = mouseX + 8;
        int top = mouseY + 8;
        if (left + boxW > this.width) {
            left = mouseX - 8 - boxW;
        }
        if (top + boxH > this.height) {
            top = mouseY - 8 - boxH;
        }
        left = MathHelper.clamp_int(left, 2, Math.max(2, this.width - boxW - 2));
        top = MathHelper.clamp_int(top, 2, Math.max(2, this.height - boxH - 2));

        RenderUtil.drawBorderedRect(left, top, left + boxW, top + boxH, Theme.TOOLTIP_BG, Theme.CONTOUR, Theme.CONTOUR_PX);
        int textY = top + TOOLTIP_PAD;
        for (String line : lines) {
            font.drawStringWithShadow(line, left + TOOLTIP_PAD, textY, Theme.TEXT);
            textY += lineH + TOOLTIP_LINE_GAP;
        }
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        if (mouseButton != 2) {
            listeningModule = null;
        }
        searchFocused = false;
        // Dismissing the modal must not also activate a control behind it.
        if (configWindow != null) {
            if (configWindow.mouseClicked(mouseX, mouseY, mouseButton)) {
                return;
            }
            configWindow = null;
            return;
        }
        if (settingsPanel != null && settingsPanel.mouseClicked(mouseX, mouseY, mouseButton)) {
            return;
        }
        if (mouseButton == 0 && RenderUtil.hovered(mouseX, mouseY,
                this.width - MARGIN - SEARCH_BOX_WIDTH, this.height - HEADER_HEIGHT - MARGIN,
                SEARCH_BOX_WIDTH, HEADER_HEIGHT)) {
            searchFocused = true;
            searchCursorCounter = 0;
            return;
        }
        if (mouseButton == 0) {
            int btnX = MARGIN;
            int btnY = this.height - HEADER_HEIGHT - MARGIN;
            if (RenderUtil.hovered(mouseX, mouseY, btnX, btnY, SETTINGS_BTN_WIDTH, HEADER_HEIGHT)) {
                settingsFlyoutOpen = !settingsFlyoutOpen;
                return;
            }
            if (settingsFlyoutOpen) {
                int configY = btnY - ROW_HEIGHT;
                if (RenderUtil.hovered(mouseX, mouseY, btnX, configY, SETTINGS_BTN_WIDTH, ROW_HEIGHT)) {
                    configWindow = new ConfigWindow(this, this.width, this.height);
                    settingsFlyoutOpen = false;
                    return;
                }
                int editY = btnY - ROW_HEIGHT * 2;
                if (RenderUtil.hovered(mouseX, mouseY, btnX, editY, SETTINGS_BTN_WIDTH, ROW_HEIGHT)) {
                    // The screen swap invokes onGuiClosed to persist panel positions.
                    this.mc.displayGuiScreen(new HudEditScreen());
                    return;
                }
            }
        }
        // Hit-test in draw order: the docked pair is above all other panels.
        CategoryPanel dockHost = settingsPanel != null ? settingsPanel.getDockHost() : null;
        if (dockHost != null && dockHost.mouseClicked(mouseX, mouseY, mouseButton, this)) {
            raisePanel(dockHost);
            if (settingsPanel != null && settingsPanel.isDockedTo(dockHost) && dockHost.isCollapsed()) {
                settingsPanel = null;
            }
            return;
        }
        // Raising a clicked panel keeps rendering and hit-testing in the same z-order, including drags.
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
        // Use the wheel event's cursor; the previous frame may point at a different window.
        int mouseX = Mouse.getEventX() * this.width / this.mc.displayWidth;
        int mouseY = this.height - Mouse.getEventY() * this.height / this.mc.displayHeight - 1;
        int wheel = Mouse.getEventDWheel();
        super.handleMouseInput();
        if (wheel == 0) {
            return;
        }
        // A config list only consumes scrolling inside its bounds.
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
            // InputManager consumes the GUI-open key before module binds, so that bind could never fire.
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
                settingsPanel = new SettingsPanel(this, module, host, this.width, this.height);
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

    // Draw once before content: overlapping translucent body fills cause visible bands.
    public static void drawWindowBase(int x, int y, int width, int height) {
        RenderUtil.rect(x, y, width, height, Theme.BODY);
    }

    // Draw after content so the frame covers full-width separators.
    public static void drawWindowFrame(int x, int y, int width, int height) {
        drawHeaderRule(x, y, width);
        Theme.contour(x, y, width, height);
    }

    public static void drawHeaderRule(int x, int y, int width) {
        RenderUtil.rectBounds(x, y + HEADER_HEIGHT - 1, x + width, y + HEADER_HEIGHT, Theme.SEP);
    }

    public static void drawWindowHeader(CustomFont font, String title, int x, int y, int width,
                                        int mouseX, int mouseY) {
        boolean closeHover = hitsClose(x, y, width, HEADER_HEIGHT, mouseX, mouseY);
        if (closeHover) {
            RenderUtil.rectBounds(x + width - CLOSE_W, y, x + width, y + HEADER_HEIGHT, Theme.HOVER_LIFT);
        }
        float textY = y + (HEADER_HEIGHT - font.getHeight()) / 2f;
        font.drawString(title, x + 5, textY, Theme.TEXT);
        font.drawCenteredInRect("x", x + width - CLOSE_W, y, CLOSE_W, HEADER_HEIGHT,
                closeHover ? Theme.TEXT : Theme.TEXT_DIM);
    }

    // Use this for both drawing and clicks so the close button's hover and hit areas agree.
    public static boolean hitsClose(int windowX, int windowY, int windowWidth, int headerHeight,
                                     int mouseX, int mouseY) {
        return RenderUtil.hovered(mouseX, mouseY, windowX + windowWidth - CLOSE_W, windowY, CLOSE_W, headerHeight);
    }
}
