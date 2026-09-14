package coldplay.gui.account;

import coldplay.account.Alt;
import coldplay.account.AltAuthService;
import coldplay.account.AltManager;
import coldplay.account.MicrosoftAuth;
import coldplay.account.ProxyConfig;
import coldplay.account.ProxyManager;
import coldplay.gui.BackgroundShader;
import coldplay.gui.CenteredPanelLayout;
import coldplay.gui.CustomSearchField;
import coldplay.gui.CustomTextInput;
import coldplay.gui.Theme;
import coldplay.gui.StyledButton;
import coldplay.util.RenderUtil;
import coldplay.util.font.CustomFont;
import coldplay.util.font.Fonts;

import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.MathHelper;
import net.minecraft.util.Session;

import org.lwjglx.input.Keyboard;
import org.lwjglx.input.Mouse;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/** Account and proxy editor. Logins run on a worker thread; the session is applied on the client thread. */
public class GuiAccountManager extends GuiScreen {

    private static final int ROW_H = 22;
    private static final int PAD = 14;
    private static final int DELETE_SIZE = 12;
    private static final AtomicLong LOGIN_ATTEMPTS = new AtomicLong();

    private final GuiScreen parentScreen;
    private final AltAuthService authService = new AltAuthService();
    private final MicrosoftAuth microsoftAuth = new MicrosoftAuth();

    // proxy slots must stay last and contiguous; isProxyFieldFocused relies on it
    private static final int F_NONE = -1, F_TOKEN = 0, F_NAME = 1, F_HOST = 2, F_USER = 3, F_PASS = 4;
    private static final int[] MAX_LEN = {4096, 16, 256, 128, 256};
    private static final boolean[] MASKED = {true, false, false, false, true};
    private final String[] fields = {"", "", "", "", ""};
    private int focused = F_TOKEN;
    private int cursorCounter;

    private boolean crackedTab;

    private volatile boolean busy;
    private volatile String status = "";
    private volatile int statusColor = Theme.TEXT_DIM;
    private int scrollOffset;

    private int left, right, top, bottom, panelW;
    private int titleY, subtitleY, tokenLabelY;
    private int tabY, tabH, tabW, crackedTabX;
    private int tokenX, tokenY, tokenW, tokenH;
    private int loginX, loginY, loginW, loginH;
    private int statusY, savedLabelY;
    private int proxyLabelY, proxyFieldH;
    private int proxyHostX, proxyHostY, proxyHostW;
    private int proxyCredY, proxyUserX, proxyUserW, proxyPassX, proxyPassW;
    private int saveProxyX, saveProxyY, saveProxyW, saveProxyH;
    private int proxyToggleX, proxyToggleW;
    private int listX, listY, listW, listBottom;
    private int backX, backY, backW, backH;

    public GuiAccountManager(final GuiScreen parentScreen) {
        this.parentScreen = parentScreen;
    }

    @Override
    public void initGui() {
        Keyboard.enableRepeatEvents(true);
        Fonts.load();
        AltManager.getInstance().load(this.mc.mcDataDir);
        final ProxyManager proxyManager = ProxyManager.getInstance();
        proxyManager.load(this.mc.mcDataDir);

        this.fields[F_HOST] = proxyManager.getProxyAddress();
        this.fields[F_USER] = proxyManager.getProxyUsername();
        this.fields[F_PASS] = proxyManager.getProxyPassword();
    }

    private void updateLayout() {
        CenteredPanelLayout panel = CenteredPanelLayout.create(this.width, this.height, 360, 20, 24);
        this.left = panel.getLeft();
        this.right = panel.getRight();
        this.top = panel.getTop();
        this.bottom = panel.getBottom();
        this.panelW = panel.getWidth();

        this.titleY = this.top + 10;
        this.subtitleY = this.titleY + 22;

        this.tabY = this.subtitleY + 16;
        this.tabH = 16;
        this.tabW = (this.panelW - PAD * 2 - 6) / 2;
        this.crackedTabX = this.left + PAD + this.tabW + 6;

        this.tokenLabelY = this.tabY + this.tabH + 8;

        this.tokenX = this.left + PAD;
        this.tokenW = this.panelW - PAD * 2;
        this.tokenY = this.tokenLabelY + 13;
        this.tokenH = 14;

        this.loginX = this.left + PAD;
        this.loginW = this.panelW - PAD * 2;
        this.loginY = this.tokenY + this.tokenH + 8;
        this.loginH = 16;

        this.statusY = this.loginY + this.loginH + 7;

        this.proxyFieldH = 14;
        final int credGap = 6;
        this.proxyLabelY = this.statusY + 14;

        this.proxyHostX = this.left + PAD;
        this.proxyHostW = this.panelW - PAD * 2;
        this.proxyHostY = this.proxyLabelY + 12;

        this.proxyCredY = this.proxyHostY + this.proxyFieldH + 4;
        this.proxyUserX = this.left + PAD;
        this.proxyUserW = (this.panelW - PAD * 2 - credGap) / 2;
        this.proxyPassX = this.proxyUserX + this.proxyUserW + credGap;
        this.proxyPassW = this.right - PAD - this.proxyPassX;

        this.proxyToggleW = 70;
        this.saveProxyX = this.left + PAD;
        this.saveProxyW = this.panelW - PAD * 2 - this.proxyToggleW - 6;
        this.saveProxyY = this.proxyCredY + this.proxyFieldH + 6;
        this.saveProxyH = 16;
        this.proxyToggleX = this.saveProxyX + this.saveProxyW + 6;

        this.savedLabelY = this.saveProxyY + this.saveProxyH + 10;

        CenteredPanelLayout.Rect back = panel.bottomButton(PAD, 16, 8);
        this.backX = back.x;
        this.backY = back.y;
        this.backW = back.width;
        this.backH = back.height;

        this.listX = this.left + PAD;
        this.listW = this.panelW - PAD * 2;
        this.listY = this.savedLabelY + 14;
        this.listBottom = this.backY - 8;
    }

    @Override
    public void updateScreen() {
        this.cursorCounter++;
    }

    @Override
    public void drawScreen(final int mouseX, final int mouseY, final float partialTicks) {
        Fonts.load();
        this.updateLayout();

        BackgroundShader.draw(this.width, this.height, this.mc.displayWidth, this.mc.displayHeight);
        RenderUtil.rect(this.left, this.top, this.panelW, this.bottom - this.top, Theme.BODY);
        Theme.contour(this.left, this.top, this.panelW, this.bottom - this.top);

        final CustomFont font = Fonts.medium;
        final CustomFont small = Fonts.list;

        BackgroundShader.drawSectionTitle("Account Manager", this.width / 2f, this.titleY);
        final Session session = this.mc.getSession();
        if (font != null && session != null) {
            final boolean premium = session.getSessionType() == Session.Type.MOJANG;
            final String label = session.getUsername() + (premium ? " (Premium)" : " (Offline)");
            font.drawCentered(label, this.width / 2f, this.subtitleY, premium ? Theme.FROST : Theme.TEXT_DIM);
        }

        drawButton(font, "Premium", this.left + PAD, this.tabY, this.tabW, this.tabH, mouseX, mouseY, true);
        drawButton(font, "Cracked", this.crackedTabX, this.tabY, this.tabW, this.tabH, mouseX, mouseY, true);
        final int selX = this.crackedTab ? this.crackedTabX : this.left + PAD;
        RenderUtil.rect(selX, this.tabY + this.tabH - Theme.TICK_PX, this.tabW, Theme.TICK_PX, Theme.FROST);

        if (font != null) {
            font.drawString(this.crackedTab ? "Username" : "Refresh Token (M.C...)",
                    this.left + PAD, this.tokenLabelY, Theme.TEXT_DIM);
        }
        this.drawField(font, this.crackedTab ? F_NAME : F_TOKEN,
                this.tokenX, this.tokenY, this.tokenW, this.tokenH, "");
        drawButton(font, !this.crackedTab && this.busy ? "Validating..." : "Login",
                this.loginX, this.loginY, this.loginW, this.loginH, mouseX, mouseY, !this.busy);

        if (font != null && this.status != null && !this.status.isEmpty()) {
            font.drawCentered(this.status, this.width / 2f, this.statusY, this.statusColor);
        }

        final ProxyManager proxyManager = ProxyManager.getInstance();
        final boolean proxyConfigured = proxyManager.hasProxyConfigured();
        final boolean proxyOn = proxyManager.isProxyEnabled();
        if (font != null) {
            font.drawString("Proxy (SOCKS5)", this.left + PAD, this.proxyLabelY, Theme.TEXT_DIM);
        }
        if (small != null && proxyConfigured) {
            final String proxyState = proxyOn ? "ACTIVE" : "OFF";
            small.drawString(proxyState, this.right - PAD - small.getStringWidth(proxyState), this.proxyLabelY, proxyOn ? Theme.FROST : Theme.TEXT_MUTE);
        }
        this.drawField(font, F_HOST, this.proxyHostX, this.proxyHostY, this.proxyHostW, this.proxyFieldH, "host:port");
        this.drawField(font, F_USER, this.proxyUserX, this.proxyCredY, this.proxyUserW, this.proxyFieldH, "username");
        this.drawField(font, F_PASS, this.proxyPassX, this.proxyCredY, this.proxyPassW, this.proxyFieldH, "password");
        drawButton(font, "Save Proxy", this.saveProxyX, this.saveProxyY, this.saveProxyW, this.saveProxyH, mouseX, mouseY, true);
        drawButton(font, proxyOn ? "On" : "Off", this.proxyToggleX, this.saveProxyY, this.proxyToggleW, this.saveProxyH, mouseX, mouseY, proxyConfigured);

        if (font != null) {
            font.drawString(this.crackedTab ? "Saved Cracked" : "Saved Premium",
                    this.left + PAD, this.savedLabelY, Theme.TEXT_DIM);
        }
        this.drawList(font, mouseX, mouseY);

        drawButton(font, "Back", this.backX, this.backY, this.backW, this.backH, mouseX, mouseY, true);
    }

    private void drawList(final CustomFont font, final int mouseX, final int mouseY) {
        final List<Alt> alts = AltManager.getInstance().getAlts(this.crackedTab);
        final int listH = this.listBottom - this.listY;

        if (alts.isEmpty()) {
            if (font != null) {
                font.drawCentered("No saved alts", this.width / 2f, this.listY + listH / 2f - font.getHeight() / 2f, Theme.TEXT_MUTE);
            }
            this.scrollOffset = 0;
            RenderUtil.outline(this.listX, this.listY, this.listX + this.listW, this.listBottom, 1, Theme.SEP);
            return;
        }

        final int contentH = alts.size() * ROW_H;
        this.scrollOffset = MathHelper.clamp_int(this.scrollOffset, 0, Math.max(0, contentH - listH));

        final int sf = new ScaledResolution(this.mc).getScaleFactor();
        RenderUtil.beginScissor(this.listX, this.listY, this.listW, listH, sf);
        for (int i = 0; i < alts.size(); i++) {
            final int rowY = this.rowTop(i);
            if (!this.rowVisible(rowY)) {
                continue;
            }
            this.drawRow(font, alts.get(i), rowY, mouseX, mouseY);
            if (i > 0) {
                RenderUtil.rectBounds(this.listX, rowY, this.listX + this.listW, rowY + 1, Theme.SEP);
            }
        }
        RenderUtil.endScissor();
        this.drawScrollbar(contentH, listH);
        // frame goes last, over any row hover fill
        RenderUtil.outline(this.listX, this.listY, this.listX + this.listW, this.listBottom, 1, Theme.SEP);
    }

    private void drawRow(final CustomFont font, final Alt alt, final int rowY, final int mouseX, final int mouseY) {
        final int delX = this.delX();
        final int delY = delY(rowY);
        final boolean delHover = this.hitsDelete(rowY, mouseX, mouseY);
        if (delHover) {
            RenderUtil.rect(delX, delY, DELETE_SIZE, DELETE_SIZE, Theme.HOVER_LIFT);
        } else if (RenderUtil.hovered(mouseX, mouseY, this.listX, rowY, this.listW, ROW_H)) {
            RenderUtil.rect(this.listX, rowY, this.listW, ROW_H, Theme.HOVER_LIFT);
        }

        final String name = alt.getUsername() == null ? "?" : alt.getUsername();
        final Session session = this.mc.getSession();
        if (session != null && name.equals(session.getUsername())) {
            RenderUtil.rect(this.listX + Theme.CONTOUR_PX, rowY, Theme.TICK_PX, ROW_H, Theme.FROST);
        }
        if (font != null) {
            font.drawString(name, this.listX + 6, rowY + (ROW_H - font.getHeight()) / 2, Theme.TEXT);
            font.drawCenteredInRect("x", delX, delY, DELETE_SIZE, DELETE_SIZE,
                    delHover ? Theme.DANGER : Theme.TEXT_DIM);
        }
    }

    /** Indicator only; the thumb is not draggable. */
    private void drawScrollbar(final int contentH, final int listH) {
        final int maxScroll = contentH - listH;
        final int trackH = listH - 4;
        if (maxScroll <= 0 || trackH <= 0) {
            return;
        }
        final int trackX = this.listX + this.listW - 2;
        final int trackY = this.listY + 2;
        final int thumbH = RenderUtil.scrollThumbHeight(trackH, listH, contentH);
        RenderUtil.rect(trackX, trackY, 1, trackH, Theme.SEP);
        RenderUtil.rect(trackX,
                trackY + RenderUtil.scrollThumbOffset(trackH, thumbH, this.scrollOffset, maxScroll),
                1, thumbH, Theme.FROST);
    }

    private int rowTop(final int index) {
        return this.listY + index * ROW_H - this.scrollOffset;
    }

    private boolean rowVisible(final int rowTop) {
        return rowTop + ROW_H >= this.listY && rowTop <= this.listBottom;
    }

    private int delX() {
        return this.listX + this.listW - DELETE_SIZE - 4;
    }

    private static int delY(final int rowTop) {
        return rowTop + (ROW_H - DELETE_SIZE) / 2;
    }

    private boolean hitsDelete(final int rowTop, final int mouseX, final int mouseY) {
        return RenderUtil.hovered(mouseX, mouseY, this.delX(), delY(rowTop), DELETE_SIZE, DELETE_SIZE);
    }

    private void drawField(final CustomFont font, final int slot, final int x, final int y,
                           final int w, final int h, final String placeholder) {
        CustomSearchField.draw(font, x, y, w, h, this.fields[slot], this.focused == slot, placeholder,
                this.cursorCounter, MASKED[slot]);
    }

    private void drawButton(final CustomFont font, final String label, final int x, final int y, final int w, final int h,
                            final int mouseX, final int mouseY, final boolean enabled) {
        StyledButton.draw(font, label, x, y, w, h, mouseX, mouseY, enabled, Theme.WELL, Theme.TEXT);
    }

    @Override
    public void handleMouseInput() throws IOException {
        // event coordinates, not the last frame's cursor
        final int mouseX = Mouse.getEventX() * this.width / this.mc.displayWidth;
        final int mouseY = this.height - Mouse.getEventY() * this.height / this.mc.displayHeight - 1;
        final int wheel = Mouse.getEventDWheel();
        super.handleMouseInput();
        if (wheel != 0 && RenderUtil.hovered(mouseX, mouseY, this.listX, this.listY, this.listW, this.listBottom - this.listY)) {
            this.scrollOffset -= Math.round(wheel / 120f) * ROW_H; // clamped by drawList
        }
    }

    @Override
    protected void mouseClicked(final int mouseX, final int mouseY, final int mouseButton) throws IOException {
        if (mouseButton != 0) {
            return;
        }

        this.focused = F_NONE;
        if (RenderUtil.hovered(mouseX, mouseY, this.tokenX, this.tokenY, this.tokenW, this.tokenH)) {
            this.focused = this.crackedTab ? F_NAME : F_TOKEN;
        } else if (RenderUtil.hovered(mouseX, mouseY, this.proxyHostX, this.proxyHostY, this.proxyHostW, this.proxyFieldH)) {
            this.focused = F_HOST;
        } else if (RenderUtil.hovered(mouseX, mouseY, this.proxyUserX, this.proxyCredY, this.proxyUserW, this.proxyFieldH)) {
            this.focused = F_USER;
        } else if (RenderUtil.hovered(mouseX, mouseY, this.proxyPassX, this.proxyCredY, this.proxyPassW, this.proxyFieldH)) {
            this.focused = F_PASS;
        }
        if (this.focused != F_NONE) {
            this.cursorCounter = 0;
            return;
        }

        if (RenderUtil.hovered(mouseX, mouseY, this.backX, this.backY, this.backW, this.backH)) {
            this.mc.displayGuiScreen(this.parentScreen);
            return;
        }

        if (RenderUtil.hovered(mouseX, mouseY, this.left + PAD, this.tabY, this.tabW, this.tabH)) {
            this.switchTab(false);
            return;
        }
        if (RenderUtil.hovered(mouseX, mouseY, this.crackedTabX, this.tabY, this.tabW, this.tabH)) {
            this.switchTab(true);
            return;
        }

        if (RenderUtil.hovered(mouseX, mouseY, this.loginX, this.loginY, this.loginW, this.loginH)) {
            if (this.crackedTab) {
                this.loginOffline();
            } else if (!this.busy) {
                this.startLogin();
            }
            return;
        }

        if (RenderUtil.hovered(mouseX, mouseY, this.saveProxyX, this.saveProxyY, this.saveProxyW, this.saveProxyH)) {
            this.applyProxyFromFields();
            return;
        }

        if (RenderUtil.hovered(mouseX, mouseY, this.proxyToggleX, this.saveProxyY, this.proxyToggleW, this.saveProxyH)) {
            final ProxyManager manager = ProxyManager.getInstance();
            if (manager.hasProxyConfigured()) {
                final boolean nowEnabled = !manager.isProxyEnabled();
                manager.setProxyEnabled(nowEnabled);
                this.setStatus(nowEnabled ? "Proxy enabled: " + manager.getProxyAddress() : "Proxy disabled", nowEnabled ? Theme.FROST : Theme.TEXT_DIM);
            } else {
                this.setStatus("No proxy configured", Theme.TEXT_DIM);
            }
            return;
        }

        this.handleListClick(mouseX, mouseY);
    }

    private void handleListClick(final int mouseX, final int mouseY) {
        if (mouseY < this.listY || mouseY > this.listBottom) {
            return;
        }
        final List<Alt> alts = AltManager.getInstance().getAlts(this.crackedTab);
        for (int i = 0; i < alts.size(); i++) {
            final int rowY = this.rowTop(i);
            if (!this.rowVisible(rowY)
                    || !RenderUtil.hovered(mouseX, mouseY, this.listX, rowY, this.listW, ROW_H)) {
                continue;
            }
            final Alt alt = alts.get(i);

            if (this.hitsDelete(rowY, mouseX, mouseY)) {
                if (AltManager.getInstance().remove(alt.getUuid())) {
                    this.setStatus("Removed " + alt.getUsername(), Theme.TEXT_DIM);
                }
                return;
            }

            // Prevent an in-flight premium login from overwriting an offline session.
            if (!this.busy) {
                if (alt.isCracked()) {
                    this.applyOffline(alt.getUsername());
                } else if (!alt.getRefreshToken().isEmpty()) {
                    this.runLogin(alt.getRefreshToken(), true);
                } else {
                    this.runLogin(alt.getToken(), false);
                }
            }
            return;
        }
    }

    @Override
    protected void keyTyped(final char typedChar, final int keyCode) throws IOException {
        // a focused field takes every key except Enter, which submits
        if (this.focused != F_NONE
                && keyCode != Keyboard.KEY_RETURN && keyCode != Keyboard.KEY_NUMPADENTER) {
            final CustomTextInput.EditResult edit = CustomTextInput.edit(
                    this.fields[this.focused], typedChar, keyCode, MAX_LEN[this.focused]);
            this.fields[this.focused] = edit.getValue();
            if (!edit.isFocused()) {
                this.focused = F_NONE;
            }
            return;
        }
        if (keyCode == Keyboard.KEY_ESCAPE) {
            this.mc.displayGuiScreen(this.parentScreen);
            return;
        }
        if (keyCode == Keyboard.KEY_RETURN || keyCode == Keyboard.KEY_NUMPADENTER) {
            if (this.isProxyFieldFocused()) {
                this.applyProxyFromFields();
            } else if (this.crackedTab) {
                this.loginOffline();
            } else if (!this.busy) {
                this.startLogin();
            }
        }
    }

    private void switchTab(final boolean cracked) {
        if (this.crackedTab == cracked) {
            return;
        }
        this.crackedTab = cracked;
        this.scrollOffset = 0;
        this.setStatus("", Theme.TEXT_DIM);
        this.focused = cracked ? F_NAME : F_TOKEN;
    }

    private void loginOffline() {
        if (this.busy) {
            return;
        }
        final String name = this.fields[F_NAME].trim();
        if (!name.matches("[a-zA-Z0-9_]{3,16}")) {
            this.setStatus("Invalid username: 3-16 letters, numbers, _", Theme.DANGER);
            return;
        }
        this.applyOffline(name);
    }

    private void applyOffline(final String name) {
        final String uuid = EntityPlayer.getOfflineUUID(name).toString();
        this.mc.setSession(new Session(name, uuid, "", "legacy"));
        AltManager.getInstance().upsert(new Alt(name, uuid, "", null, true));
        this.setStatus("Offline session: " + name, Theme.FROST);
    }

    private void startLogin() {
        final String trimmed = this.fields[F_TOKEN].trim();
        this.fields[F_TOKEN] = "";
        // anything that is not a JWT access token is treated as a refresh token
        this.runLogin(trimmed, !looksLikeJwt(trimmed));
    }

    private static boolean looksLikeJwt(final String token) {
        return token.startsWith("eyJ") && token.indexOf('.') != token.lastIndexOf('.');
    }

    private void runLogin(final String input, final boolean isRefreshToken) {
        if (this.busy) {
            return;
        }
        final long attemptId = beginLoginAttempt();
        this.setStatus(isRefreshToken ? "Refreshing session..." : "Validating account...", Theme.TEXT_DIM);
        this.busy = true;

        final Thread worker = new Thread(() -> {
            try {
                final Session session;
                final String refreshToken;
                if (isRefreshToken) {
                    final MicrosoftAuth.MsaAuthResult result = this.microsoftAuth.loginWithRefreshToken(input);
                    session = this.authService.login(result.minecraftToken);
                    refreshToken = result.refreshToken;
                } else {
                    session = this.authService.login(input);
                    refreshToken = null;
                }
                this.mc.addScheduledTask(() -> {
                    if (!isLatestAttempt(attemptId)) {
                        return;
                    }
                    this.mc.setSession(session);
                    AltManager.getInstance().upsert(new Alt(
                            session.getUsername(), session.getPlayerID(), session.getToken(),
                            refreshToken, false));
                    this.busy = false;
                    this.setStatus("Logged in as " + session.getUsername(), Theme.FROST);
                });
            } catch (final Exception e) {
                this.mc.addScheduledTask(() -> {
                    if (!isLatestAttempt(attemptId)) {
                        return;
                    }
                    this.busy = false;
                    this.setStatus("Login failed: " + cleanMessage(e), Theme.DANGER);
                });
            }
        }, "ColdPlay Login");
        worker.setDaemon(true);
        worker.start();
    }

    static long beginLoginAttempt() {
        return LOGIN_ATTEMPTS.incrementAndGet();
    }

    static boolean isLatestAttempt(long attemptId) {
        return attemptId == LOGIN_ATTEMPTS.get();
    }

    private void setStatus(final String status, final int color) {
        this.status = status;
        this.statusColor = color;
    }

    private void applyProxyFromFields() {
        try {
            final ProxyManager manager = ProxyManager.getInstance();
            manager.apply(this.fields[F_HOST], this.fields[F_USER], this.fields[F_PASS]);
            final ProxyConfig active = manager.getActiveProxy();
            if (active == null) {
                this.fields[F_HOST] = "";
                this.fields[F_USER] = "";
                this.fields[F_PASS] = "";
                this.setStatus("Proxy cleared", Theme.TEXT_DIM);
            } else {
                this.setStatus("Proxy set: " + active.getDisplayAddress() + " (enabled)", Theme.FROST);
            }
        } catch (final IllegalArgumentException e) {
            this.setStatus(e.getMessage(), Theme.DANGER);
        }
    }

    private boolean isProxyFieldFocused() {
        return this.focused >= F_HOST;
    }

    private static String cleanMessage(final Exception e) {
        final String msg = e.getMessage();
        return msg == null || msg.trim().isEmpty() ? e.getClass().getSimpleName() : msg;
    }

    @Override
    public void onGuiClosed() {
        Keyboard.enableRepeatEvents(false);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
