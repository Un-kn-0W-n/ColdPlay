package coldplay.gui.account;

import coldplay.account.Alt;
import coldplay.account.AltAuthService;
import coldplay.account.AltManager;
import coldplay.account.MicrosoftAuth;
import coldplay.account.ProxyConfig;
import coldplay.account.ProxyManager;
import coldplay.gui.CustomTextInput;
import coldplay.gui.GlassList;
import coldplay.gui.GlassMenuButton;
import coldplay.gui.GlassScreen;
import coldplay.gui.GlassShader;
import coldplay.gui.GlassUi;
import coldplay.gui.Icons;
import coldplay.gui.Theme;
import coldplay.util.RenderUtil;
import coldplay.util.font.CustomFont;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.Session;

import org.lwjglx.input.Keyboard;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/** Account and proxy editor. Logins run on a worker thread; the session is applied on the client thread. */
public class GuiAccountManager extends GlassScreen {

    private static final float ROW_H = 34.5F;
    private static final float ROW_STEP = 37.5F;
    private static final float COL_W = 255.0F;
    private static final float FIELD_H = 25.5F;
    private static final String[] TABS = {"Premium", "Cracked"};
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
    private volatile int statusColor = GlassUi.DIM;

    private final GlassList list = new GlassList();
    private float leftX;
    private float bodyY;
    private GuiButton loginButton;

    public GuiAccountManager(final GuiScreen parentScreen) {
        this.parentScreen = parentScreen;
    }

    @Override
    public void initGui() {
        Keyboard.enableRepeatEvents(true);
        AltManager.getInstance().load(this.mc.mcDataDir);
        final ProxyManager proxyManager = ProxyManager.getInstance();
        proxyManager.load(this.mc.mcDataDir);

        this.fields[F_HOST] = proxyManager.getProxyAddress();
        this.fields[F_USER] = proxyManager.getProxyUsername();
        this.fields[F_PASS] = proxyManager.getProxyPassword();

        this.setCard(570.0F, 355.5F);
        this.leftX = this.cardX + PAD;
        this.bodyY = this.cardY + 55.5F;
        final float rightX = this.leftX + COL_W + 30.75F;
        this.list.place(rightX, this.bodyY + 19.5F, this.cardX + this.cardW - PAD - rightX, 265.5F);
        this.addBack(0);
        this.buttonList.add(this.loginButton = new GlassMenuButton(1, this.leftX, this.bodyY + 83.25F, COL_W, FIELD_H,
                "Login", Icons.Icon.LOGIN, GlassMenuButton.Style.PRIMARY, false));
        this.buttonList.add(new GlassMenuButton(3, this.leftX, this.bodyY + 234.0F, COL_W - 78.0F, FIELD_H,
                "Save Proxy", Icons.Icon.CHECK, GlassMenuButton.Style.CHIP_FIT, false));
    }

    @Override
    public void updateScreen() {
        this.cursorCounter++;
    }

    @Override
    protected void actionPerformed(final GuiButton button) {
        if (button.id == 0) {
            this.mc.displayGuiScreen(this.parentScreen);
        } else if (button.id == 1) {
            if (this.crackedTab) {
                this.loginOffline();
            } else {
                this.startLogin();
            }
        } else if (button.id == 3) {
            this.applyProxyFromFields();
        }
    }

    @Override
    protected void drawCard(final int mouseX, final int mouseY, final float partialTicks) {
        this.drawHeader("Play", "Alt Manager");
        this.drawSession();
        this.loginButton.displayString = !this.crackedTab && this.busy ? "Validating..." : "Login";
        this.loginButton.enabled = !this.busy;

        final float b = this.bodyY;
        final float x = this.leftX;
        GlassUi.segmented(x, b, COL_W, FIELD_H, TABS, this.crackedTab ? 1 : 0,
                RenderUtil.hovered(mouseX, mouseY, x, b, COL_W, FIELD_H) ? GlassUi.segmentAt(x, COL_W, 2, mouseX) : -1, true);
        final CustomFont label = GlassUi.LABEL.get();
        label.drawString(this.crackedTab ? "Username" : "Refresh Token (M.C...)", x, b + 36.0F + (9.75F - label.getHeight()) / 2.0F, GlassUi.DIM);
        final int slot = this.crackedTab ? F_NAME : F_TOKEN;
        GlassUi.field(this.crackedTab ? GlassUi.BODY.get() : GlassUi.MONO_BODY.get(), x, b + 50.25F, COL_W, FIELD_H,
                this.fields[slot], this.focused == slot,
                this.crackedTab ? "3-16 letters, numbers, _" : "M.C... refresh token or eyJ... access token",
                this.cursorCounter, MASKED[slot], this.crackedTab ? Icons.Icon.USER : Icons.Icon.KEY);

        if (this.status != null && !this.status.isEmpty()) {
            final CustomFont body = GlassUi.BODY.get();
            final String text = body.trimToWidth(this.status, Math.round(COL_W - 10.5F), "...");
            final float tw = 10.5F + body.getStringWidth(text);
            final float tx = x + (COL_W - tw) / 2.0F;
            int dot = this.statusColor;
            if (this.busy) {
                final double phase = Minecraft.getSystemTime() / 1200.0 % 1.0;
                dot = Theme.withAlpha(dot, (int) (64 + 191 * (0.5 - 0.5 * Math.cos(phase * Math.PI * 2))));
            }
            GlassShader.rect(tx, b + 120.0F, 4.5F, 4.5F, 2.25F, dot, dot);
            body.drawString(text, tx + 10.5F, b + 116.25F + (12.0F - body.getHeight()) / 2.0F, this.statusColor);
        }
        GlassUi.divider(x, b + 140.25F, COL_W);

        final ProxyManager proxyManager = ProxyManager.getInstance();
        final boolean proxyConfigured = proxyManager.hasProxyConfigured();
        final boolean proxyOn = proxyManager.isProxyEnabled();
        GlassUi.section("Proxy (SOCKS5)", x, b + 151.5F + 2.25F);
        if (proxyConfigured) {
            final String state = proxyOn ? "ACTIVE" : "OFF";
            final CustomFont mono = GlassUi.MONO.get();
            final float bw = 9.0F + mono.getStringWidth(state, 0.45F);
            final float bx = x + COL_W - bw;
            final int fill = proxyOn ? 0x1F84D2E3 : 0x0FFFFFFF;
            GlassShader.rect(bx, b + 151.5F, bw, 13.5F, 3.0F, fill, fill);
            mono.drawString(state, bx + 4.5F, b + 151.5F + (13.5F - mono.getHeight()) / 2.0F,
                    proxyOn ? GlassUi.FROST : 0x80FFFFFF, 0.45F);
        }
        GlassUi.field(GlassUi.MONO_BODY.get(), x, b + 171.0F, COL_W, FIELD_H, this.fields[F_HOST], this.focused == F_HOST,
                "host:port", this.cursorCounter, false, null);
        final float half = (COL_W - 6.0F) / 2.0F;
        GlassUi.field(GlassUi.BODY.get(), x, b + 202.5F, half, FIELD_H, this.fields[F_USER], this.focused == F_USER,
                "username", this.cursorCounter, false, null);
        GlassUi.field(GlassUi.BODY.get(), x + half + 6.0F, b + 202.5F, half, FIELD_H, this.fields[F_PASS], this.focused == F_PASS,
                "password", this.cursorCounter, true, null);
        this.drawProxyToggle(mouseX, mouseY, proxyConfigured, proxyOn);

        GlassShader.rect(x + COL_W + 15.0F, b, 0.75F, 285.0F, 0.0F, GlassUi.LINE, GlassUi.LINE);
        this.drawAlts(mouseX, mouseY);
        this.drawButtons(mouseX, mouseY, partialTicks);
    }

    private void drawSession() {
        final Session session = this.mc.getSession();
        if (session == null) {
            return;
        }
        final Session.Type type = session.getSessionType();
        final boolean premium = type == Session.Type.MOJANG || type == Session.Type.MSA;
        final String name = session.getUsername();
        final String kind = premium ? "Premium" : "Offline";
        final CustomFont font = GlassUi.BODY_MEDIUM.get();
        final float w = 3.75F + 16.5F + 6.0F + font.getStringWidth(name) + 6.0F + GlassUi.badgeWidth(kind) + 7.5F;
        final float x = this.cardX + this.cardW - PAD - w;
        final float y = this.cardY + PAD_TOP + 1.5F;
        GlassShader.rect(x, y, w, 24.0F, 6.0F, 0x0AFFFFFF, 0x0AFFFFFF);
        GlassShader.stroke(x, y, w, 24.0F, 6.0F, GlassUi.LINE);
        this.avatar(x + 3.75F, y + 3.75F, 16.5F, name);
        font.drawString(name, x + 26.25F, y + (24.0F - font.getHeight()) / 2.0F, 0xFFFFFFFF);
        GlassUi.badge(x + 32.25F + font.getStringWidth(name), y + 4.5F, kind,
                premium ? 0x1F84D2E3 : 0x0FFFFFFF, premium ? GlassUi.FROST : 0xB8FFFFFF);
    }

    private void avatar(final float x, final float y, final float size, final String name) {
        GlassShader.rect(x, y, size, size, size * 0.22F, 0x2484D2E3, 0x2484D2E3);
        final CustomFont font = GlassUi.STRONG.get();
        final String initial = name.isEmpty() ? "?" : name.substring(0, 1).toUpperCase(Locale.ROOT);
        font.drawCentered(initial, x + size / 2.0F, y + (size - font.getHeight()) / 2.0F, GlassUi.FROST);
    }

    private float toggleX() {
        return this.leftX + COL_W - 72.0F;
    }

    private void drawProxyToggle(final int mouseX, final int mouseY, final boolean configured, final boolean on) {
        final float x = this.toggleX();
        final float y = this.bodyY + 234.0F;
        final boolean hover = configured && RenderUtil.hovered(mouseX, mouseY, x, y, 72.0F, FIELD_H);
        GlassUi.chip(x, y, 72.0F, FIELD_H, "", null, hover ? 1.0F : 0.0F, configured, false, false);
        final CustomFont font = GlassUi.BODY.get();
        final String label = on ? "On" : "Off";
        final float content = 21.0F + 7.5F + font.getStringWidth(label);
        final float cx = x + (72.0F - content) / 2.0F;
        GlassUi.toggle(cx, y + 6.75F, on);
        font.drawString(label, cx + 28.5F, y + (FIELD_H - font.getHeight()) / 2.0F, configured ? 0xCCFFFFFF : GlassUi.MUTE);
    }

    private void drawAlts(final int mouseX, final int mouseY) {
        final List<Alt> alts = AltManager.getInstance().getAlts(this.crackedTab);
        final CustomFont mono = GlassUi.MONO.get();
        GlassUi.section(this.crackedTab ? "Saved Cracked" : "Saved Premium", this.list.x, this.bodyY + 2.25F);
        final String count = alts.size() + "/50";
        mono.drawString(count, this.list.x + this.list.w - mono.getStringWidth(count), this.bodyY + (13.5F - mono.getHeight()) / 2.0F, GlassUi.MUTE);

        GlassUi.well(this.list.x, this.list.y, this.list.w, this.list.h);
        if (alts.isEmpty()) {
            final CustomFont body = GlassUi.BODY.get();
            body.drawCentered("No saved alts", this.list.x + this.list.w / 2.0F,
                    this.list.y + (this.list.h - body.getHeight()) / 2.0F, GlassUi.MUTE);
            return;
        }
        this.list.setContent(alts.size() * ROW_STEP + 3.0F);
        final int hover = this.altAt(alts, mouseX, mouseY);
        final Session session = this.mc.getSession();
        final float x = this.list.x + 3.0F;
        final float w = this.list.w - 6.0F;
        this.clip(this.list.x, this.list.y, this.list.w, this.list.h);
        for (int i = 0; i < alts.size(); i++) {
            final float y = this.list.top() + 3.0F + i * ROW_STEP;
            if (y + ROW_H < this.list.y || y > this.list.y + this.list.h) {
                continue;
            }
            final String name = alts.get(i).getUsername() == null ? "?" : alts.get(i).getUsername();
            final boolean current = session != null && name.equals(session.getUsername());
            GlassUi.entry(x, y, w, ROW_H, i == hover, current);
            this.avatar(x + 6.75F, y + 6.75F, 21.0F, name);
            final float removeX = x + w - 6.0F - 19.5F;
            float right = removeX - 3.0F;
            if (current) {
                right -= this.inUse(right, y + 10.5F);
            }
            final CustomFont font = GlassUi.ROW.get();
            font.drawString(font.trimToWidth(name, Math.round(right - 6.0F - (x + 36.75F)), "..."), x + 36.75F,
                    y + (ROW_H - font.getHeight()) / 2.0F, GlassUi.ICE);
            if (i == hover || current) {
                GlassUi.ghost(removeX, y + 7.5F, 19.5F, Icons.Icon.CLOSE,
                        RenderUtil.hovered(mouseX, mouseY, removeX, y + 7.5F, 19.5F, 19.5F), true);
            }
        }
        this.unclip();
        this.list.drawScrollbar();
    }

    /** The "In use" tag ending at {@code right}; returns its width plus the gap before it. */
    private float inUse(final float right, final float y) {
        final CustomFont font = GlassUi.LABEL.get();
        final float w = 5.25F + 3.75F + 4.5F + font.getStringWidth("In use") + 5.25F;
        final float x = right - w;
        GlassShader.rect(x, y, w, 13.5F, 3.0F, 0x1F84D2E3, 0x1F84D2E3);
        GlassShader.rect(x + 5.25F, y + 4.875F, 3.75F, 3.75F, 1.875F, GlassUi.FROST, GlassUi.FROST);
        font.drawString("In use", x + 13.5F, y + (13.5F - font.getHeight()) / 2.0F, GlassUi.FROST);
        return w + 6.0F;
    }

    private int altAt(final List<Alt> alts, final int mouseX, final int mouseY) {
        if (!this.list.contains(mouseX, mouseY)) {
            return -1;
        }
        final float offset = mouseY - this.list.top() - 3.0F;
        final int index = (int) Math.floor(offset / ROW_STEP);
        return index >= 0 && index < alts.size() && offset - index * ROW_STEP < ROW_H ? index : -1;
    }

    @Override
    protected void cardScrolled(final int mouseX, final int mouseY, final int wheel) {
        if (this.list.contains(mouseX, mouseY)) {
            this.list.wheel(wheel, ROW_STEP);
        }
    }

    @Override
    protected void cardClicked(final int mouseX, final int mouseY, final int mouseButton) {
        if (mouseButton != 0) {
            return;
        }
        final float b = this.bodyY;
        final float x = this.leftX;
        final float half = (COL_W - 6.0F) / 2.0F;
        this.focused = F_NONE;
        if (RenderUtil.hovered(mouseX, mouseY, x, b + 50.25F, COL_W, FIELD_H)) {
            this.focused = this.crackedTab ? F_NAME : F_TOKEN;
        } else if (RenderUtil.hovered(mouseX, mouseY, x, b + 171.0F, COL_W, FIELD_H)) {
            this.focused = F_HOST;
        } else if (RenderUtil.hovered(mouseX, mouseY, x, b + 202.5F, half, FIELD_H)) {
            this.focused = F_USER;
        } else if (RenderUtil.hovered(mouseX, mouseY, x + half + 6.0F, b + 202.5F, half, FIELD_H)) {
            this.focused = F_PASS;
        }
        if (this.focused != F_NONE) {
            this.cursorCounter = 0;
            return;
        }

        if (RenderUtil.hovered(mouseX, mouseY, x, b, COL_W, FIELD_H)) {
            final int tab = GlassUi.segmentAt(x, COL_W, 2, mouseX);
            if (tab >= 0) {
                this.switchTab(tab == 1);
            }
            return;
        }

        if (RenderUtil.hovered(mouseX, mouseY, this.toggleX(), b + 234.0F, 72.0F, FIELD_H)) {
            final ProxyManager manager = ProxyManager.getInstance();
            if (manager.hasProxyConfigured()) {
                final boolean nowEnabled = !manager.isProxyEnabled();
                manager.setProxyEnabled(nowEnabled);
                this.setStatus(nowEnabled ? "Proxy enabled: " + manager.getProxyAddress() : "Proxy disabled", nowEnabled ? GlassUi.FROST : GlassUi.DIM);
            } else {
                this.setStatus("No proxy configured", GlassUi.DIM);
            }
            return;
        }

        this.handleListClick(mouseX, mouseY);
    }

    private void handleListClick(final int mouseX, final int mouseY) {
        final List<Alt> alts = AltManager.getInstance().getAlts(this.crackedTab);
        final int index = this.altAt(alts, mouseX, mouseY);
        if (index < 0) {
            return;
        }
        final Alt alt = alts.get(index);
        final float y = this.list.top() + 3.0F + index * ROW_STEP;
        final float removeX = this.list.x + this.list.w - 9.0F - 19.5F;
        if (RenderUtil.hovered(mouseX, mouseY, removeX, y + 7.5F, 19.5F, 19.5F)) {
            if (AltManager.getInstance().remove(alt.getUuid())) {
                this.setStatus("Removed " + alt.getUsername(), GlassUi.DIM);
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

    @Override
    protected void drawFooter() {
        this.versionChip();
        this.hintChip("Enter", "Log in", "Esc", "Back");
    }

    private void switchTab(final boolean cracked) {
        if (this.crackedTab == cracked) {
            return;
        }
        this.crackedTab = cracked;
        this.list.setContent(0.0F);
        this.setStatus("", GlassUi.DIM);
        this.focused = cracked ? F_NAME : F_TOKEN;
    }

    private void loginOffline() {
        if (this.busy) {
            return;
        }
        final String name = this.fields[F_NAME].trim();
        if (!name.matches("[a-zA-Z0-9_]{3,16}")) {
            this.setStatus("Invalid username: 3-16 letters, numbers, _", GlassUi.DANGER);
            return;
        }
        this.applyOffline(name);
    }

    private void applyOffline(final String name) {
        final String uuid = EntityPlayer.getOfflineUUID(name).toString();
        this.mc.setSession(new Session(name, uuid, "", "legacy"));
        AltManager.getInstance().upsert(new Alt(name, uuid, "", null, true));
        this.setStatus("Offline session: " + name, GlassUi.FROST);
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
        this.setStatus(isRefreshToken ? "Refreshing session..." : "Validating account...", GlassUi.DIM);
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
                    this.setStatus("Logged in as " + session.getUsername(), GlassUi.FROST);
                });
            } catch (final Exception e) {
                this.mc.addScheduledTask(() -> {
                    if (!isLatestAttempt(attemptId)) {
                        return;
                    }
                    this.busy = false;
                    this.setStatus("Login failed: " + cleanMessage(e), GlassUi.DANGER);
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
                this.setStatus("Proxy cleared", GlassUi.DIM);
            } else {
                this.setStatus("Proxy set: " + active.getDisplayAddress() + " (enabled)", GlassUi.FROST);
            }
        } catch (final IllegalArgumentException e) {
            this.setStatus(e.getMessage(), GlassUi.DANGER);
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
