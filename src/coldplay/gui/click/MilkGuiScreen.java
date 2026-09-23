package coldplay.gui.click;

import coldplay.ColdPlay;
import coldplay.gui.CustomTextInput;
import coldplay.gui.Glass;
import coldplay.gui.GlassShader;
import coldplay.gui.GuiStyle;
import coldplay.gui.Icons;
import coldplay.gui.hud.HudEditScreen;
import coldplay.module.Category;
import coldplay.module.Module;
import coldplay.module.ModuleManager;
import coldplay.util.RenderUtil;
import coldplay.util.font.CustomFont;
import coldplay.util.font.FontRef;
import coldplay.util.font.Fonts;

import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.util.MathHelper;
import org.lwjglx.input.Keyboard;
import org.lwjglx.input.Mouse;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Frosted white window: category sidebar, the category's modules, and the selected module's settings.
 * Drag it by any empty part of the window.
 */
public class MilkGuiScreen extends GuiScreen {

    private static final int MAX_W = 720;
    private static final int MAX_H = 465;
    private static final float RADIUS = 16.5F;
    private static final float SIDEBAR_SHARE = 159.0F / 720.0F;
    private static final float LIST_SHARE = 225.0F / 720.0F;

    private static final float SIDE_PAD = 10.5F;
    private static final float TOP_PAD = 15.0F;
    private static final float BADGE = 24.0F;
    private static final float SEARCH_H = 27.0F;
    private static final float NAV_H = 30.0F;
    private static final float NAV_GAP = 3.0F;
    private static final float FOOT_H = 27.0F;
    private static final float GROUP_GAP = 12.0F;
    private static final float ITEM_RADIUS = 8.25F;
    private static final float ICON = 12.75F;

    private static final float LIST_PAD_X = 9.0F;
    private static final float LIST_PAD_TOP = 16.5F;
    private static final int ROW_H = 22;
    private static final int ROW_PITCH = 23;
    private static final float ROW_RADIUS = 6.75F;
    private static final float ROW_PAD = 7.5F;

    private static final float SETTINGS_PAD = 16.5F;
    private static final float SETTINGS_BOTTOM = 13.5F;
    private static final float HEAD_H = 20.0F;
    private static final float BIG_SWITCH_W = 28.5F;
    private static final float BIG_SWITCH_H = 16.5F;

    private static final int DIM = 0x1F080E1C;
    private static final int SIDEBAR = 0x4DFFFFFF;
    private static final int DIVIDER = 0x12101520;
    private static final int FIELD = 0xA6FFFFFF;
    private static final int FIELD_LINE = 0x14101520;
    private static final int ACTIVE = 0xE6FFFFFF;
    private static final int SELECTED = 0xD9FFFFFF;
    private static final int HOVER = 0x0A101520;
    private static final int ACCENT = 0xFF2D5BE3;
    private static final int INK = 0xFF10151D;
    private static final int INK_DIM = 0xFF3A4452;
    private static final int INK_MUTE = 0xFF5A6474;

    private static final FontRef BRAND = new FontRef(Fonts.JAKARTA_BOLD, 12.0F);
    private static final FontRef VERSION = new FontRef(Fonts.JAKARTA, 8.25F);
    private static final FontRef FIELD_TEXT = new FontRef(Fonts.JAKARTA, 9.75F);
    private static final FontRef NAV = new FontRef(Fonts.JAKARTA_SEMIBOLD, 10.125F);
    private static final FontRef COUNT = new FontRef(Fonts.JAKARTA_MEDIUM, 8.625F);
    private static final FontRef FOOT = new FontRef(Fonts.JAKARTA_MEDIUM, 9.75F);
    private static final FontRef TITLE = new FontRef(Fonts.JAKARTA_BOLD, 15.0F);
    private static final FontRef SUBTITLE = new FontRef(Fonts.JAKARTA, 9.0F);
    private static final FontRef ROW = new FontRef(Fonts.JAKARTA_MEDIUM, 9.75F);
    private static final FontRef BIND = new FontRef(Fonts.JAKARTA_MEDIUM, 8.25F);

    private static final Icons.Icon[] CATEGORY_ICONS = {
            Icons.Icon.SWORD, Icons.Icon.MOVE, Icons.Icon.EYE, Icons.Icon.WRENCH}; // Category order
    private static final String[] FOOTER = {"Config", "Edit GUI", "Smoke GUI"};
    private static final Icons.Icon[] FOOTER_ICONS = {Icons.Icon.SLIDERS, Icons.Icon.LAYOUT, Icons.Icon.GEAR};

    private static Category category = Category.COMBAT;
    private static Module selected;

    private int x;
    private int y;
    private int w;
    private int h;
    private boolean dragging;
    private int dragDX;
    private int dragDY;
    private int listScroll;
    private String search = "";
    private boolean searchFocused;
    private int cursorCounter;
    private Module listeningModule;
    private SettingsPanel settings;
    private ConfigWindow configWindow;

    @Override
    public void initGui() {
        Fonts.load(); // the settings panel measures with the fonts
        w = Math.min(MAX_W, this.width - 16);
        h = Math.min(MAX_H, this.height - 16);
        int[] saved = ColdPlay.getInstance().getConfigManager().getMilkWindow();
        x = saved != null ? saved[0] : (this.width - w) / 2;
        y = saved != null ? saved[1] : (this.height - h) / 2;
        clampToScreen();
        listScroll = 0;
        configWindow = null;
        if (selected == null) {
            selected = manager().getModulesInCategory(category).get(0);
        }
        openSettings();
    }

    private void openSettings() {
        settings = new SettingsPanel(this, selected, settingsLeft(), settingsTop(), settingsWidth(),
                Math.round(h - (settingsTop() - y) - SETTINGS_BOTTOM));
    }

    private void clampToScreen() {
        x = MathHelper.clamp_int(x, 0, Math.max(0, this.width - w));
        y = MathHelper.clamp_int(y, 0, Math.max(0, this.height - h));
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        Fonts.load();
        if (!Fonts.isLoaded()) {
            return;
        }
        if (dragging) {
            x = mouseX - dragDX;
            y = mouseY - dragDY;
            clampToScreen();
            settings.moveTo(settingsLeft(), settingsTop());
        }
        settings.drag(mouseX, mouseY, this.width, this.height);
        cursorCounter++;
        int scaleFactor = new ScaledResolution(this.mc).getScaleFactor();
        RenderUtil.rectBounds(0, 0, this.width, this.height, DIM);
        GlassShader.panel(x, y, w, h, RADIUS, Glass.MILK);
        drawSidebar(mouseX, mouseY, scaleFactor);
        drawModules(mouseX, mouseY, scaleFactor);
        drawSettings(mouseX, mouseY, scaleFactor);
        if (configWindow != null) {
            configWindow.render(mouseX, mouseY);
            return;
        }
        String tip = tooltip(mouseX, mouseY);
        if (tip != null && !tip.isEmpty()) {
            Widgets.tooltip(Skin.MILK, tip, mouseX, mouseY, this.width, this.height);
        }
    }

    // ---- Sidebar ----

    private void drawSidebar(int mouseX, int mouseY, int scaleFactor) {
        float sw = sidebarWidth();
        // overdraw past the divider and clip it, so only the window's own corners round
        RenderUtil.beginScissor(x, y, sw, h, scaleFactor);
        GlassShader.rect(x, y, sw + RADIUS, h, RADIUS, SIDEBAR, SIDEBAR);
        RenderUtil.endScissor();
        GlassShader.rect(x + sw - 0.75F, y, 0.75F, h, 0.0F, DIVIDER, DIVIDER);

        float left = x + SIDE_PAD;
        float inner = sw - SIDE_PAD * 2;
        float by = y + TOP_PAD;
        GlassShader.fill(left, by, BADGE, BADGE, 7.5F, ACCENT, 9.0F, 3.0F, 0.3F);
        Icons.draw(Icons.Icon.SNOWFLAKE, left + (BADGE - 13.5F) / 2.0F, by + (BADGE - 13.5F) / 2.0F, 13.5F, 2.2F,
                0xFFFFFFFF);
        CustomFont brand = BRAND.get();
        CustomFont version = VERSION.get();
        float textTop = by + (BADGE - brand.getHeight() - version.getHeight()) / 2.0F;
        brand.drawString("ColdPlay", left + BADGE + 7.5F, textTop, INK, -0.12F);
        version.drawString(ColdPlay.VERSION, left + BADGE + 7.5F, textTop + brand.getHeight(), INK_MUTE);

        float sy = searchY();
        GlassShader.rect(left, sy, inner, SEARCH_H, ITEM_RADIUS, FIELD, FIELD);
        GlassShader.stroke(left, sy, inner, SEARCH_H, ITEM_RADIUS, searchFocused ? ACCENT : FIELD_LINE);
        Icons.draw(Icons.Icon.SEARCH, left + 7.5F, sy + (SEARCH_H - 10.5F) / 2.0F, 10.5F, 2.4F, INK_MUTE);
        Widgets.text(FIELD_TEXT.get(), left + 7.5F + 10.5F + 6.0F, sy, SEARCH_H, search, searchFocused,
                "Search modules", cursorCounter, Skin.MILK);

        CustomFont nav = NAV.get();
        CustomFont count = COUNT.get();
        Category[] categories = Category.values();
        for (int i = 0; i < categories.length; i++) {
            float ny = navY(i);
            boolean active = categories[i] == category && search.isEmpty();
            if (active) {
                GlassShader.fill(left, ny, inner, NAV_H, ITEM_RADIUS, ACTIVE, 3.0F, 0.75F, 0.12F);
            } else if (RenderUtil.hovered(mouseX, mouseY, left, ny, inner, NAV_H)) {
                GlassShader.rect(left, ny, inner, NAV_H, ITEM_RADIUS, HOVER, HOVER);
            }
            int ink = active ? INK : INK_DIM;
            Icons.draw(CATEGORY_ICONS[i], left + 7.5F, ny + (NAV_H - ICON) / 2.0F, ICON, 2.0F, ink);
            nav.drawString(CategoryPanel.label(categories[i]), left + 7.5F + ICON + 7.5F,
                    ny + (NAV_H - nav.getHeight()) / 2.0F, ink);
            List<Module> modules = manager().getModulesInCategory(categories[i]);
            String text = enabledCount(modules) + "/" + modules.size();
            count.drawString(text, left + inner - 7.5F - count.getStringWidth(text),
                    ny + (NAV_H - count.getHeight()) / 2.0F, INK_MUTE);
        }

        CustomFont foot = FOOT.get();
        for (int i = 0; i < FOOTER.length; i++) {
            float fy = footY(i);
            if (RenderUtil.hovered(mouseX, mouseY, left, fy, inner, FOOT_H)) {
                GlassShader.rect(left, fy, inner, FOOT_H, ITEM_RADIUS, HOVER, HOVER);
            }
            Icons.draw(FOOTER_ICONS[i], left + 7.5F, fy + (FOOT_H - 12.0F) / 2.0F, 12.0F, 2.0F, INK_DIM);
            foot.drawString(FOOTER[i], left + 7.5F + 12.0F + 7.5F, fy + (FOOT_H - foot.getHeight()) / 2.0F, INK_DIM);
        }
    }

    // ---- Module list ----

    private void drawModules(int mouseX, int mouseY, int scaleFactor) {
        float lx = listX() + LIST_PAD_X;
        float lw = listWidth() - LIST_PAD_X * 2;
        List<Module> modules = modules();
        CustomFont title = TITLE.get();
        CustomFont sub = SUBTITLE.get();
        title.drawString(search.isEmpty() ? CategoryPanel.label(category) : "Search", lx + 7.5F,
                y + LIST_PAD_TOP, INK, -0.15F);
        sub.drawString(enabledCount(modules) + " of " + modules.size() + " enabled", lx + 7.5F,
                y + LIST_PAD_TOP + title.getHeight() + 1.5F, INK_MUTE);
        GlassShader.rect(listX() + listWidth() - 0.75F, y, 0.75F, h, 0.0F, DIVIDER, DIVIDER);

        int top = listTop();
        int viewH = listViewH();
        int maxScroll = Math.max(0, modules.size() * ROW_PITCH - viewH);
        listScroll = MathHelper.clamp_int(listScroll, 0, maxScroll);
        boolean inView = RenderUtil.hovered(mouseX, mouseY, lx, top, lw, viewH);
        RenderUtil.beginScissor(listX(), top, listWidth(), viewH, scaleFactor);
        CustomFont row = ROW.get();
        CustomFont bindFont = BIND.get();
        int rowY = top - listScroll;
        for (Module module : modules) {
            if (module == selected) {
                GlassShader.fill(lx, rowY, lw, ROW_H, ROW_RADIUS, SELECTED, 3.0F, 0.75F, 0.12F);
            } else if (inView && RenderUtil.hovered(mouseX, mouseY, lx, rowY, lw, ROW_H)) {
                GlassShader.rect(lx, rowY, lw, ROW_H, ROW_RADIUS, HOVER, HOVER);
            }
            row.drawString(module.getName(), lx + ROW_PAD, rowY + (ROW_H - row.getHeight()) / 2.0F,
                    module.isEnabled() ? INK : INK_DIM);
            float sx = lx + lw - ROW_PAD - Widgets.SWITCH_W;
            String bind = module == listeningModule ? "..." : bindName(module);
            if (bind != null) {
                bindFont.drawString(bind, sx - 6 - bindFont.getStringWidth(bind),
                        rowY + (ROW_H - bindFont.getHeight()) / 2.0F, module == listeningModule ? ACCENT : INK_MUTE);
            }
            Widgets.toggle(sx, rowY + (ROW_H - Widgets.SWITCH_H) / 2.0F, Widgets.SWITCH_W, Widgets.SWITCH_H,
                    module.isEnabled(), Skin.MILK);
            rowY += ROW_PITCH;
        }
        RenderUtil.endScissor();
        if (maxScroll > 0) {
            int thumbH = RenderUtil.scrollThumbHeight(viewH, viewH, modules.size() * ROW_PITCH);
            int thumbY = top + RenderUtil.scrollThumbOffset(viewH, thumbH, listScroll, maxScroll);
            GlassShader.rect(listX() + listWidth() - 4.5F, thumbY, 2.0F, thumbH, 1.0F, 0x33101520, 0x33101520);
        }
    }

    // ---- Settings ----

    private void drawSettings(int mouseX, int mouseY, int scaleFactor) {
        CustomFont title = TITLE.get();
        float headY = y + SETTINGS_PAD;
        title.drawString(selected.getName(), settingsLeft(), headY + (HEAD_H - title.getHeight()) / 2.0F, INK, -0.15F);
        Widgets.toggle(switchX(), headY + (HEAD_H - BIG_SWITCH_H) / 2.0F, BIG_SWITCH_W, BIG_SWITCH_H,
                selected.isEnabled(), Skin.MILK);
        settings.render(mouseX, mouseY, scaleFactor);
        settings.renderItems(mouseX, mouseY);
        settings.renderDragGhost();
        settings.renderColorPicker(mouseX, mouseY);
    }

    private String tooltip(int mouseX, int mouseY) {
        if (settings.contains(mouseX, mouseY)) {
            return settings.getTooltipAt(mouseX, mouseY);
        }
        Module module = moduleAt(mouseX, mouseY);
        return module != null ? module.getDescription() : null;
    }

    // ---- Input ----

    /** Buttons as in the Smoke GUI, except a left click on a row name selects it. */
    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) {
        if (button != 2) {
            listeningModule = null;
        }
        searchFocused = false;
        if (configWindow != null) {
            if (!configWindow.mouseClicked(mouseX, mouseY, button)) {
                configWindow = null;
            }
            return;
        }
        if (settings.mouseClicked(mouseX, mouseY, button)) {
            return;
        }
        float left = x + SIDE_PAD;
        float inner = sidebarWidth() - SIDE_PAD * 2;
        if (button == 0) {
            if (RenderUtil.hovered(mouseX, mouseY, switchX() - 3, y + SETTINGS_PAD, BIG_SWITCH_W + 6, HEAD_H)) {
                toggle(selected);
                return;
            }
            if (RenderUtil.hovered(mouseX, mouseY, left, searchY(), inner, SEARCH_H)) {
                searchFocused = true;
                cursorCounter = 0;
                return;
            }
            Category[] categories = Category.values();
            for (int i = 0; i < categories.length; i++) {
                if (RenderUtil.hovered(mouseX, mouseY, left, navY(i), inner, NAV_H)) {
                    category = categories[i];
                    search = "";
                    listScroll = 0;
                    return;
                }
            }
            for (int i = 0; i < FOOTER.length; i++) {
                if (RenderUtil.hovered(mouseX, mouseY, left, footY(i), inner, FOOT_H)) {
                    footer(i);
                    return;
                }
            }
        }
        Module module = moduleAt(mouseX, mouseY);
        if (module != null) {
            if (button == 2) {
                listeningModule = listeningModule == module ? null : module;
            } else if (button == 0 && mouseX >= listX() + listWidth() - LIST_PAD_X - ROW_PAD - Widgets.SWITCH_W - 3) {
                toggle(module);
            } else {
                selected = module;
                openSettings();
            }
            return;
        }
        if (button == 0 && RenderUtil.hovered(mouseX, mouseY, x, y, w, h)) {
            dragging = true;
            dragDX = mouseX - x;
            dragDY = mouseY - y;
        }
    }

    private void footer(int index) {
        if (index == 0) {
            configWindow = new ConfigWindow(this::closeConfigWindow, this.width, this.height, Skin.MILK);
        } else if (index == 1) {
            this.mc.displayGuiScreen(new HudEditScreen());
        } else {
            ColdPlay.getInstance().getConfigManager().setGuiStyle(GuiStyle.SMOKE);
            this.mc.displayGuiScreen(GuiStyle.SMOKE.createScreen());
        }
    }

    @Override
    protected void mouseClickMove(int mouseX, int mouseY, int clickedMouseButton, long timeSinceLastClick) {
        settings.drag(mouseX, mouseY, this.width, this.height);
    }

    @Override
    protected void mouseReleased(int mouseX, int mouseY, int state) {
        settings.mouseReleased();
        if (dragging) {
            dragging = false;
            ColdPlay.getInstance().getConfigManager().setMilkWindow(x, y);
            ColdPlay.getInstance().saveConfig();
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
        } else if (settings.contains(mouseX, mouseY)) {
            settings.scroll(wheel, mouseX, mouseY);
        } else if (RenderUtil.hovered(mouseX, mouseY, listX(), listTop(), listWidth(), listViewH())) {
            listScroll += wheel > 0 ? -ROW_PITCH : ROW_PITCH; // clamped on the next draw
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
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
        if (settings.isSearching()) {
            settings.charTyped(typedChar, keyCode);
            return;
        }
        if (searchFocused) {
            CustomTextInput.EditResult edit = CustomTextInput.edit(search, typedChar, keyCode, -1);
            search = edit.getValue();
            searchFocused = edit.isFocused();
            listScroll = 0;
            return;
        }
        int guiOpenKey = ColdPlay.getInstance().getConfigManager().getGuiOpenKey();
        if (listeningModule != null) {
            // InputManager eats the GUI-open key before module binds see it
            boolean unusable = keyCode == Keyboard.KEY_ESCAPE || keyCode == guiOpenKey;
            listeningModule.setKeyBind(unusable ? Keyboard.KEY_NONE : keyCode);
            listeningModule = null;
            ColdPlay.getInstance().saveConfig();
            return;
        }
        if (keyCode == Keyboard.KEY_ESCAPE && settings.closeOverlay()) {
            return;
        }
        if (keyCode == Keyboard.KEY_ESCAPE || keyCode == guiOpenKey) {
            this.mc.displayGuiScreen(null);
        }
    }

    @Override
    public void onGuiClosed() {
        ColdPlay.getInstance().saveConfig();
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    private void closeConfigWindow() {
        configWindow = null;
    }

    private Module moduleAt(int mouseX, int mouseY) {
        if (!RenderUtil.hovered(mouseX, mouseY, listX() + LIST_PAD_X, listTop(), listWidth() - LIST_PAD_X * 2,
                listViewH())) {
            return null;
        }
        int offset = mouseY - listTop() + listScroll;
        int index = offset / ROW_PITCH;
        List<Module> modules = modules();
        return index < modules.size() && offset % ROW_PITCH < ROW_H ? modules.get(index) : null;
    }

    /** The category's modules, or every module matching the search. */
    private List<Module> modules() {
        if (search.isEmpty()) {
            return manager().getModulesInCategory(category);
        }
        List<Module> found = new ArrayList<Module>();
        for (Module module : manager().getModules()) {
            if (module.getName().toLowerCase().contains(search.toLowerCase())) {
                found.add(module);
            }
        }
        return found;
    }

    private static void toggle(Module module) {
        ColdPlay.getInstance().getModuleManager().toggle(module);
        ColdPlay.getInstance().saveConfig();
    }

    private static ModuleManager manager() {
        return ColdPlay.getInstance().getModuleManager();
    }

    private static int enabledCount(List<Module> modules) {
        int n = 0;
        for (Module module : modules) {
            if (module.isEnabled()) {
                n++;
            }
        }
        return n;
    }

    private static String bindName(Module module) {
        int key = module.getKeyBind();
        return key == Keyboard.KEY_NONE ? null : GameSettings.getKeyDisplayString(key);
    }

    // ---- Geometry ----

    private float sidebarWidth() {
        return Math.round(w * SIDEBAR_SHARE);
    }

    private int listX() {
        return x + Math.round(sidebarWidth());
    }

    private int listWidth() {
        return Math.round(w * LIST_SHARE);
    }

    private int settingsX() {
        return listX() + listWidth();
    }

    private int settingsLeft() {
        return Math.round(settingsX() + SETTINGS_PAD);
    }

    private int settingsWidth() {
        return Math.round(x + w - SETTINGS_PAD - settingsLeft());
    }

    private int settingsTop() {
        return Math.round(y + SETTINGS_PAD + HEAD_H + 7.5F);
    }

    private float switchX() {
        return settingsLeft() + settingsWidth() - BIG_SWITCH_W;
    }

    private float searchY() {
        return y + TOP_PAD + BADGE + GROUP_GAP;
    }

    private float navY(int index) {
        return searchY() + SEARCH_H + GROUP_GAP + index * (NAV_H + NAV_GAP);
    }

    private float footY(int index) {
        return y + h - GROUP_GAP - (FOOTER.length - index) * (FOOT_H + NAV_GAP) + NAV_GAP;
    }

    private int listTop() {
        return Math.round(y + LIST_PAD_TOP + TITLE.get().getHeight() + 1.5F + SUBTITLE.get().getHeight() + 9.0F);
    }

    private int listViewH() {
        return Math.round(y + h - LIST_PAD_X - listTop());
    }
}
