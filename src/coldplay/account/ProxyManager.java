package coldplay.account;

import coldplay.config.JsonStore;

import net.minecraft.client.Minecraft;

import java.io.File;
import java.util.function.Supplier;

/** Global SOCKS5 proxy for game-server connections, saved in coldplay/proxy.json. */
public final class ProxyManager {
    private static final ProxyManager INSTANCE = new ProxyManager();

    private ProxyConfig activeProxy;
    private String proxyAddress = "";
    private String proxyUsername = "";
    private String proxyPassword = "";
    private boolean proxyEnabled;
    private JsonStore<Stored> store;
    private boolean loaded;

    private ProxyManager() {
    }

    public static ProxyManager getInstance() {
        return INSTANCE;
    }

    public synchronized void load(final File mcDataDir) {
        if (this.loaded) {
            return;
        }
        this.store = createStore(mcDataDir);
        this.loaded = true;
        final Stored stored = this.store.load();
        this.proxyAddress = stored.proxyAddress == null ? "" : stored.proxyAddress;
        this.proxyUsername = stored.proxyUsername == null ? "" : stored.proxyUsername;
        this.proxyPassword = stored.proxyPassword == null ? "" : stored.proxyPassword;
        this.proxyEnabled = stored.proxyEnabled != null && stored.proxyEnabled;
        try {
            this.activeProxy = ProxyConfig.parse(this.proxyAddress, this.proxyUsername, this.proxyPassword);
        } catch (final IllegalArgumentException ignored) {
            this.activeProxy = null;
        }
    }

    /** Loads on first call. Null when no proxy is set or it is disabled. */
    public synchronized ProxyConfig getActiveProxy() {
        if (!this.loaded) {
            final Minecraft mc = Minecraft.getMinecraft();
            if (mc != null && mc.mcDataDir != null) {
                load(mc.mcDataDir);
            }
        }
        return this.proxyEnabled ? this.activeProxy : null;
    }

    /** True when a proxy is saved, enabled or not. */
    public synchronized boolean hasProxyConfigured() {
        return this.activeProxy != null;
    }

    public synchronized boolean isProxyEnabled() {
        return this.proxyEnabled;
    }

    public synchronized void setProxyEnabled(final boolean enabled) {
        this.proxyEnabled = enabled;
        save();
    }

    /** Proxied connections resolve the host remotely; direct ones use local DNS. */
    public net.minecraft.network.NetworkManager connect(final String ip, final int port,
            final boolean useNativeTransport) throws java.net.UnknownHostException {
        final ProxyConfig proxy = getActiveProxy();
        if (proxy != null) {
            return net.minecraft.network.NetworkManager.createNetworkManagerAndConnect(ip, port, useNativeTransport, proxy);
        }
        return net.minecraft.network.NetworkManager.createNetworkManagerAndConnect(
                java.net.InetAddress.getByName(ip), port, useNativeTransport);
    }

    /** Blank input clears the proxy. Throws IllegalArgumentException on a malformed address. */
    public synchronized void apply(final String address, final String user, final String pass) {
        final ProxyConfig parsed = ProxyConfig.parse(address, user, pass);
        this.activeProxy = parsed;
        this.proxyEnabled = parsed != null;
        this.proxyAddress = address == null ? "" : address.trim();
        this.proxyUsername = user == null ? "" : user.trim();
        this.proxyPassword = pass == null ? "" : pass;
        if (parsed == null) {
            this.proxyAddress = "";
            this.proxyUsername = "";
            this.proxyPassword = "";
        }
        save();
    }

    public synchronized String getProxyAddress() {
        return this.proxyAddress;
    }

    public synchronized String getProxyUsername() {
        return this.proxyUsername;
    }

    public synchronized String getProxyPassword() {
        return this.proxyPassword;
    }

    private JsonStore<Stored> createStore(File mcDataDir) {
        return new JsonStore<Stored>(
                new File(new File(mcDataDir, "coldplay"), "proxy.json"),
                Stored.class,
                new Supplier<Stored>() {
                    public Stored get() {
                        return new Stored();
                    }
                },
                "proxy");
    }

    private void save() {
        if (this.store == null) {
            final Minecraft mc = Minecraft.getMinecraft();
            if (mc == null || mc.mcDataDir == null) {
                return;
            }
            this.store = createStore(mc.mcDataDir);
        }
        final Stored stored = new Stored();
        stored.proxyAddress = this.proxyAddress;
        stored.proxyUsername = this.proxyUsername;
        stored.proxyPassword = this.proxyPassword;
        stored.proxyEnabled = this.proxyEnabled;
        this.store.save(stored);
    }

    private static final class Stored {
        private String proxyAddress;
        private String proxyUsername;
        private String proxyPassword;
        private Boolean proxyEnabled; // null in older files, treated as off
    }
}
