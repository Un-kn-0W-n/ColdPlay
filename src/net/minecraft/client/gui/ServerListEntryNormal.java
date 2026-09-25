package net.minecraft.client.gui;

import coldplay.gui.GlassShader;
import coldplay.gui.GlassUi;
import coldplay.gui.Icons;
import coldplay.util.font.CustomFont;
import com.google.common.base.Charsets;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.base64.Base64;
import java.awt.image.BufferedImage;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureUtil;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.ResourceLocation;
import org.apache.commons.lang3.Validate;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class ServerListEntryNormal implements ServerSelectionList.Entry
{
    private static final Logger logger = LogManager.getLogger();
    private static final ThreadPoolExecutor field_148302_b = new ScheduledThreadPoolExecutor(5, (new ThreadFactoryBuilder()).setNameFormat("Server Pinger #%d").setDaemon(true).build());
    private static final String CODES = "0123456789abcdef";
    private static final int[] PALETTE = {0x000000, 0x0000AA, 0x00AA00, 0x00AAAA, 0xAA0000, 0xAA00AA, 0xFFAA00, 0xAAAAAA,
            0x555555, 0x5555FF, 0x55FF55, 0x55FFFF, 0xFF5555, 0xFF55FF, 0xFFFF55, 0xFFFFFF};
    private static final int MOTD_GRAY = 0x8CFFFFFF;
    private final GuiMultiplayer owner;
    private final Minecraft mc;
    private final ServerData server;
    private final ResourceLocation serverIcon;
    private String field_148299_g;
    private DynamicTexture field_148305_h;

    protected ServerListEntryNormal(GuiMultiplayer p_i45048_1_, ServerData serverIn)
    {
        this.owner = p_i45048_1_;
        this.server = serverIn;
        this.mc = Minecraft.getMinecraft();
        this.serverIcon = new ResourceLocation("servers/" + serverIn.serverIP + "/icon");
        this.field_148305_h = (DynamicTexture)this.mc.getTextureManager().getTexture(this.serverIcon);
    }

    public float height()
    {
        return 48.0F;
    }

    public void draw(int index, float x, float y, float w, int mouseX, int mouseY, boolean hover, boolean selected)
    {
        if (!this.server.field_78841_f)
        {
            this.server.field_78841_f = true;
            this.server.pingToServer = -2L;
            this.server.serverMOTD = "";
            this.server.populationInfo = "";
            field_148302_b.submit(new Runnable()
            {
                public void run()
                {
                    try
                    {
                        ServerListEntryNormal.this.owner.getOldServerPinger().ping(ServerListEntryNormal.this.server);
                    }
                    catch (UnknownHostException var2)
                    {
                        ServerListEntryNormal.this.server.pingToServer = -1L;
                        ServerListEntryNormal.this.server.serverMOTD = EnumChatFormatting.DARK_RED + "Can\'t resolve hostname";
                    }
                    catch (Exception var3)
                    {
                        ServerListEntryNormal.this.server.pingToServer = -1L;
                        ServerListEntryNormal.this.server.serverMOTD = EnumChatFormatting.DARK_RED + "Can\'t connect to server.";
                    }
                }
            });
        }

        if (this.server.getBase64EncodedIconData() != null && !this.server.getBase64EncodedIconData().equals(this.field_148299_g))
        {
            this.field_148299_g = this.server.getBase64EncodedIconData();
            this.prepareServerIcon();
            this.owner.getServerList().saveServerList();
        }

        GlassUi.entry(x, y, w, 48.0F, hover, selected);
        if (this.field_148305_h != null)
        {
            this.mc.getTextureManager().bindTexture(this.serverIcon);
            GlassShader.image(x + 7.5F, y + 7.5F, 33.0F, 33.0F, 6.0F, 0.0F, 0.0F, 1.0F, 1.0F, 0xFFFFFFFF);
        }
        else
        {
            GlassShader.rect(x + 7.5F, y + 7.5F, 33.0F, 33.0F, 6.0F, 0x0FFFFFFF, 0x0FFFFFFF);
            CustomFont letter = GlassUi.TITLE.get();
            String initial = this.server.serverName.isEmpty() ? "?" : this.server.serverName.substring(0, 1).toUpperCase(Locale.ROOT);
            letter.drawCentered(initial, x + 24.0F, y + 24.0F - letter.getHeight() / 2.0F, 0x80FFFFFF);
        }

        boolean newer = this.server.version > 47;
        boolean mismatch = newer || this.server.version < 47;
        boolean pinged = this.server.field_78841_f && this.server.pingToServer != -2L;
        boolean failed = pinged && this.server.pingToServer < 0L;
        int level = 0;
        String tip;
        String ms = "";
        String players = null;

        if (mismatch)
        {
            tip = newer ? "Client out of date!" : "Server out of date!";
            players = this.server.playerList;
        }
        else if (failed)
        {
            tip = "(no connection)";
        }
        else if (pinged)
        {
            long ping = this.server.pingToServer;
            level = ping < 150L ? 5 : ping < 300L ? 4 : ping < 600L ? 3 : ping < 1000L ? 2 : 1;
            tip = ping + "ms";
            ms = ping + " ms";
            players = this.server.playerList;
        }
        else
        {
            tip = "Pinging...";
        }

        float right = x + w - 30.0F;
        CustomFont mono = GlassUi.MONO.get();
        String population = EnumChatFormatting.getTextWithoutFormattingCodes(mismatch ? this.server.gameVersion : this.server.populationInfo);
        float popW = mono.getStringWidth(population);
        mono.drawString(population, right - popW, y + 12.0F + (9.75F - mono.getHeight()) / 2.0F, mismatch ? GlassUi.DANGER : 0xB3FFFFFF);
        if (mismatch || failed)
        {
            Icons.draw(Icons.Icon.CLOSE, right - 10.5F, y + 26.25F, 10.5F, 2.4F, GlassUi.DANGER);
        }
        else
        {
            GlassUi.pingBars(right - 17.25F, y + 27.0F, level, index);
            CustomFont small = GlassUi.SMALL.get();
            small.drawString(ms, right - 23.25F - small.getStringWidth(ms), y + 27.0F + (9.0F - small.getHeight()) / 2.0F, 0x73FFFFFF);
        }
        if (mouseX >= right - 17.25F && mouseX < right && mouseY >= y + 25.5F && mouseY < y + 37.5F)
        {
            this.owner.setHoveringText(tip);
        }
        else if (players != null && mouseX >= right - popW && mouseX < right && mouseY >= y + 12.0F && mouseY < y + 21.75F)
        {
            this.owner.setHoveringText(players);
        }

        float tx = x + 49.5F;
        int textW = Math.round(right - 84.0F - 9.0F - tx);
        String motd = this.server.serverMOTD;
        if (!pinged && motd.isEmpty())
        {
            motd = "Pinging...";
        }
        String[] lines = motd.split("\n");
        int count = Math.min(2, lines.length);
        float top = y + (count == 2 ? 5.25F : 11.625F);
        CustomFont name = GlassUi.ROW.get();
        name.drawString(name.trimToWidth(this.server.serverName, textW, "..."), tx, top + (12.0F - name.getHeight()) / 2.0F, GlassUi.ICE);
        int color = MOTD_GRAY;
        for (int i = 0; i < count; i++)
        {
            float ly = top + 14.25F + i * 12.75F;
            color = failed ? GlassUi.DANGER : this.drawMotdLine(lines[i], tx, ly, textW, color, failed);
        }

        if (hover || selected)
        {
            if (this.owner.func_175392_a(this, index))
            {
                GlassUi.ghost(x + w - 22.5F, y + 6.75F, 16.5F, Icons.Icon.CHEVRON_UP, this.reorderAt(index, x, y, w, mouseX, mouseY) < 0, false);
            }
            if (this.owner.func_175394_b(this, index))
            {
                GlassUi.ghost(x + w - 22.5F, y + 24.75F, 16.5F, Icons.Icon.CHEVRON_DOWN, this.reorderAt(index, x, y, w, mouseX, mouseY) > 0, false);
            }
        }
    }

    /** Draws one MOTD line in its section colors and returns the color it ends on. */
    private int drawMotdLine(String line, float x, float y, int maxW, int color, boolean failed)
    {
        CustomFont font = GlassUi.SMALL.get();
        String plain = EnumChatFormatting.getTextWithoutFormattingCodes(line);
        if (failed)
        {
            font.drawString(font.trimToWidth(plain, maxW, "..."), x, y + (10.5F - font.getHeight()) / 2.0F, GlassUi.DANGER);
            return color;
        }
        for (int i = 0; i < plain.length(); i++)
        {
            if (plain.charAt(i) < 32 || plain.charAt(i) > 126)
            {
                // the baked font is ASCII only; the vanilla renderer handles the rest, colors included
                this.mc.fontRendererObj.drawString(this.mc.fontRendererObj.trimStringToWidth(line, maxW), x, y + 1.0F, 0x8C8C8C, false);
                return color;
            }
        }
        float cx = x;
        StringBuilder part = new StringBuilder();
        for (int i = 0; i < line.length(); i++)
        {
            char c = line.charAt(i);
            if (c == '§' && i + 1 < line.length())
            {
                cx = this.flush(font, part, cx, y, x + maxW, color);
                char code = Character.toLowerCase(line.charAt(++i));
                int palette = CODES.indexOf(code);
                if (palette >= 0)
                {
                    color = 0xFF000000 | PALETTE[palette];
                }
                else if (code == 'r')
                {
                    color = MOTD_GRAY;
                }
            }
            else
            {
                part.append(c);
            }
        }
        this.flush(font, part, cx, y, x + maxW, color);
        return color;
    }

    private float flush(CustomFont font, StringBuilder part, float x, float y, float limit, int color)
    {
        if (part.length() == 0 || x >= limit)
        {
            part.setLength(0);
            return x;
        }
        String text = font.trimToWidth(part.toString(), Math.round(limit - x), "");
        font.drawString(text, x, y + (10.5F - font.getHeight()) / 2.0F, color);
        part.setLength(0);
        return x + font.getStringWidth(text);
    }

    /** -1 over the move up arrow, 1 over move down, 0 elsewhere. */
    public int reorderAt(int index, float x, float y, float w, int mouseX, int mouseY)
    {
        if (mouseX < x + w - 22.5F || mouseX >= x + w - 6.0F)
        {
            return 0;
        }
        if (mouseY >= y + 6.75F && mouseY < y + 23.25F && this.owner.func_175392_a(this, index))
        {
            return -1;
        }
        if (mouseY >= y + 24.75F && mouseY < y + 41.25F && this.owner.func_175394_b(this, index))
        {
            return 1;
        }
        return 0;
    }

    private void prepareServerIcon()
    {
        if (this.server.getBase64EncodedIconData() == null)
        {
            this.mc.getTextureManager().deleteTexture(this.serverIcon);
            this.field_148305_h = null;
        }
        else
        {
            ByteBuf bytebuf = Unpooled.copiedBuffer((CharSequence)this.server.getBase64EncodedIconData(), Charsets.UTF_8);
            ByteBuf bytebuf1 = Base64.decode(bytebuf);
            BufferedImage bufferedimage;
            label101:
            {
                try
                {
                    bufferedimage = TextureUtil.readBufferedImage(new ByteBufInputStream(bytebuf1));
                    Validate.validState(bufferedimage.getWidth() == 64, "Must be 64 pixels wide", new Object[0]);
                    Validate.validState(bufferedimage.getHeight() == 64, "Must be 64 pixels high", new Object[0]);
                    break label101;
                }
                catch (Throwable throwable)
                {
                    logger.error("Invalid icon for server " + this.server.serverName + " (" + this.server.serverIP + ")", throwable);
                    this.server.setBase64EncodedIconData((String)null);
                }
                finally
                {
                    bytebuf.release();
                    bytebuf1.release();
                }

                return;
            }

            if (this.field_148305_h == null)
            {
                this.field_148305_h = new DynamicTexture(bufferedimage.getWidth(), bufferedimage.getHeight());
                this.mc.getTextureManager().loadTexture(this.serverIcon, this.field_148305_h);
            }

            bufferedimage.getRGB(0, 0, bufferedimage.getWidth(), bufferedimage.getHeight(), this.field_148305_h.getTextureData(), 0, bufferedimage.getWidth());
            this.field_148305_h.updateDynamicTexture();
        }
    }

    public ServerData getServerData()
    {
        return this.server;
    }
}
