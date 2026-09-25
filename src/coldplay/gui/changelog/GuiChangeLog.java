package coldplay.gui.changelog;

import coldplay.ColdPlay;
import coldplay.gui.GlassList;
import coldplay.gui.GlassScreen;
import coldplay.gui.GlassShader;
import coldplay.gui.GlassUi;
import coldplay.util.font.CustomFont;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;

import org.lwjglx.input.Keyboard;

import java.io.IOException;
import java.util.List;

public class GuiChangeLog extends GlassScreen {

    /** Newest first; each section is {header, bullet, ...}. */
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

    private static final float INSET = 10.5F;

    private final GuiScreen parentScreen;
    private final GlassList list = new GlassList();

    public GuiChangeLog(final GuiScreen parentScreen) {
        this.parentScreen = parentScreen;
    }

    @Override
    public void initGui() {
        this.setCard(480.0F, 363.0F);
        this.list.place(this.cardX + PAD, this.cardY + 55.5F, this.cardW - PAD * 2.0F, 292.5F);
        this.addBack(0);
    }

    @Override
    protected void actionPerformed(final GuiButton button) {
        if (button.id == 0) {
            this.mc.displayGuiScreen(this.parentScreen);
        }
    }

    @Override
    protected void drawCard(final int mouseX, final int mouseY, final float partialTicks) {
        this.drawHeader("Client", "Change Log");
        final CustomFont mono = GlassUi.MONO.get();
        final String current = "v" + ColdPlay.VERSION;
        mono.drawString(current, this.cardX + this.cardW - PAD - mono.getStringWidth(current),
                this.cardY + PAD_TOP + (27.0F - mono.getHeight()) / 2.0F, 0x80FFFFFF);

        GlassUi.well(this.list.x, this.list.y, this.list.w, this.list.h);
        this.list.setContent(this.drawLog(false));
        this.clip(this.list.x, this.list.y, this.list.w, this.list.h);
        this.drawLog(true);
        this.unclip();
        this.list.drawScrollbar();
        this.drawButtons(mouseX, mouseY, partialTicks);
    }

    /** Lays the log out from the list top and returns its height; draws it too when asked. */
    private float drawLog(final boolean draw) {
        final CustomFont title = GlassUi.ROW.get();
        final CustomFont body = GlassUi.BODY.get();
        final float x = this.list.x + INSET;
        final float w = this.list.w - INSET * 2.0F - 6.0F;
        final float lineH = body.getHeight() + 3.0F;
        float y = this.list.top() + 12.0F;
        for (int s = 0; s < LOG.length; s++) {
            final String[] section = LOG[s];
            if (s > 0) {
                if (draw) {
                    GlassUi.divider(x, y, w);
                }
                y += 12.0F;
            }
            if (draw) {
                title.drawString(section[0], x, y + (12.0F - title.getHeight()) / 2.0F, GlassUi.ICE);
                if (s == 0) {
                    GlassUi.badge(x + title.getStringWidth(section[0]) + 7.5F, y - 1.5F, "Latest", 0x1F84D2E3, GlassUi.FROST);
                }
            }
            y += 12.0F + 6.0F;
            for (int i = 1; i < section.length; i++) {
                final List<String> lines = body.wrapToWidth(section[i], Math.round(w - 12.0F));
                for (int l = 0; l < lines.size(); l++) {
                    if (draw) {
                        if (l == 0) {
                            GlassShader.rect(x + 1.5F, y + body.getHeight() / 2.0F - 1.5F, 3.0F, 3.0F, 1.5F, 0x9984D2E3, 0x9984D2E3);
                        }
                        body.drawString(lines.get(l), x + 12.0F, y, 0xB3FFFFFF);
                    }
                    y += lineH;
                }
                y += 1.5F;
            }
            y += 4.5F;
        }
        return y - this.list.top();
    }

    @Override
    protected void cardScrolled(final int mouseX, final int mouseY, final int wheel) {
        this.list.wheel(wheel, 30.0F);
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
