package net.minecraft.client.gui;

import coldplay.gui.GlassShader;
import coldplay.gui.GlassUi;
import coldplay.gui.Icons;
import coldplay.gui.Theme;
import coldplay.util.font.CustomFont;
import net.minecraft.client.Minecraft;

public class ServerListEntryLanScan implements ServerSelectionList.Entry
{
    public float height()
    {
        return 39.0F;
    }

    public void draw(int index, float x, float y, float w, int mouseX, int mouseY, boolean hover, boolean selected)
    {
        CustomFont font = GlassUi.BODY.get();
        String text = "Scanning for games on your local network";
        float content = 12.0F + 7.5F + font.getStringWidth(text) + 7.5F + 15.0F;
        float cx = x + (w - content) / 2.0F;
        Icons.draw(Icons.Icon.WIFI, cx, y + 13.5F, 12.0F, 1.8F, 0x80FFFFFF);
        cx += 19.5F;
        font.drawString(text, cx, y + (39.0F - font.getHeight()) / 2.0F, 0xA8FFFFFF);
        cx += font.getStringWidth(text) + 7.5F;
        for (int i = 0; i < 3; i++)
        {
            double phase = (Minecraft.getSystemTime() / 1200.0 - i / 6.0) % 1.0;
            int alpha = (int) (64 + 191 * (0.5 - 0.5 * Math.cos(phase * Math.PI * 2)));
            int color = Theme.withAlpha(GlassUi.FROST, alpha);
            GlassShader.rect(cx + i * 6.0F, y + 18.0F, 3.0F, 3.0F, 1.5F, color, color);
        }
    }
}
