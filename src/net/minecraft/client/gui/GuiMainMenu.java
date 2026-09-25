package net.minecraft.client.gui;

import coldplay.gui.GlassMenuButton;
import coldplay.gui.GlassScreen;
import coldplay.gui.GlassShader;
import coldplay.gui.GlassUi;
import coldplay.gui.Glass;
import coldplay.gui.Icons;
import coldplay.gui.Theme;
import coldplay.util.font.CustomFont;
import coldplay.util.font.FontRef;
import coldplay.util.font.Fonts;
import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import net.minecraft.client.Minecraft;
import net.minecraft.world.demo.DemoWorldServer;
import net.minecraft.world.storage.ISaveFormat;
import net.minecraft.world.storage.WorldInfo;
import net.optifine.reflect.Reflector;
import org.apache.commons.io.Charsets;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.*;

public class GuiMainMenu extends GlassScreen implements GuiYesNoCallback {
    private static final Random RANDOM = new Random();
    private static final net.minecraft.util.ResourceLocation splashTexts = new net.minecraft.util.ResourceLocation("texts/splashes.txt");

    private static final float CARD_W = 270.0F;
    private static final float ROW_H = 28.5F;
    private static final float ROW_GAP = 3.0F;
    private static final float CHIP_GAP = 4.5F;
    private static final float TRACKING = 1.35F;
    private static final float RAIL = 1.5F;
    private static final FontRef WORDMARK = new FontRef(Fonts.GEIST_SEMIBOLD, 22.5F);

    /**
     * The splash message.
     */
    private String splashText;
    private GuiButton buttonResetDemo;
    private float clientY;

    public GuiMainMenu() {
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
        final boolean hasMods = Reflector.GuiModList_Constructor.exists();
        final int rows = this.mc.isDemo() ? 2 : 3 + (hasMods ? 1 : 0);
        this.setCard(CARD_W, 200.25F + rows * (ROW_H + ROW_GAP) - ROW_GAP);

        final float x = this.cardX + PAD;
        final float w = CARD_W - PAD * 2.0F;
        float y = this.cardY + 102.75F;
        if (this.mc.isDemo()) {
            y = this.addRow(11, "Play Demo World", Icons.Icon.USER, y, false);
            y = this.addRow(12, "Reset Demo World", Icons.Icon.CLOSE, y, true);
            this.buttonResetDemo = this.buttonList.get(this.buttonList.size() - 1);
            final WorldInfo worldinfo = this.mc.getSaveLoader().getWorldInfo("Demo_World");
            if (worldinfo == null) this.buttonResetDemo.enabled = false;
        } else {
            y = this.addRow(1, "Singleplayer", Icons.Icon.USER, y, false);
            y = this.addRow(2, "Multiplayer", Icons.Icon.SERVERS, y, false);
            y = this.addRow(21, "Alt Manager", Icons.Icon.USERS, y, false);
            if (hasMods) y = this.addRow(6, "Mods", Icons.Icon.LAYOUT, y, false);
        }
        this.clientY = y - ROW_GAP + 12.0F;
        final float chipY = this.clientY + 15.0F;
        final float half = (w - CHIP_GAP) / 2.0F;
        final float h = GlassUi.CHIP_H;
        final GlassMenuButton.Style chip = GlassMenuButton.Style.CHIP;
        this.buttonList.add(new GlassMenuButton(0, x, chipY, half, h, "Options", Icons.Icon.SLIDERS, chip, false));
        this.buttonList.add(new GlassMenuButton(5, x + half + CHIP_GAP, chipY, half, h, "Language", Icons.Icon.GLOBE, chip, false));
        this.buttonList.add(new GlassMenuButton(20, x, chipY + h + CHIP_GAP, half, h, "Change Log", Icons.Icon.DOCUMENT, chip, false));
        this.buttonList.add(new GlassMenuButton(4, x + half + CHIP_GAP, chipY + h + CHIP_GAP, half, h, "Quit Game", Icons.Icon.POWER, chip, true));
    }

    private float addRow(final int id, final String label, final Icons.Icon icon, final float y, final boolean danger) {
        this.buttonList.add(new GlassMenuButton(id, this.cardX + PAD, y, CARD_W - PAD * 2.0F, ROW_H, label, icon,
                GlassMenuButton.Style.ROW, danger));
        return y + ROW_H + ROW_GAP;
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
        }
        this.mc.displayGuiScreen(this);
    }

    @Override
    protected void drawCard(final int mouseX, final int mouseY, final float partialTicks) {
        final float center = this.cardX + CARD_W / 2.0F;
        final CustomFont mark = WORDMARK.get();
        final float coldW = mark.getStringWidth("COLD", TRACKING);
        final float playW = mark.getStringWidth("PLAY", TRACKING);
        final float left = center - (coldW + 1.5F + playW) / 2.0F;
        final float playX = left + coldW + 1.5F;
        final float bottom = this.cardY + 43.5F;
        // glyphs sit centered in a 24 px line box on the row's bottom edge
        final float textY = bottom - 12.0F - mark.getHeight() / 2.0F;
        mark.drawString("COLD", left, textY, GlassUi.ICE, TRACKING);
        mark.drawString("PLAY", playX, textY, GlassUi.FROST, TRACKING);
        final float railEnd = playX + playW - 2.25F;
        GlassShader.rect(playX, bottom + 3.0F, railEnd - playX, RAIL, 0.0F, GlassUi.FROST, GlassUi.FROST);
        GlassShader.rect(railEnd - RAIL, bottom - 0.75F, RAIL, 5.25F, 0.0F, GlassUi.ICE, GlassUi.ICE);
        drawGlint(playX, playX + playW, bottom + 3.0F);

        final CustomFont body = GlassUi.BODY.get();
        body.drawCentered(body.trimToWidth(this.splashText, Math.round(CARD_W - PAD * 2.0F), "..."),
                center, this.cardY + 51.0F + (12.0F - body.getHeight()) / 2.0F, GlassUi.DIM);
        GlassUi.divider(this.cardX + PAD, this.cardY + 76.5F, CARD_W - PAD * 2.0F);
        GlassUi.section("Play", this.cardX + PAD, this.cardY + 87.75F);
        GlassUi.section("Client", this.cardX + PAD, this.clientY);
        this.drawButtons(mouseX, mouseY, partialTicks);
    }

    // a light passes along the rail every six seconds
    private static void drawGlint(final float left, final float right, final float y) {
        final float p = Minecraft.getSystemTime() % 6000L / 6000.0F;
        if (p < 0.62F || p > 0.86F) {
            return;
        }
        final float from = left - 22.5F + 94.5F * ease((p - 0.62F) / 0.24F);
        final float a = p < 0.7F ? ease((p - 0.62F) / 0.08F) : 1.0F - ease((p - 0.7F) / 0.16F);
        ramp(from, from + 8.25F, 0.0F, a, left, right, y);
        ramp(from + 8.25F, from + 16.5F, a, 0.0F, left, right, y);
    }

    private static void ramp(final float x0, final float x1, final float a0, final float a1,
                             final float left, final float right, final float y) {
        final float c0 = Math.max(x0, left);
        final float c1 = Math.min(x1, right);
        if (c1 <= c0) {
            return;
        }
        final float s0 = a0 + (a1 - a0) * (c0 - x0) / (x1 - x0);
        final float s1 = a0 + (a1 - a0) * (c1 - x0) / (x1 - x0);
        GlassShader.rect(c0, y, c1 - c0, RAIL, 0.0F,
                Theme.withAlpha(0xFFFFFFFF, Math.round(s0 * 255)), Theme.withAlpha(0xFFFFFFFF, Math.round(s1 * 255)));
    }

    private static float ease(final float t) {
        return t * t * (3.0F - 2.0F * t);
    }

    @Override
    protected void drawFooter() {
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
        } else {
            this.versionChip();
        }
        final CustomFont body = GlassUi.BODY.get();
        final String text = "Open ColdPlay in-game";
        final String key = "Right Shift";
        final float keyW = GlassUi.kbdWidth(key);
        final float w = 9.0F + body.getStringWidth(text) + 7.5F + keyW + 4.5F;
        final float x = this.width - 15.0F - w;
        final float y = this.height - 37.5F;
        GlassShader.panel(x, y, w, 22.5F, 6.0F, Glass.SMOKE);
        body.drawString(text, x + 9.0F, y + (22.5F - body.getHeight()) / 2.0F, 0xB3FFFFFF);
        GlassUi.kbd(x + w - 4.5F - keyW, y + 3.75F, key);
    }
}
