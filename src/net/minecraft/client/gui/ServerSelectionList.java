package net.minecraft.client.gui;

import com.google.common.collect.Lists;
import java.util.List;
import net.minecraft.client.multiplayer.ServerList;
import net.minecraft.client.network.LanServerDetector;

/** Saved servers, then the LAN scan row, then detected LAN games; the multiplayer screen draws them. */
public class ServerSelectionList
{
    /** One row of the multiplayer list, drawn in card coordinates. */
    public interface Entry
    {
        float height();

        void draw(int index, float x, float y, float w, int mouseX, int mouseY, boolean hover, boolean selected);
    }

    private final GuiMultiplayer owner;
    private final List<ServerListEntryNormal> serverListInternet = Lists.<ServerListEntryNormal>newArrayList();
    private final List<ServerListEntryLanDetected> serverListLan = Lists.<ServerListEntryLanDetected>newArrayList();
    private final Entry lanScanEntry = new ServerListEntryLanScan();
    private int selectedSlotIndex = -1;

    public ServerSelectionList(GuiMultiplayer ownerIn)
    {
        this.owner = ownerIn;
    }

    /**
     * Gets the Entry object for the given index
     */
    public Entry getListEntry(int index)
    {
        if (index < this.serverListInternet.size())
        {
            return this.serverListInternet.get(index);
        }
        else
        {
            index = index - this.serverListInternet.size();

            if (index == 0)
            {
                return this.lanScanEntry;
            }
            else
            {
                --index;
                return this.serverListLan.get(index);
            }
        }
    }

    public int getSize()
    {
        return this.serverListInternet.size() + 1 + this.serverListLan.size();
    }

    public int countSaved()
    {
        return this.serverListInternet.size();
    }

    public void setSelectedSlotIndex(int selectedSlotIndexIn)
    {
        this.selectedSlotIndex = selectedSlotIndexIn;
    }

    public int func_148193_k()
    {
        return this.selectedSlotIndex;
    }

    public void func_148195_a(ServerList p_148195_1_)
    {
        this.serverListInternet.clear();

        for (int i = 0; i < p_148195_1_.countServers(); ++i)
        {
            this.serverListInternet.add(new ServerListEntryNormal(this.owner, p_148195_1_.getServerData(i)));
        }
    }

    public void func_148194_a(List<LanServerDetector.LanServer> p_148194_1_)
    {
        this.serverListLan.clear();

        for (LanServerDetector.LanServer lanserverdetector$lanserver : p_148194_1_)
        {
            this.serverListLan.add(new ServerListEntryLanDetected(lanserverdetector$lanserver));
        }
    }
}
