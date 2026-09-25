package net.minecraft.client.gui;

import coldplay.gui.GlassShader;
import coldplay.gui.GlassUi;
import coldplay.gui.Icons;
import coldplay.util.font.CustomFont;
import net.minecraft.client.Minecraft;
import net.minecraft.client.network.LanServerDetector;

public class ServerListEntryLanDetected implements ServerSelectionList.Entry
{
    protected final Minecraft mc;
    protected final LanServerDetector.LanServer field_148291_b;

    protected ServerListEntryLanDetected(LanServerDetector.LanServer p_i45046_2_)
    {
        this.field_148291_b = p_i45046_2_;
        this.mc = Minecraft.getMinecraft();
    }

    public float height()
    {
        return 48.0F;
    }

    public void draw(int index, float x, float y, float w, int mouseX, int mouseY, boolean hover, boolean selected)
    {
        GlassUi.entry(x, y, w, 48.0F, hover, selected);
        GlassShader.rect(x + 7.5F, y + 7.5F, 33.0F, 33.0F, 6.0F, 0x1F84D2E3, 0x1F84D2E3);
        Icons.draw(Icons.Icon.WIFI, x + 15.0F, y + 15.0F, 18.0F, 1.8F, GlassUi.FROST);
        float tx = x + 49.5F;
        int textW = Math.round(x + w - 12.0F - tx);
        CustomFont name = GlassUi.ROW.get();
        CustomFont small = GlassUi.SMALL.get();
        CustomFont mono = GlassUi.MONO.get();
        name.drawString("LAN World", tx, y + 5.25F + (12.0F - name.getHeight()) / 2.0F, GlassUi.ICE);
        small.drawString(small.trimToWidth(this.field_148291_b.getServerMotd(), textW, "..."), tx,
                y + 19.5F + (10.5F - small.getHeight()) / 2.0F, 0x8CFFFFFF);
        String address = this.mc.gameSettings.hideServerAddress ? "(Hidden)"
                : this.field_148291_b.getServerIpPort();
        mono.drawString(address, tx, y + 32.25F + (10.5F - mono.getHeight()) / 2.0F, 0x66FFFFFF);
    }

    public LanServerDetector.LanServer getLanServer()
    {
        return this.field_148291_b;
    }
}
