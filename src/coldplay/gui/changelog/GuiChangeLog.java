package coldplay.gui.changelog;

import coldplay.gui.BackgroundShader;
import coldplay.gui.CenteredPanelLayout;
import coldplay.gui.StyledButton;
import coldplay.gui.Theme;
import coldplay.util.RenderUtil;
import coldplay.util.font.CustomFont;
import coldplay.util.font.Fonts;

import net.minecraft.client.gui.GuiScreen;

import org.lwjglx.input.Keyboard;

import java.io.IOException;
import java.util.List;

/** Changelog opened from the main menu. */
public class GuiChangeLog extends GuiScreen {

    /** The changelog itself, newest version first: each section is {header, bullet, bullet, ...}. */
    private static final String[][] LOG = {
            {"Version 1.1.0",
                    "New Mirror module"},
            {"Version 1.0.9",
                    "KillAura Rotation improvement",
                    "AutoPearl Improvement",
                    "BridgeAssist Improvement",
                    "Main menu design change",
                    "Settings panel for modules fixes",
                    "InvManager Improvement",
                    "ChestStealer Improvement"},
            {"Version 1.0.8",
                    "New AltManager improvements",
                    "New AutoPearl module"},
            {"Version 1.0.7",
                    "New Telly mode for scaffold",
                    "New Block Highligh inside BlockESP",
                    "New BlockAnimation"},
            {"Version 1.0.6",
                    "New blocking animation",
                    "Edit GUI button",
                    "Armor overlay"},
            {"Version 1.0.5",
                    "New scaffold update",
                    "Chests now have red / green border showing status of interactions with them",
                    "New search button in ClickGUI"},
            {"Version 1.0.4",
                    "Added new Chams mode to ESP!"},
            {"Version 1.0.3",
                    "KillAura new settings",
                    "New BackTrack",
                    "New Configs"},
            {"Version 1.0.2",
                    "Updated ESP Outline mode"},
            {"Version 1.0.1",
                    "AutoHeal",
                    "KillAura Rotation speed and Raytrace methods and render"},
    };

    private static final int PAD = 14;

    private final GuiScreen parentScreen;

    private int left, top, bottom, panelW;
    private int titleY;
    private int contentX, contentY, contentW, contentBottom;
    private int backX, backY, backW, backH;

    public GuiChangeLog(final GuiScreen parentScreen) {
        this.parentScreen = parentScreen;
    }

    @Override
    public void initGui() {
        Fonts.load();
    }

    private void updateLayout() {
        CenteredPanelLayout panel = CenteredPanelLayout.create(this.width, this.height, 360, 20, 24);
        this.left = panel.getLeft();
        this.top = panel.getTop();
        this.bottom = panel.getBottom();
        this.panelW = panel.getWidth();

        this.titleY = this.top + 12;

        CenteredPanelLayout.Rect back = panel.bottomButton(PAD, 20, 8);
        this.backX = back.x;
        this.backY = back.y;
        this.backW = back.width;
        this.backH = back.height;

        this.contentX = this.left + PAD;
        this.contentW = this.panelW - PAD * 2;
        this.contentY = this.titleY + 24;
        this.contentBottom = this.backY - 8;
    }

    @Override
    public void drawScreen(final int mouseX, final int mouseY, final float partialTicks) {
        Fonts.load();
        this.updateLayout();

        BackgroundShader.draw(this.width, this.height, this.mc.displayWidth, this.mc.displayHeight);
        RenderUtil.rect(this.left, this.top, this.panelW, this.bottom - this.top, Theme.BODY);
        Theme.contour(this.left, this.top, this.panelW, this.bottom - this.top);

        final CustomFont font = Fonts.medium;

        BackgroundShader.drawSectionTitle("ChangeLog", this.width / 2f, this.titleY);

        RenderUtil.drawBorderedRect(this.contentX, this.contentY, this.contentX + this.contentW,
                this.contentBottom, Theme.WELL, Theme.SEP);

        if (font != null) {
            if (LOG.length == 0) {
                final int contentH = this.contentBottom - this.contentY;
                font.drawCentered("No entries yet", this.width / 2f,
                        this.contentY + contentH / 2f - font.getHeight() / 2f, Theme.TEXT_MUTE);
            } else {
                drawEntries(font);
            }
        }

        StyledButton.draw(font, "Back", this.backX, this.backY, this.backW, this.backH,
                mouseX, mouseY, true, Theme.WELL, Theme.TEXT);
    }

    private void drawEntries(final CustomFont font) {
        final int innerX = this.contentX + 10;
        final int innerW = this.contentW - 20;
        final int bulletIndent = font.getStringWidth("- ");
        final int lineH = font.getHeight();
        final int maxY = this.contentBottom - 8 - lineH;
        // no scrolling — sections past the box bottom are clipped; add wheel scroll when the log outgrows it
        float y = this.contentY + 8;
        for (final String[] section : LOG) {
            if (y > maxY) {
                return;
            }
            font.drawString(section[0], innerX, y, Theme.TEXT);
            y += lineH + 4;
            for (int i = 1; i < section.length; i++) {
                final List<String> lines = font.wrapToWidth(section[i], innerW - bulletIndent);
                for (int l = 0; l < lines.size(); l++) {
                    if (y > maxY) {
                        return;
                    }
                    if (l == 0) {
                        font.drawString("-", innerX, y, Theme.TEXT_DIM);
                    }
                    font.drawString(lines.get(l), innerX + bulletIndent, y, Theme.TEXT_DIM);
                    y += lineH + 2;
                }
            }
            y += 10; // gap before the next version section
        }
    }

    @Override
    protected void mouseClicked(final int mouseX, final int mouseY, final int mouseButton) throws IOException {
        if (mouseButton != 0) {
            return;
        }
        if (RenderUtil.hovered(mouseX, mouseY, this.backX, this.backY, this.backW, this.backH)) {
            this.mc.displayGuiScreen(this.parentScreen);
        }
    }

    @Override
    protected void keyTyped(final char typedChar, final int keyCode) throws IOException {
        if (keyCode == Keyboard.KEY_ESCAPE) {
            this.mc.displayGuiScreen(this.parentScreen);
        }
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
