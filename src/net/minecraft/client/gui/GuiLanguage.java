package net.minecraft.client.gui;

import coldplay.gui.GlassList;
import coldplay.gui.GlassScreen;
import coldplay.gui.GlassUi;
import coldplay.gui.Icons;
import coldplay.util.RenderUtil;
import coldplay.util.font.CustomFont;
import com.google.common.collect.Lists;
import java.io.IOException;
import java.util.List;
import net.minecraft.client.resources.Language;
import net.minecraft.client.resources.LanguageManager;
import net.minecraft.client.settings.GameSettings;

public class GuiLanguage extends GlassScreen
{
    private static final float ROW_H = 28.5F;
    private static final float ROW_STEP = 31.5F;
    private static final String UNICODE = "Force Unicode Font";

    /** The parent Gui screen */
    protected GuiScreen parentScreen;

    /** Reference to the GameSettings object. */
    private final GameSettings game_settings_3;

    /** Reference to the LanguageManager object. */
    private final LanguageManager languageManager;
    private final List<Language> languages = Lists.<Language>newArrayList();
    private final GlassList list = new GlassList();
    private float footerY;

    public GuiLanguage(GuiScreen screen, GameSettings gameSettingsObj, LanguageManager manager)
    {
        this.parentScreen = screen;
        this.game_settings_3 = gameSettingsObj;
        this.languageManager = manager;
    }

    /**
     * Adds the buttons (and other controls) to the screen in question. Called when the GUI is displayed and when the
     * window resizes, the buttonList is cleared beforehand.
     */
    public void initGui()
    {
        this.languages.clear();
        this.languages.addAll(this.languageManager.getLanguages());
        this.setCard(480.0F, 363.0F);
        this.list.place(this.cardX + PAD, this.cardY + 55.5F, this.cardW - PAD * 2.0F, 255.0F);
        this.list.setContent(this.languages.size() * ROW_STEP + 3.0F);
        int current = this.languages.indexOf(this.languageManager.getCurrentLanguage());
        this.list.reveal(current * ROW_STEP, current * ROW_STEP + ROW_H + 6.0F);
        this.footerY = this.list.y + this.list.h + 12.0F;
        this.addBack(6);
    }

    /**
     * Called by the controls from the buttonList when activated. (Mouse pressed for buttons)
     */
    protected void actionPerformed(GuiButton button) throws IOException
    {
        if (button.enabled && button.id == 6)
        {
            this.mc.displayGuiScreen(this.parentScreen);
        }
    }

    private float toggleWidth()
    {
        return 9.0F + 21.0F + 7.5F + GlassUi.BODY.get().getStringWidth(UNICODE) + 9.0F;
    }

    @Override
    protected void drawCard(int mouseX, int mouseY, float partialTicks)
    {
        this.drawHeader("Client", "Language");
        CustomFont mono = GlassUi.MONO.get();
        String count = this.languages.size() + " languages";
        mono.drawString(count, this.cardX + this.cardW - PAD - mono.getStringWidth(count),
                this.cardY + PAD_TOP + (27.0F - mono.getHeight()) / 2.0F, 0x80FFFFFF);

        GlassUi.well(this.list.x, this.list.y, this.list.w, this.list.h);
        int hover = this.rowAt(mouseX, mouseY);
        String currentCode = this.languageManager.getCurrentLanguage().getLanguageCode();
        this.clip(this.list.x, this.list.y, this.list.w, this.list.h);
        for (int i = 0; i < this.languages.size(); i++)
        {
            float y = this.list.top() + 3.0F + i * ROW_STEP;
            if (y + ROW_H > this.list.y && y < this.list.y + this.list.h)
            {
                Language language = this.languages.get(i);
                this.drawLanguage(language, this.list.x + 3.0F, y, this.list.w - 6.0F, i == hover,
                        language.getLanguageCode().equals(currentCode));
            }
        }
        this.unclip();
        this.list.drawScrollbar();

        float x = this.cardX + PAD;
        float w = this.toggleWidth();
        boolean hoverToggle = RenderUtil.hovered(mouseX, mouseY, x, this.footerY, w, GlassUi.CHIP_H);
        GlassUi.chip(x, this.footerY, w, GlassUi.CHIP_H, "", null, hoverToggle ? 1.0F : 0.0F, true, false, false);
        GlassUi.toggle(x + 9.0F, this.footerY + 6.75F, this.game_settings_3.forceUnicodeFont);
        CustomFont body = GlassUi.BODY.get();
        body.drawString(UNICODE, x + 37.5F, this.footerY + (GlassUi.CHIP_H - body.getHeight()) / 2.0F, 0xCCFFFFFF);
        CustomFont small = GlassUi.SMALL.get();
        String warning = "Translations may not be 100% accurate";
        small.drawString(warning, this.cardX + this.cardW - PAD - small.getStringWidth(warning),
                this.footerY + (GlassUi.CHIP_H - small.getHeight()) / 2.0F, GlassUi.MUTE);
        this.drawButtons(mouseX, mouseY, partialTicks);
    }

    private void drawLanguage(Language language, float x, float y, float w, boolean hover, boolean selected)
    {
        GlassUi.entry(x, y, w, ROW_H, hover, selected);
        String full = language.toString();
        int split = full.lastIndexOf(" (");
        String name = split > 0 ? full.substring(0, split) : full;
        String region = split > 0 ? full.substring(split + 2, full.length() - 1) : "";
        float nx = x + 12.0F;
        nx += this.text(GlassUi.ROW.get(), name, nx, y, ROW_H, selected ? 0xFFFFFFFF : GlassUi.ICE) + 7.5F;
        this.text(GlassUi.BODY.get(), region, nx, y, ROW_H, 0x80FFFFFF);
        float right = x + w - 12.0F;
        if (selected)
        {
            Icons.draw(Icons.Icon.CHECK, right - 10.5F, y + (ROW_H - 10.5F) / 2.0F, 10.5F, 2.4F, GlassUi.FROST);
            right -= 18.0F;
        }
        CustomFont mono = GlassUi.MONO.get();
        mono.drawString(language.getLanguageCode(), right - mono.getStringWidth(language.getLanguageCode()),
                y + (ROW_H - mono.getHeight()) / 2.0F, 0x73FFFFFF);
    }

    /** Draws with the baked font when it can, else with the vanilla one; returns the width drawn. */
    private float text(CustomFont font, String s, float x, float y, float h, int color)
    {
        for (int i = 0; i < s.length(); i++)
        {
            if (s.charAt(i) < 32 || s.charAt(i) > 126)
            {
                this.fontRendererObj.setBidiFlag(true);
                this.fontRendererObj.drawString(s, x, y + (h - 8.0F) / 2.0F, color & 0xFFFFFF, false);
                this.fontRendererObj.setBidiFlag(this.languageManager.getCurrentLanguage().isBidirectional());
                return this.fontRendererObj.getStringWidth(s);
            }
        }
        font.drawString(s, x, y + (h - font.getHeight()) / 2.0F, color);
        return font.getStringWidth(s);
    }

    private int rowAt(int mouseX, int mouseY)
    {
        if (!this.list.contains(mouseX, mouseY))
        {
            return -1;
        }
        float offset = mouseY - this.list.top() - 3.0F;
        int index = (int) Math.floor(offset / ROW_STEP);
        return index >= 0 && index < this.languages.size() && offset - index * ROW_STEP < ROW_H ? index : -1;
    }

    @Override
    protected void cardClicked(int mouseX, int mouseY, int button)
    {
        if (button != 0)
        {
            return;
        }
        if (RenderUtil.hovered(mouseX, mouseY, this.cardX + PAD, this.footerY, this.toggleWidth(), GlassUi.CHIP_H))
        {
            this.game_settings_3.setOptionValue(GameSettings.Options.FORCE_UNICODE_FONT, 1);
            this.setWorldAndResolution(this.mc, this.width, this.height);
            return;
        }
        int index = this.rowAt(mouseX, mouseY);
        if (index < 0)
        {
            return;
        }
        Language language = this.languages.get(index);
        this.languageManager.setCurrentLanguage(language);
        this.game_settings_3.language = language.getLanguageCode();
        this.mc.refreshResources();
        this.fontRendererObj.setUnicodeFlag(this.languageManager.isCurrentLocaleUnicode() || this.game_settings_3.forceUnicodeFont);
        this.fontRendererObj.setBidiFlag(this.languageManager.isCurrentLanguageBidirectional());
        this.game_settings_3.saveOptions();
    }

    @Override
    protected void cardScrolled(int mouseX, int mouseY, int wheel)
    {
        this.list.wheel(wheel, ROW_STEP);
    }
}
