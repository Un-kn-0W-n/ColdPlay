package net.minecraft.client.gui;

import coldplay.gui.GlassList;
import coldplay.gui.GlassMenuButton;
import coldplay.gui.GlassScreen;
import coldplay.gui.GlassUi;
import coldplay.gui.Icons;
import com.google.common.base.Splitter;
import com.google.common.collect.Lists;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.GuiConnecting;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerList;
import net.minecraft.client.network.LanServerDetector;
import net.minecraft.client.network.OldServerPinger;
import net.minecraft.client.resources.I18n;
import net.minecraft.util.EnumChatFormatting;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjglx.input.Keyboard;

import java.io.IOException;
import java.util.List;

public class GuiMultiplayer extends GlassScreen implements GuiYesNoCallback {
    private static final Logger logger = LogManager.getLogger();
    private static final float GAP = 3.0F;
    private final OldServerPinger oldServerPinger = new OldServerPinger();
    private final GuiScreen parentScreen;
    private ServerSelectionList serverListSelector;
    private ServerList savedServerList;
    private final GlassList list = new GlassList();
    private GuiButton btnEditServer;
    private GuiButton btnSelectServer;
    private GuiButton btnDeleteServer;
    private boolean deletingServer;
    private boolean addingServer;
    private boolean editingServer;
    private boolean directConnect;
    private int lastClicked = -1;
    private long lastClickTime;

    /**
     * The text to be displayed when the player's cursor hovers over a server listing.
     */
    private String hoveringText;
    private ServerData selectedServer;
    private LanServerDetector.LanServerList lanServerList;
    private LanServerDetector.ThreadLanServerFind lanServerDetector;
    private boolean initialized;

    public GuiMultiplayer(GuiScreen parentScreen) {
        this.parentScreen = parentScreen;
    }

    /**
     * Adds the buttons (and other controls) to the screen in question. Called when the GUI is displayed and when the
     * window resizes, the buttonList is cleared beforehand.
     */
    public void initGui() {
        Keyboard.enableRepeatEvents(true);
        this.buttonList.clear();

        if (!this.initialized) {
            this.initialized = true;
            this.savedServerList = new ServerList(this.mc);
            this.savedServerList.loadServerList();
            this.lanServerList = new LanServerDetector.LanServerList();

            try {
                this.lanServerDetector = new LanServerDetector.ThreadLanServerFind(this.lanServerList);
                this.lanServerDetector.start();
            } catch (Exception exception) {
                logger.warn("Unable to start LAN server detection: " + exception.getMessage());
            }

            this.serverListSelector = new ServerSelectionList(this);
            this.serverListSelector.func_148195_a(this.savedServerList);
        }

        this.setCard(510.0F, 408.0F);
        this.list.place(this.cardX + PAD, this.cardY + 55.5F, 480.0F, 300.0F);
        this.createButtons();
    }

    public void createButtons() {
        this.addBack(0);
        float right = this.cardX + this.cardW - PAD;
        float y = this.cardY + PAD_TOP + 0.75F;
        float addX = right - GlassUi.chipWidth("Add Server", Icons.Icon.PLUS, false);
        float directX = addX - 6.0F - GlassUi.chipWidth("Direct Connect", Icons.Icon.LINK, false);
        this.buttonList.add(this.chip(3, addX, y, "Add Server", Icons.Icon.PLUS, false));
        this.buttonList.add(this.chip(4, directX, y, "Direct Connect", Icons.Icon.LINK, false));
        this.buttonList.add(new GlassMenuButton(8, directX - 6.0F - GlassUi.CHIP_H, y, GlassUi.CHIP_H, GlassUi.CHIP_H,
                "Refresh", Icons.Icon.REFRESH, GlassMenuButton.Style.ICON, false));

        float fy = this.cardY + 367.5F;
        this.buttonList.add(this.btnEditServer = this.chip(7, this.cardX + PAD, fy, "Edit", Icons.Icon.PENCIL, false));
        this.buttonList.add(this.btnDeleteServer = this.chip(2, ((GlassMenuButton) this.btnEditServer).right() + 6.0F, fy,
                "Delete", Icons.Icon.TRASH, true));
        float joinW = GlassUi.chipWidth("Join Server", Icons.Icon.PLAY, true);
        this.buttonList.add(this.btnSelectServer = new GlassMenuButton(1, right - joinW, fy, joinW, GlassUi.CHIP_H,
                "Join Server", Icons.Icon.PLAY, GlassMenuButton.Style.PRIMARY, false));
        this.selectServer(this.serverListSelector.func_148193_k());
    }

    private GlassMenuButton chip(int id, float x, float y, String label, Icons.Icon icon, boolean danger) {
        return new GlassMenuButton(id, x, y, GlassUi.chipWidth(label, icon, false), GlassUi.CHIP_H, label, icon,
                GlassMenuButton.Style.CHIP_FIT, danger);
    }

    /**
     * Called from the main game loop to update the screen.
     */
    public void updateScreen() {
        super.updateScreen();

        if (this.lanServerList.getWasUpdated()) {
            List<LanServerDetector.LanServer> list = this.lanServerList.getLanServers();
            this.lanServerList.setWasNotUpdated();
            this.serverListSelector.func_148194_a(list);
        }

        this.oldServerPinger.pingPendingNetworks();
    }

    /**
     * Called when the screen is unloaded. Used to disable keyboard repeat events
     */
    public void onGuiClosed() {
        Keyboard.enableRepeatEvents(false);

        if (this.lanServerDetector != null) {
            this.lanServerDetector.interrupt();
            this.lanServerDetector = null;
        }

        this.oldServerPinger.clearPendingNetworks();
    }

    private ServerSelectionList.Entry selectedEntry() {
        int i = this.serverListSelector.func_148193_k();
        return i < 0 ? null : this.serverListSelector.getListEntry(i);
    }

    /**
     * Called by the controls from the buttonList when activated. (Mouse pressed for buttons)
     */
    protected void actionPerformed(GuiButton button) throws IOException {
        if (button.enabled) {
            ServerSelectionList.Entry entry = this.selectedEntry();

            if (button.id == 2 && entry instanceof ServerListEntryNormal) {
                String s4 = ((ServerListEntryNormal) entry).getServerData().serverName;

                if (s4 != null) {
                    this.deletingServer = true;
                    String s = I18n.format("selectServer.deleteQuestion");
                    String s1 = "'" + s4 + "' " + I18n.format("selectServer.deleteWarning");
                    String s2 = I18n.format("selectServer.deleteButton");
                    String s3 = I18n.format("gui.cancel");
                    GuiYesNo guiyesno = new GuiYesNo(this, s, s1, s2, s3, this.serverListSelector.func_148193_k());
                    this.mc.displayGuiScreen(guiyesno);
                }
            } else if (button.id == 1) {
                this.connectToSelected();
            } else if (button.id == 4) {
                this.directConnect = true;
                this.mc.displayGuiScreen(new GuiScreenServerList(this, this.selectedServer = new ServerData(I18n.format("selectServer.defaultName"), "", false)));
            } else if (button.id == 3) {
                this.addingServer = true;
                this.mc.displayGuiScreen(new GuiScreenAddServer(this, this.selectedServer = new ServerData(I18n.format("selectServer.defaultName"), "", false)));
            } else if (button.id == 7 && entry instanceof ServerListEntryNormal) {
                this.editingServer = true;
                ServerData serverdata = ((ServerListEntryNormal) entry).getServerData();
                this.selectedServer = new ServerData(serverdata.serverName, serverdata.serverIP, false);
                this.selectedServer.copyFrom(serverdata);
                this.mc.displayGuiScreen(new GuiScreenAddServer(this, this.selectedServer));
            } else if (button.id == 0) {
                this.mc.displayGuiScreen(this.parentScreen);
            } else if (button.id == 8) {
                this.refreshServerList();
            }
        }
    }

    private void refreshServerList() {
        this.mc.displayGuiScreen(new GuiMultiplayer(this.parentScreen));
    }

    public void confirmClicked(boolean result, int id) {
        ServerSelectionList.Entry entry = this.selectedEntry();

        if (this.deletingServer) {
            this.deletingServer = false;

            if (result && entry instanceof ServerListEntryNormal) {
                this.savedServerList.removeServerData(this.serverListSelector.func_148193_k());
                this.savedServerList.saveServerList();
                this.serverListSelector.setSelectedSlotIndex(-1);
                this.serverListSelector.func_148195_a(this.savedServerList);
            }

            this.mc.displayGuiScreen(this);
        } else if (this.directConnect) {
            this.directConnect = false;

            if (result) {
                this.connectToServer(this.selectedServer);
            } else {
                this.mc.displayGuiScreen(this);
            }
        } else if (this.addingServer) {
            this.addingServer = false;

            if (result) {
                this.savedServerList.addServerData(this.selectedServer);
                this.savedServerList.saveServerList();
                this.serverListSelector.setSelectedSlotIndex(-1);
                this.serverListSelector.func_148195_a(this.savedServerList);
            }

            this.mc.displayGuiScreen(this);
        } else if (this.editingServer) {
            this.editingServer = false;

            if (result && entry instanceof ServerListEntryNormal) {
                ServerData serverdata = ((ServerListEntryNormal) entry).getServerData();
                serverdata.serverName = this.selectedServer.serverName;
                serverdata.serverIP = this.selectedServer.serverIP;
                serverdata.copyFrom(this.selectedServer);
                this.savedServerList.saveServerList();
                this.serverListSelector.func_148195_a(this.savedServerList);
            }

            this.mc.displayGuiScreen(this);
        }
    }

    /**
     * Fired when a key is typed (except F11 which toggles full screen). This is the equivalent of
     * KeyListener.keyTyped(KeyEvent e). Args : character (character on the key), keyCode (lwjgl Keyboard key code)
     */
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        int i = this.serverListSelector.func_148193_k();
        ServerSelectionList.Entry entry = this.selectedEntry();

        if (keyCode == 63) {
            this.refreshServerList();
        } else {
            if (i >= 0) {
                if (keyCode == 200) {
                    if (isShiftKeyDown()) {
                        if (i > 0 && entry instanceof ServerListEntryNormal) {
                            this.savedServerList.swapServers(i, i - 1);
                            this.selectServer(i - 1);
                            this.serverListSelector.func_148195_a(this.savedServerList);
                        }
                    } else if (i > 0) {
                        this.selectServer(i - 1);

                        if (this.serverListSelector.getListEntry(i - 1) instanceof ServerListEntryLanScan) {
                            this.selectServer(i - 2);
                        }
                    } else {
                        this.selectServer(-1);
                    }
                } else if (keyCode == 208) {
                    if (isShiftKeyDown()) {
                        if (i < this.savedServerList.countServers() - 1) {
                            this.savedServerList.swapServers(i, i + 1);
                            this.selectServer(i + 1);
                            this.serverListSelector.func_148195_a(this.savedServerList);
                        }
                    } else if (i < this.serverListSelector.getSize() - 1) {
                        this.selectServer(i + 1);

                        if (this.serverListSelector.getListEntry(i + 1) instanceof ServerListEntryLanScan) {
                            this.selectServer(i + 2 < this.serverListSelector.getSize() ? i + 2 : -1);
                        }
                    } else {
                        this.selectServer(-1);
                    }
                } else if (keyCode != 28 && keyCode != 156) {
                    super.keyTyped(typedChar, keyCode);
                } else {
                    this.actionPerformed(this.btnSelectServer);
                }
            } else {
                super.keyTyped(typedChar, keyCode);
            }
        }
    }

    @Override
    protected void drawCard(int mouseX, int mouseY, float partialTicks) {
        this.hoveringText = null;
        this.drawHeader("Play", "Multiplayer");
        GlassUi.well(this.list.x, this.list.y, this.list.w, this.list.h);
        int size = this.serverListSelector.getSize();
        this.list.setContent(this.rowTop(size - 1) + this.serverListSelector.getListEntry(size - 1).height() + GAP);
        int hover = this.rowAt(mouseX, mouseY);
        boolean inside = this.list.contains(mouseX, mouseY);
        float x = this.list.x + GAP;
        float w = this.list.w - GAP * 2.0F;
        this.clip(this.list.x, this.list.y, this.list.w, this.list.h);
        GlassUi.section("Saved servers", x + 7.5F, this.list.top() + GAP + 6.0F);
        for (int i = 0; i < size; i++) {
            ServerSelectionList.Entry entry = this.serverListSelector.getListEntry(i);
            float y = this.list.top() + this.rowTop(i);
            if (entry instanceof ServerListEntryLanScan) {
                GlassUi.section("Local network", x + 7.5F, y - GAP - 19.5F + 9.0F);
            }
            if (y + entry.height() > this.list.y && y < this.list.y + this.list.h) {
                entry.draw(i, x, y, w, inside ? mouseX : -1, inside ? mouseY : -1,
                        i == hover && !(entry instanceof ServerListEntryLanScan), i == this.serverListSelector.func_148193_k());
            }
        }
        this.unclip();
        this.list.drawScrollbar();
        this.drawButtons(mouseX, mouseY, partialTicks);

        if (this.hoveringText != null && !this.hoveringText.isEmpty()) {
            GlassUi.tooltip(Lists.newArrayList(Splitter.on('\n').split(EnumChatFormatting.getTextWithoutFormattingCodes(this.hoveringText))),
                    mouseX, mouseY, this.toCardX(this.width), this.toCardY(this.height));
        }
    }

    /** Content y of a row; the section labels sit above the first row and above the LAN scan row. */
    private float rowTop(int index) {
        float y = GAP + 16.5F + GAP;
        for (int i = 0; i <= index; i++) {
            ServerSelectionList.Entry entry = this.serverListSelector.getListEntry(i);
            if (entry instanceof ServerListEntryLanScan) {
                y += 19.5F + GAP;
            }
            if (i < index) {
                y += entry.height() + GAP;
            }
        }
        return y;
    }

    private int rowAt(int mouseX, int mouseY) {
        if (!this.list.contains(mouseX, mouseY)) {
            return -1;
        }
        for (int i = 0; i < this.serverListSelector.getSize(); i++) {
            float top = this.list.top() + this.rowTop(i);
            if (mouseY >= top && mouseY < top + this.serverListSelector.getListEntry(i).height()) {
                return i;
            }
        }
        return -1;
    }

    @Override
    protected void cardClicked(int mouseX, int mouseY, int button) {
        int index = this.rowAt(mouseX, mouseY);
        if (button != 0 || index < 0) {
            return;
        }
        ServerSelectionList.Entry entry = this.serverListSelector.getListEntry(index);
        if (entry instanceof ServerListEntryLanScan) {
            return;
        }
        if (entry instanceof ServerListEntryNormal) {
            float y = this.list.top() + this.rowTop(index);
            int move = ((ServerListEntryNormal) entry).reorderAt(index, this.list.x + GAP, y, this.list.w - GAP * 2.0F, mouseX, mouseY);
            if (move < 0) {
                this.func_175391_a((ServerListEntryNormal) entry, index, isShiftKeyDown());
                return;
            }
            if (move > 0) {
                this.func_175393_b((ServerListEntryNormal) entry, index, isShiftKeyDown());
                return;
            }
        }
        boolean twice = index == this.lastClicked && Minecraft.getSystemTime() - this.lastClickTime < 250L;
        this.lastClicked = index;
        this.lastClickTime = Minecraft.getSystemTime();
        this.selectServer(index);
        if (twice) {
            this.connectToSelected();
        }
    }

    @Override
    protected void cardScrolled(int mouseX, int mouseY, int wheel) {
        this.list.wheel(wheel, 51.0F);
    }

    @Override
    protected void drawFooter() {
        this.versionChip();
        this.hintChip("F5", "Refresh", "Shift + Up/Down", "Reorder");
    }

    public void connectToSelected() {
        ServerSelectionList.Entry entry = this.selectedEntry();

        if (entry instanceof ServerListEntryNormal) {
            this.connectToServer(((ServerListEntryNormal) entry).getServerData());
        } else if (entry instanceof ServerListEntryLanDetected) {
            LanServerDetector.LanServer lanserverdetector$lanserver = ((ServerListEntryLanDetected) entry).getLanServer();
            this.connectToServer(new ServerData(lanserverdetector$lanserver.getServerMotd(), lanserverdetector$lanserver.getServerIpPort(), true));
        }
    }

    private void connectToServer(ServerData server) {
        this.mc.displayGuiScreen(new GuiConnecting(this, this.mc, server));
    }

    public void selectServer(int index) {
        this.serverListSelector.setSelectedSlotIndex(index);
        ServerSelectionList.Entry entry = index < 0 ? null : this.serverListSelector.getListEntry(index);
        this.btnSelectServer.enabled = false;
        this.btnEditServer.enabled = false;
        this.btnDeleteServer.enabled = false;

        if (entry != null && !(entry instanceof ServerListEntryLanScan)) {
            this.btnSelectServer.enabled = true;

            if (entry instanceof ServerListEntryNormal) {
                this.btnEditServer.enabled = true;
                this.btnDeleteServer.enabled = true;
            }
            float top = this.rowTop(index);
            this.list.reveal(top, top + entry.height());
        }
    }

    public OldServerPinger getOldServerPinger() {
        return this.oldServerPinger;
    }

    public void setHoveringText(String p_146793_1_) {
        this.hoveringText = p_146793_1_;
    }

    public ServerList getServerList() {
        return this.savedServerList;
    }

    public boolean func_175392_a(ServerListEntryNormal p_175392_1_, int p_175392_2_) {
        return p_175392_2_ > 0;
    }

    public boolean func_175394_b(ServerListEntryNormal p_175394_1_, int p_175394_2_) {
        return p_175394_2_ < this.savedServerList.countServers() - 1;
    }

    public void func_175391_a(ServerListEntryNormal p_175391_1_, int p_175391_2_, boolean p_175391_3_) {
        int i = p_175391_3_ ? 0 : p_175391_2_ - 1;
        this.savedServerList.swapServers(p_175391_2_, i);

        if (this.serverListSelector.func_148193_k() == p_175391_2_) {
            this.selectServer(i);
        }

        this.serverListSelector.func_148195_a(this.savedServerList);
    }

    public void func_175393_b(ServerListEntryNormal p_175393_1_, int p_175393_2_, boolean p_175393_3_) {
        int i = p_175393_3_ ? this.savedServerList.countServers() - 1 : p_175393_2_ + 1;
        this.savedServerList.swapServers(p_175393_2_, i);

        if (this.serverListSelector.func_148193_k() == p_175393_2_) {
            this.selectServer(i);
        }

        this.serverListSelector.func_148195_a(this.savedServerList);
    }
}
