package coldplay.gui;

import coldplay.ColdPlay;
import coldplay.util.RenderUtil;
import coldplay.util.font.CustomFont;
import coldplay.util.font.Fonts;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import org.lwjglx.input.Mouse;

import java.io.IOException;

/**
 * A menu screen drawn as one Smoke glass card over the menu backdrop, or over the dimmed world in game.
 * The card shrinks about the screen center when the window is too small, so input is mapped back to card space.
 */
public abstract class GlassScreen extends GuiScreen {

    protected static final float PAD = 15.0F;
    protected static final float PAD_TOP = 16.5F;

    protected float cardX;
    protected float cardY;
    protected float cardW;
    protected float cardH;
    protected float scale = 1.0F;

    /** Centers a card of this size; call from initGui before placing anything. */
    protected void setCard(float w, float h) {
        this.cardW = w;
        this.cardH = h;
        this.cardX = (this.width - w) / 2.0F;
        this.cardY = (this.height - h) / 2.0F;
        this.scale = Math.min(1.0F, Math.min((this.width - 24.0F) / w, (this.height - 24.0F) / h));
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        if (this.mc.theWorld != null) {
            this.drawDefaultBackground();
        } else {
            BackgroundShader.draw(this.width, this.height, this.mc.displayWidth, this.mc.displayHeight);
        }
        GlStateManager.disableDepth();
        GlStateManager.enableAlpha();
        Fonts.load();
        GlassShader.capture();

        float cx = this.width / 2.0F;
        float cy = this.height / 2.0F;
        GlStateManager.pushMatrix();
        GlStateManager.translate(cx, cy, 0.0F);
        GlStateManager.scale(this.scale, this.scale, 1.0F);
        GlStateManager.translate(-cx, -cy, 0.0F);
        GlassShader.frost(this.cardX, this.cardY, this.cardW, this.cardH, 7.5F, GlassUi.CARD);
        this.drawCard(this.toCardX(mouseX), this.toCardY(mouseY), partialTicks);
        GlStateManager.popMatrix();

        if (this.mc.theWorld == null && cy + this.cardH * this.scale / 2.0F <= this.height - 40.0F) {
            this.drawFooter();
        }
    }

    /** Draws the card's content, buttons included, in card coordinates. */
    protected abstract void drawCard(int mouseX, int mouseY, float partialTicks);

    protected void drawButtons(int mouseX, int mouseY, float partialTicks) {
        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    /** Bottom corners of the screen, drawn only when the card leaves room. */
    protected void drawFooter() {
        versionChip();
    }

    protected GlassMenuButton addBack(int id) {
        GlassMenuButton back = new GlassMenuButton(id, this.cardX + PAD, this.cardY + PAD_TOP, 25.5F, 25.5F, "Back",
                Icons.Icon.CHEVRON_LEFT, GlassMenuButton.Style.ICON, false);
        this.buttonList.add(back);
        return back;
    }

    /** Section label over the title, beside the back button. */
    protected void drawHeader(String eyebrow, String title) {
        float x = this.cardX + PAD + 25.5F + 9.0F;
        GlassUi.section(eyebrow, x, this.cardY + PAD_TOP);
        CustomFont font = GlassUi.TITLE.get();
        font.drawString(title, x, this.cardY + PAD_TOP + 10.5F + (16.5F - font.getHeight()) / 2.0F, 0xFFFFFFFF);
    }

    /** Clips to a card-space box until {@link #unclip()}. */
    protected void clip(float x, float y, float w, float h) {
        float cx = this.width / 2.0F;
        float cy = this.height / 2.0F;
        RenderUtil.beginScissor(cx + (x - cx) * this.scale, cy + (y - cy) * this.scale, w * this.scale,
                h * this.scale, new ScaledResolution(this.mc).getScaleFactor());
    }

    protected void unclip() {
        RenderUtil.endScissor();
    }

    protected void versionChip() {
        CustomFont name = GlassUi.BODY_MEDIUM.get();
        CustomFont mono = GlassUi.MONO.get();
        String version = "v" + ColdPlay.VERSION;
        float w = 9.0F + name.getStringWidth(ColdPlay.NAME) + 6.0F + mono.getStringWidth(version) + 9.0F;
        float y = this.height - 37.5F;
        GlassShader.panel(15.0F, y, w, 22.5F, 6.0F, Glass.SMOKE);
        name.drawString(ColdPlay.NAME, 24.0F, y + (22.5F - name.getHeight()) / 2.0F, GlassUi.ICE);
        mono.drawString(version, 30.0F + name.getStringWidth(ColdPlay.NAME), y + (22.5F - mono.getHeight()) / 2.0F,
                0x80FFFFFF);
    }

    /** Bottom right chip: one plain line, or key and label pairs split by hairlines. */
    protected void hintChip(String... parts) {
        CustomFont body = GlassUi.BODY.get();
        float y = this.height - 37.5F;
        if (parts.length == 1) {
            float w = 18.0F + body.getStringWidth(parts[0]);
            GlassShader.panel(this.width - 15.0F - w, y, w, 22.5F, 6.0F, Glass.SMOKE);
            body.drawString(parts[0], this.width - 15.0F - w + 9.0F, y + (22.5F - body.getHeight()) / 2.0F, 0xB3FFFFFF);
            return;
        }
        float w = 4.5F;
        for (int i = 0; i < parts.length; i += 2) {
            w += GlassUi.kbdWidth(parts[i]) + 6.0F + body.getStringWidth(parts[i + 1]) + (i + 2 < parts.length ? 18.75F : 9.0F);
        }
        float x = this.width - 15.0F - w;
        GlassShader.panel(x, y, w, 22.5F, 6.0F, Glass.SMOKE);
        float cx = x + 4.5F;
        for (int i = 0; i < parts.length; i += 2) {
            cx += GlassUi.kbd(cx, y + 3.75F, parts[i]) + 6.0F;
            body.drawString(parts[i + 1], cx, y + (22.5F - body.getHeight()) / 2.0F, 0xB3FFFFFF);
            cx += body.getStringWidth(parts[i + 1]);
            if (i + 2 < parts.length) {
                GlassShader.rect(cx + 9.0F, y + 6.0F, 0.75F, 10.5F, 0.0F, GlassUi.LINE, GlassUi.LINE);
                cx += 18.75F;
            }
        }
    }

    protected int toCardX(int x) {
        return Math.round(this.width / 2.0F + (x - this.width / 2.0F) / this.scale);
    }

    protected int toCardY(int y) {
        return Math.round(this.height / 2.0F + (y - this.height / 2.0F) / this.scale);
    }

    @Override
    protected final void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        int x = this.toCardX(mouseX);
        int y = this.toCardY(mouseY);
        super.mouseClicked(x, y, mouseButton);
        if (this.mc.currentScreen == this) {
            this.cardClicked(x, y, mouseButton);
        }
    }

    @Override
    protected final void mouseReleased(int mouseX, int mouseY, int state) {
        int x = this.toCardX(mouseX);
        int y = this.toCardY(mouseY);
        super.mouseReleased(x, y, state);
        this.cardReleased(x, y, state);
    }

    @Override
    protected final void mouseClickMove(int mouseX, int mouseY, int button, long timeSinceLastClick) {
        this.cardDragged(this.toCardX(mouseX), this.toCardY(mouseY), button);
    }

    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        int wheel = Mouse.getEventDWheel();
        if (wheel != 0) {
            int x = Mouse.getEventX() * this.width / this.mc.displayWidth;
            int y = this.height - Mouse.getEventY() * this.height / this.mc.displayHeight - 1;
            this.cardScrolled(this.toCardX(x), this.toCardY(y), wheel);
        }
    }

    protected void cardClicked(int mouseX, int mouseY, int button) throws IOException {
    }

    protected void cardReleased(int mouseX, int mouseY, int button) {
    }

    protected void cardDragged(int mouseX, int mouseY, int button) {
    }

    protected void cardScrolled(int mouseX, int mouseY, int wheel) {
    }
}
