package net.minecraft.client.gui;

import coldplay.gui.BackgroundShader;
import coldplay.gui.MenuButton;
import coldplay.gui.Theme;
import coldplay.util.RenderUtil;
import coldplay.util.font.Fonts;
import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.resources.I18n;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.demo.DemoWorldServer;
import net.minecraft.world.storage.ISaveFormat;
import net.minecraft.world.storage.WorldInfo;
import net.optifine.reflect.Reflector;
import org.apache.commons.io.Charsets;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjglx.opengl.GLContext;
import pisi.unitedmeows.minecraft.MinecraftInstance;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.util.*;

@SuppressWarnings("FieldCanBeLocal")
public class GuiMainMenu extends GuiScreen implements GuiYesNoCallback {
    private static final Logger logger = LogManager.getLogger();
    private static final Random RANDOM = new Random();
    /**
     * The splash message.
     */
    private String splashText;
    private GuiButton buttonResetDemo;
    /**
     * The Object object utilized as a thread lock when performing non thread-safe operations
     */
    private final Object threadLock = new Object();
    /**
     * OpenGL graphics card warning.
     */
    private String openGLWarning1;
    /**
     * OpenGL graphics card warning.
     */
    private String openGLWarning2;
    /**
     * Link to the Mojang Support about minimum requirements
     */
    private String openGLWarningLink;
    private static final ResourceLocation splashTexts = new ResourceLocation("texts/splashes.txt");
    public static final String field_96138_a = "Please click " + EnumChatFormatting.UNDERLINE + "here" + EnumChatFormatting.RESET + " for more information.";
    private int field_92024_r;
    private int field_92023_s;
    private int field_92022_t;
    private int field_92021_u;
    private int field_92020_v;
    private int field_92019_w;
    private int menuPanelLeft;
    private int menuPanelTop;
    private int menuPanelRight;
    private int menuPanelBottom;
    private int menuContentX;
    private int menuContentWidth;
    private int wordmarkY;
    private boolean compactLayout;

    public GuiMainMenu() {
        this.openGLWarning2 = field_96138_a;
        this.splashText = "missingno";
        BufferedReader bufferedreader = null;
        try {
            final List<String> list = Lists.newArrayList();
            bufferedreader = new BufferedReader(new InputStreamReader(Minecraft.getMinecraft().getResourceManager().getResource(splashTexts).getInputStream(), Charsets.UTF_8));
            String s;
            while ((s = bufferedreader.readLine()) != null) {
                s = s.trim();
                if (!s.isEmpty()) list.add(s);
            }
            if (!list.isEmpty()) {
                this.splashText = list.get(RANDOM.nextInt(list.size()));
                if (this.splashText.hashCode() == 125780783) {
                    this.splashText = "Mojang removed this splash.";
                }
            }
        } catch (final IOException ignored) {
        } finally {
            if (bufferedreader != null) try {
                bufferedreader.close();
            } catch (final IOException ignored) {
            }
        }
        this.openGLWarning1 = "";
        if (!GLContext.getCapabilities().OpenGL20 && !OpenGlHelper.areShadersSupported()) {
            this.openGLWarning1 = I18n.format("title.oldgl1");
            this.openGLWarning2 = I18n.format("title.oldgl2");
            this.openGLWarningLink = "https://help.mojang.com/customer/portal/articles/325948?ref=game";
        }
    }

    /**
     * Returns true if this GUI should pause the game when it is displayed in single-player
     */
    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    /**
     * Adds the buttons (and other controls) to the screen in question. Called when the GUI is displayed
     * and when the window resizes, the buttonList is cleared beforehand.
     */
    @Override
    public void initGui() {
        final Calendar calendar = Calendar.getInstance();
        calendar.setTime(new Date());
        if (calendar.get(Calendar.MONTH) + 1 == 12 && calendar.get(Calendar.DATE) == 24) this.splashText = "Merry X-mas!";
        else if (calendar.get(Calendar.MONTH) + 1 == 1 && calendar.get(Calendar.DATE) == 1) this.splashText = "Happy new year!";
        else if (calendar.get(Calendar.MONTH) + 1 == 10 && calendar.get(Calendar.DATE) == 31) this.splashText = "OOoooOOOoooo! Spooky!";
        this.compactLayout = this.width < 640 || this.height < 360;
        final boolean hasMods = Reflector.GuiModList_Constructor.exists();
        final int buttonHeight = this.compactLayout ? 19 : 24;
        final int gap = this.compactLayout ? 4 : 6;
        final int primaryCount = this.mc.isDemo() ? 2 : 3 + (hasMods ? 1 : 0);
        final int requiredHeight = (this.compactLayout ? 76 : 112)
                + primaryCount * (buttonHeight + gap) + 2 * (buttonHeight + gap) + 14;
        // the panel is clamped to the window, not to the buttons — on a window shorter
        // than requiredHeight the button column overflows the silhouette rather than scrolling.
        final int panelHeight = Math.min(this.height - 12,
                this.compactLayout ? Math.max(218, requiredHeight) : Math.max(340, requiredHeight));
        final int panelWidth = Math.min(this.compactLayout ? 280 : 300, this.width - 24);
        this.menuPanelLeft = (this.width - panelWidth) / 2;
        this.menuPanelTop = (this.height - panelHeight) / 2;
        this.menuPanelRight = this.menuPanelLeft + panelWidth;
        this.menuPanelBottom = this.menuPanelTop + panelHeight;
        this.menuContentX = this.menuPanelLeft + 14;
        this.menuContentWidth = panelWidth - 28;
        this.wordmarkY = this.menuPanelTop + (this.compactLayout ? 30 : 50);

        final int step = buttonHeight + gap;
        int nextY = this.menuPanelTop + (this.compactLayout ? 76 : 112);
        nextY = this.mc.isDemo()
                ? this.addDemoButtons(nextY, step, buttonHeight)
                : this.addSingleplayerMultiplayerButtons(nextY, step, buttonHeight, hasMods);
        nextY += gap;

        final int halfWidth = (this.menuContentWidth - gap) / 2;
        this.buttonList.add(new MenuButton(0, this.menuContentX, nextY, halfWidth, buttonHeight,
                I18n.format("menu.options")));
        this.buttonList.add(new MenuButton(5, this.menuContentX + halfWidth + gap, nextY,
                this.menuContentWidth - halfWidth - gap, buttonHeight,
                I18n.format("options.language")));
        nextY += step;
        this.buttonList.add(new MenuButton(20, this.menuContentX, nextY, halfWidth, buttonHeight,
                "Change Log"));
        this.buttonList.add(new MenuButton(4, this.menuContentX + halfWidth + gap, nextY,
                this.menuContentWidth - halfWidth - gap, buttonHeight,
                I18n.format("menu.quit"), true));
        synchronized (this.threadLock) {
            this.field_92023_s = this.fontRendererObj.getStringWidth(this.openGLWarning1);
            this.field_92024_r = this.fontRendererObj.getStringWidth(this.openGLWarning2);
            final int k = Math.max(this.field_92023_s, this.field_92024_r);
            this.field_92022_t = (this.width - k) / 2;
            this.field_92021_u = this.buttonList.get(0).yPosition - 24;
            this.field_92020_v = this.field_92022_t + k;
            this.field_92019_w = this.field_92021_u + 24;
        }
    }

    /**
     * Adds Singleplayer and Multiplayer buttons on Main Menu for players who have bought the game.
     */
    private int addSingleplayerMultiplayerButtons(final int startY, final int step,
                                                   final int buttonHeight, final boolean hasMods) {
        int y = startY;
        this.buttonList.add(new MenuButton(1, this.menuContentX, y, this.menuContentWidth, buttonHeight,
                I18n.format("menu.singleplayer")));
        y += step;
        this.buttonList.add(new MenuButton(2, this.menuContentX, y, this.menuContentWidth, buttonHeight,
                I18n.format("menu.multiplayer")));
        y += step;
        this.buttonList.add(new MenuButton(21, this.menuContentX, y, this.menuContentWidth, buttonHeight,
                "Alt Manager"));
        y += step;
        if (hasMods) {
            this.buttonList.add(new MenuButton(6, this.menuContentX, y, this.menuContentWidth, buttonHeight,
                    I18n.format("fml.menu.mods")));
            y += step;
        }
        return y;
    }

    /**
     * Adds Demo buttons on Main Menu for players who are playing Demo.
     */
    private int addDemoButtons(final int startY, final int step, final int buttonHeight) {
        this.buttonList.add(new MenuButton(11, this.menuContentX, startY, this.menuContentWidth, buttonHeight,
                I18n.format("menu.playdemo")));
        this.buttonList.add(this.buttonResetDemo = new MenuButton(12, this.menuContentX, startY + step,
                this.menuContentWidth, buttonHeight, I18n.format("menu.resetdemo"), true));
        final ISaveFormat isaveformat = this.mc.getSaveLoader();
        final WorldInfo worldinfo = isaveformat.getWorldInfo("Demo_World");
        if (worldinfo == null) this.buttonResetDemo.enabled = false;
        return startY + step * 2;
    }

    /**
     * Called by the controls from the buttonList when activated. (Mouse pressed for buttons)
     */
    @Override
    protected void actionPerformed(final GuiButton button) throws IOException {
        if (button.id == 0) this.mc.displayGuiScreen(new GuiOptions(this, this.mc.gameSettings));
        if (button.id == 5)
            this.mc.displayGuiScreen(new GuiLanguage(this, this.mc.gameSettings, this.mc.getLanguageManager()));
        if (button.id == 1) this.mc.displayGuiScreen(new GuiSelectWorld(this));
        if (button.id == 2) this.mc.displayGuiScreen(new GuiMultiplayer(this));
        if (button.id == 4) this.mc.shutdown();
        if (button.id == 6 && Reflector.GuiModList_Constructor.exists())
            this.mc.displayGuiScreen((GuiScreen) Reflector.newInstance(Reflector.GuiModList_Constructor, this));
        if (button.id == 11)
            this.mc.launchIntegratedServer("Demo_World", "Demo_World", DemoWorldServer.demoWorldSettings);
        if (button.id == 12) {
            final ISaveFormat isaveformat = this.mc.getSaveLoader();
            final WorldInfo worldinfo = isaveformat.getWorldInfo("Demo_World");
            if (worldinfo != null) {
                final GuiYesNo guiyesno = GuiSelectWorld.makeDeleteWorldYesNo(this, worldinfo.getWorldName(), 12);
                this.mc.displayGuiScreen(guiyesno);
            }
        }
        if (button.id == 20) this.mc.displayGuiScreen(new coldplay.gui.changelog.GuiChangeLog(this));
        if (button.id == 21) this.mc.displayGuiScreen(new coldplay.gui.account.GuiAccountManager(this));
    }

    @Override
    public void confirmClicked(final boolean result, final int id) {
        if (result && id == 12) {
            final ISaveFormat isaveformat = this.mc.getSaveLoader();
            isaveformat.flushCache();
            isaveformat.deleteWorldDirectory("Demo_World");
            this.mc.displayGuiScreen(this);
        } else if (id == 13) {
            if (result) try {
                final Class<?> oclass = Class.forName("java.awt.Desktop");
                final Object object = oclass.getMethod("getDesktop").invoke(null);
                oclass.getMethod("browse", URI.class).invoke(object, new URI(this.openGLWarningLink));
            } catch (final Throwable throwable) {
                logger.error("Couldn't open link", throwable);
            }
            this.mc.displayGuiScreen(this);
        }
    }

    /**
     * Draws the screen and all the components in it. Args : mouseX, mouseY, renderPartialTicks
     */
    @Override
    public void drawScreen(final int mouseX, final int mouseY, final float partialTicks) {
        BackgroundShader.draw(this.width, this.height, this.mc.displayWidth, this.mc.displayHeight);
        GlStateManager.disableDepth();
        GlStateManager.enableAlpha();
        Fonts.load();

        final int panelW = this.menuPanelRight - this.menuPanelLeft;
        final int panelH = this.menuPanelBottom - this.menuPanelTop;
        RenderUtil.rect(this.menuPanelLeft, this.menuPanelTop, panelW, panelH, Theme.BODY);
        Theme.contour(this.menuPanelLeft, this.menuPanelTop, panelW, panelH);

        BackgroundShader.drawWordmark((this.menuPanelLeft + this.menuPanelRight) / 2.0F,
                this.wordmarkY, this.compactLayout ? 0.68F : 0.92F);
        if (Fonts.list != null) {
            final String eyebrow = "COLDPLAY CLIENT";
            Fonts.list.drawString(eyebrow,
                    (this.menuPanelLeft + this.menuPanelRight - Fonts.list.getStringWidth(eyebrow)) / 2.0F,
                    this.wordmarkY + (this.compactLayout ? 23 : 32), Theme.TEXT_MUTE);
            Fonts.list.drawCentered(
                    Fonts.list.trimToWidth(this.splashText, Math.max(40, this.menuContentWidth - 12), "..."),
                    (this.menuPanelLeft + this.menuPanelRight) / 2f,
                    this.wordmarkY + (this.compactLayout ? 36 : 47), Theme.TEXT_DIM);
        }

        String s = MinecraftInstance.NAME;
        if (this.mc.isDemo()) s = s + " Demo";
        if (Reflector.FMLCommonHandler_getBrandings.exists()) {
            final Object object = Reflector.call(Reflector.FMLCommonHandler_instance);
            //noinspection unchecked
            final List<String> list = Lists.reverse((List<String>) Objects.requireNonNull(Reflector.call(object, Reflector.FMLCommonHandler_getBrandings, Boolean.TRUE)));
            for (int l1 = 0; l1 < list.size(); ++l1) {
                final String s1 = list.get(l1);
                if (!Strings.isNullOrEmpty(s1))
                    this.drawString(this.fontRendererObj, s1, 2, this.height - (10 + l1 * (this.fontRendererObj.FONT_HEIGHT + 1)), 16777215);
            }
            if (Reflector.ForgeHooksClient_renderMainMenu.exists())
                Reflector.call(Reflector.ForgeHooksClient_renderMainMenu, this, this.fontRendererObj, this.width, this.height);
        } else if (Fonts.list != null) {
            Fonts.list.drawString(s.toUpperCase(Locale.ROOT), 5, this.height - Fonts.list.getHeight() - 5, Theme.TEXT_MUTE);
        } else this.drawString(this.fontRendererObj, s, 2, this.height - 10, Theme.TEXT_MUTE);
        // Keybind hint for new users; skipped when a tiny window leaves no room under the panel.
        if (Fonts.list != null && this.height - Fonts.list.getHeight() - 5 > this.menuPanelBottom + 2) {
            Fonts.list.drawCentered("Press Right Shift in-game to open ColdPlay",
                    this.width / 2f, this.height - Fonts.list.getHeight() - 5, Theme.TEXT_MUTE);
        }
        if (this.openGLWarning1 != null && this.openGLWarning1.length() > 0) {
            drawRect(this.field_92022_t - 2, this.field_92021_u - 2, this.field_92020_v + 2, this.field_92019_w - 1, 1428160512);
            this.drawString(this.fontRendererObj, this.openGLWarning1, this.field_92022_t, this.field_92021_u, -1);
            this.drawString(this.fontRendererObj, this.openGLWarning2, (this.width - this.field_92024_r) / 2, this.buttonList.get(0).yPosition - 12, -1);
        }
        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    /**
     * Called when the mouse is clicked. Args : mouseX, mouseY, clickedButton
     */
    @Override
    protected void mouseClicked(final int mouseX, final int mouseY, final int mouseButton) throws IOException {
        super.mouseClicked(mouseX, mouseY, mouseButton);
        synchronized (this.threadLock) {
            if (this.openGLWarning1.length() > 0 && mouseX >= this.field_92022_t && mouseX <= this.field_92020_v && mouseY >= this.field_92021_u && mouseY <= this.field_92019_w) {
                final GuiConfirmOpenLink guiconfirmopenlink = new GuiConfirmOpenLink(this, this.openGLWarningLink, 13, true);
                guiconfirmopenlink.disableSecurityWarning();
                this.mc.displayGuiScreen(guiconfirmopenlink);
            }
        }
    }
}
