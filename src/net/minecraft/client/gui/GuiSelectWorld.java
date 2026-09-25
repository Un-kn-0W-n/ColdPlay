package net.minecraft.client.gui;

import coldplay.gui.GlassList;
import coldplay.gui.GlassMenuButton;
import coldplay.gui.GlassScreen;
import coldplay.gui.GlassShader;
import coldplay.gui.GlassUi;
import coldplay.gui.Icons;
import coldplay.util.font.CustomFont;
import java.io.IOException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import net.minecraft.client.AnvilConverterException;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.I18n;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.storage.ISaveFormat;
import net.minecraft.world.storage.ISaveHandler;
import net.minecraft.world.storage.SaveFormatComparator;
import net.minecraft.world.storage.WorldInfo;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class GuiSelectWorld extends GlassScreen implements GuiYesNoCallback
{
    private static final Logger logger = LogManager.getLogger();
    private static final float ROW_H = 42.0F;
    private static final float ROW_STEP = 45.0F;
    private final DateFormat dateFormat = new SimpleDateFormat();
    protected GuiScreen parentScreen;
    private boolean launching;

    /** The list index of the currently-selected world */
    private int selectedIndex = -1;
    private java.util.List<SaveFormatComparator> saves;
    private final GlassList list = new GlassList();
    private int lastClicked = -1;
    private long lastClickTime;
    private boolean confirmingDelete;
    private GuiButton deleteButton;
    private GuiButton selectButton;
    private GuiButton renameButton;
    private GuiButton recreateButton;

    public GuiSelectWorld(GuiScreen parentScreenIn)
    {
        this.parentScreen = parentScreenIn;
    }

    /**
     * Adds the buttons (and other controls) to the screen in question. Called when the GUI is displayed and when the
     * window resizes, the buttonList is cleared beforehand.
     */
    public void initGui()
    {
        try
        {
            this.loadLevelList();
        }
        catch (AnvilConverterException anvilconverterexception)
        {
            logger.error((String)"Couldn\'t load level list", (Throwable)anvilconverterexception);
            this.mc.displayGuiScreen(new GuiErrorScreen("Unable to load worlds", anvilconverterexception.getMessage()));
            return;
        }

        this.setCard(540.0F, 393.0F);
        this.list.place(this.cardX + PAD, this.cardY + 55.5F, 510.0F, 285.0F);
        this.addBack(0);
        float y = this.cardY + 352.5F;
        float x = this.cardX + PAD;
        this.buttonList.add(this.renameButton = this.chip(6, x, y, "Rename", Icons.Icon.PENCIL, false));
        x = ((GlassMenuButton) this.renameButton).right() + 6.0F;
        this.buttonList.add(this.recreateButton = this.chip(7, x, y, "Re-Create", Icons.Icon.REFRESH, false));
        x = ((GlassMenuButton) this.recreateButton).right() + 6.0F;
        this.buttonList.add(this.deleteButton = this.chip(2, x, y, "Delete", Icons.Icon.TRASH, true));
        float playW = GlassUi.chipWidth("Play Selected World", Icons.Icon.PLAY, true);
        float right = this.cardX + this.cardW - PAD;
        this.buttonList.add(this.selectButton = new GlassMenuButton(1, right - playW, y, playW, GlassUi.CHIP_H,
                "Play Selected World", Icons.Icon.PLAY, GlassMenuButton.Style.PRIMARY, false));
        float createW = GlassUi.chipWidth("Create New World", Icons.Icon.PLUS, false);
        this.buttonList.add(this.chip(3, right - playW - 6.0F - createW, y, "Create New World", Icons.Icon.PLUS, false));
        this.select(this.selectedIndex);
    }

    private GlassMenuButton chip(int id, float x, float y, String label, Icons.Icon icon, boolean danger)
    {
        return new GlassMenuButton(id, x, y, GlassUi.chipWidth(label, icon, false), GlassUi.CHIP_H, label, icon,
                GlassMenuButton.Style.CHIP_FIT, danger);
    }

    /**
     * Load the existing world saves for display
     */
    private void loadLevelList() throws AnvilConverterException
    {
        ISaveFormat isaveformat = this.mc.getSaveLoader();
        this.saves = isaveformat.getSaveList();
        Collections.sort(this.saves);
        this.selectedIndex = -1;
    }

    protected String func_146621_a(int p_146621_1_)
    {
        return this.saves.get(p_146621_1_).getFileName();
    }

    protected String func_146614_d(int p_146614_1_)
    {
        String s = this.saves.get(p_146614_1_).getDisplayName();

        if (StringUtils.isEmpty(s))
        {
            s = I18n.format("selectWorld.world", new Object[0]) + " " + (p_146614_1_ + 1);
        }

        return s;
    }

    private void select(int index)
    {
        this.selectedIndex = index;
        boolean flag = index >= 0 && index < this.saves.size();
        this.selectButton.enabled = flag;
        this.deleteButton.enabled = flag;
        this.renameButton.enabled = flag;
        this.recreateButton.enabled = flag;
    }

    /**
     * Called by the controls from the buttonList when activated. (Mouse pressed for buttons)
     */
    protected void actionPerformed(GuiButton button) throws IOException
    {
        if (button.enabled)
        {
            if (button.id == 2)
            {
                String s = this.func_146614_d(this.selectedIndex);

                if (s != null)
                {
                    this.confirmingDelete = true;
                    GuiYesNo guiyesno = makeDeleteWorldYesNo(this, s, this.selectedIndex);
                    this.mc.displayGuiScreen(guiyesno);
                }
            }
            else if (button.id == 1)
            {
                this.func_146615_e(this.selectedIndex);
            }
            else if (button.id == 3)
            {
                this.mc.displayGuiScreen(new GuiCreateWorld(this));
            }
            else if (button.id == 6)
            {
                this.mc.displayGuiScreen(new GuiRenameWorld(this, this.func_146621_a(this.selectedIndex)));
            }
            else if (button.id == 0)
            {
                this.mc.displayGuiScreen(this.parentScreen);
            }
            else if (button.id == 7)
            {
                GuiCreateWorld guicreateworld = new GuiCreateWorld(this);
                ISaveHandler isavehandler = this.mc.getSaveLoader().getSaveLoader(this.func_146621_a(this.selectedIndex), false);
                WorldInfo worldinfo = isavehandler.loadWorldInfo();
                isavehandler.flush();
                guicreateworld.recreateFromExistingWorld(worldinfo);
                this.mc.displayGuiScreen(guicreateworld);
            }
        }
    }

    public void func_146615_e(int p_146615_1_)
    {
        this.mc.displayGuiScreen((GuiScreen)null);

        if (!this.launching)
        {
            this.launching = true;
            String s = this.func_146621_a(p_146615_1_);

            if (s == null)
            {
                s = "World" + p_146615_1_;
            }

            String s1 = this.func_146614_d(p_146615_1_);

            if (s1 == null)
            {
                s1 = "World" + p_146615_1_;
            }

            if (this.mc.getSaveLoader().canLoadWorld(s))
            {
                this.mc.launchIntegratedServer(s, s1, (WorldSettings)null);
            }
        }
    }

    public void confirmClicked(boolean result, int id)
    {
        if (this.confirmingDelete)
        {
            this.confirmingDelete = false;

            if (result)
            {
                ISaveFormat isaveformat = this.mc.getSaveLoader();
                isaveformat.flushCache();
                isaveformat.deleteWorldDirectory(this.func_146621_a(id));

                try
                {
                    this.loadLevelList();
                }
                catch (AnvilConverterException anvilconverterexception)
                {
                    logger.error((String)"Couldn\'t load level list", (Throwable)anvilconverterexception);
                }
            }

            this.mc.displayGuiScreen(this);
        }
    }

    @Override
    protected void cardClicked(int mouseX, int mouseY, int button)
    {
        int index = this.rowAt(mouseX, mouseY);
        if (button != 0 || index < 0)
        {
            return;
        }
        boolean twice = index == this.lastClicked && Minecraft.getSystemTime() - this.lastClickTime < 250L;
        this.lastClicked = index;
        this.lastClickTime = Minecraft.getSystemTime();
        this.select(index);
        if (twice)
        {
            this.func_146615_e(index);
        }
    }

    @Override
    protected void cardScrolled(int mouseX, int mouseY, int wheel)
    {
        this.list.wheel(wheel, ROW_STEP);
    }

    private int rowAt(int mouseX, int mouseY)
    {
        if (!this.list.contains(mouseX, mouseY))
        {
            return -1;
        }
        float offset = mouseY - this.list.top() - 3.0F;
        int index = (int) Math.floor(offset / ROW_STEP);
        return index >= 0 && index < this.saves.size() && offset - index * ROW_STEP < ROW_H ? index : -1;
    }

    @Override
    protected void drawCard(int mouseX, int mouseY, float partialTicks)
    {
        this.drawHeader("Play", "Singleplayer");
        CustomFont mono = GlassUi.MONO.get();
        String count = this.saves.size() + (this.saves.size() == 1 ? " world" : " worlds");
        mono.drawString(count, this.cardX + this.cardW - PAD - mono.getStringWidth(count),
                this.cardY + PAD_TOP + (27.0F - mono.getHeight()) / 2.0F, 0x80FFFFFF);

        GlassUi.well(this.list.x, this.list.y, this.list.w, this.list.h);
        this.list.setContent(this.saves.size() * ROW_STEP + 3.0F);
        int hover = this.rowAt(mouseX, mouseY);
        this.clip(this.list.x, this.list.y, this.list.w, this.list.h);
        for (int i = 0; i < this.saves.size(); i++)
        {
            float y = this.list.top() + 3.0F + i * ROW_STEP;
            if (y + ROW_H > this.list.y && y < this.list.y + this.list.h)
            {
                this.drawWorld(this.saves.get(i), i, this.list.x + 3.0F, y, this.list.w - 6.0F, i == hover);
            }
        }
        this.unclip();
        this.list.drawScrollbar();
        this.drawButtons(mouseX, mouseY, partialTicks);
    }

    private void drawWorld(SaveFormatComparator save, int index, float x, float y, float w, boolean hover)
    {
        GlassUi.entry(x, y, w, ROW_H, hover, index == this.selectedIndex);
        WorldSettings.GameType type = save.getEnumGameType();
        boolean hardcore = save.isHardcoreModeEnabled();
        Icons.Icon icon = Icons.Icon.CUBE;
        int iconColor = 0xB8FFFFFF;
        String mode = type == WorldSettings.GameType.CREATIVE ? "Creative"
                : type == WorldSettings.GameType.ADVENTURE ? "Adventure"
                : type == WorldSettings.GameType.SPECTATOR ? "Spectator" : "Survival";
        int badgeFill = 0x0FFFFFFF;
        int badgeText = 0xB8FFFFFF;
        if (hardcore)
        {
            mode = "Hardcore";
            icon = Icons.Icon.HEART;
            iconColor = GlassUi.DANGER;
            badgeFill = 0x1FEE8A8E;
            badgeText = GlassUi.DANGER;
        }
        else if (type == WorldSettings.GameType.CREATIVE)
        {
            icon = Icons.Icon.STAR;
            iconColor = GlassUi.FROST;
            badgeFill = 0x1F84D2E3;
            badgeText = GlassUi.FROST;
        }
        else if (type == WorldSettings.GameType.SPECTATOR)
        {
            icon = Icons.Icon.EYE;
        }
        if (save.requiresConversion())
        {
            mode = "Needs conversion";
            badgeFill = 0x0FFFFFFF;
            badgeText = 0xB8FFFFFF;
        }

        GlassShader.rect(x + 7.5F, y + 7.5F, 27.0F, 27.0F, 5.25F, 0x0DFFFFFF, 0x0DFFFFFF);
        GlassShader.stroke(x + 7.5F, y + 7.5F, 27.0F, 27.0F, 5.25F, GlassUi.LINE);
        Icons.draw(icon, x + 14.25F, y + 14.25F, 13.5F, 1.8F, iconColor);

        boolean cheats = save.getCheatsEnabled() && !save.requiresConversion();
        float badgesW = GlassUi.badgeWidth(mode) + (cheats ? 4.5F + GlassUi.badgeWidth("Cheats") : 0.0F);
        float bx = x + w - 10.5F - badgesW;
        bx += GlassUi.badge(bx, y + 13.5F, mode, badgeFill, badgeText) + 4.5F;
        if (cheats)
        {
            GlassUi.badge(bx, y + 13.5F, "Cheats", 0x0FFFFFFF, 0xA8FFFFFF);
        }

        float tx = x + 43.5F;
        int textW = Math.round(x + w - 10.5F - badgesW - 9.0F - tx);
        String name = save.getDisplayName();
        if (StringUtils.isEmpty(name))
        {
            name = I18n.format("selectWorld.world", new Object[0]) + " " + (index + 1);
        }
        CustomFont font = GlassUi.ROW.get();
        font.drawString(font.trimToWidth(name, textW, "..."), tx, y + 8.5F + (12.0F - font.getHeight()) / 2.0F, GlassUi.ICE);
        CustomFont mono = GlassUi.MONO.get();
        float my = y + 23.5F + (10.0F - mono.getHeight()) / 2.0F;
        String folder = mono.trimToWidth(save.getFileName(), textW / 2, "...");
        mono.drawString(folder, tx, my, 0x80FFFFFF);
        float dx = tx + mono.getStringWidth(folder) + 6.0F;
        GlassShader.rect(dx, y + 27.375F, 2.25F, 2.25F, 1.125F, 0x4DFFFFFF, 0x4DFFFFFF);
        mono.drawString(this.dateFormat.format(new Date(save.getLastTimePlayed())), dx + 8.25F, my, 0x80FFFFFF);
    }

    @Override
    protected void drawFooter()
    {
        this.versionChip();
        this.hintChip("Double-click a world to play");
    }

    /**
     * Generate a GuiYesNo asking for confirmation to delete a world
     *
     * Called when user selects the "Delete" button.
     *
     * @param selectWorld A reference back to the GuiSelectWorld spawning the GuiYesNo
     * @param name The name of the world selected for deletion
     * @param id An arbitrary integer passed back to selectWorld's confirmClicked method
     */
    public static GuiYesNo makeDeleteWorldYesNo(GuiYesNoCallback selectWorld, String name, int id)
    {
        String s = I18n.format("selectWorld.deleteQuestion", new Object[0]);
        String s1 = "\'" + name + "\' " + I18n.format("selectWorld.deleteWarning", new Object[0]);
        String s2 = I18n.format("selectWorld.deleteButton", new Object[0]);
        String s3 = I18n.format("gui.cancel", new Object[0]);
        GuiYesNo guiyesno = new GuiYesNo(selectWorld, s, s1, s2, s3, id);
        return guiyesno;
    }
}
