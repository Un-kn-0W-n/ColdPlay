package coldplay.account;

import io.netty.handler.proxy.Socks5ProxyHandler;

import java.net.InetSocketAddress;

/**
 * SOCKS5 connection settings. An empty username selects unauthenticated proxy access.
 */
public final class ProxyConfig {
    private final String host;
    private final int port;
    private final String username;
    private final String password;

    private ProxyConfig(final String host, final int port, final String username, final String password) {
        this.host = host;
        this.port = port;
        this.username = username;
        this.password = password;
    }

    /**
     * Parses {@code host:port} (or {@code [ipv6]:port}) plus optional credentials.
     *
     * @return {@code null} when {@code proxyInput} is blank (meaning "no proxy")
     * @throws IllegalArgumentException if the address is malformed or the port is not 1-65535
     */
    public static ProxyConfig parse(final String proxyInput, final String usernameInput, final String passwordInput) {
        final String proxy = proxyInput == null ? "" : proxyInput.trim();
        final String username = usernameInput == null ? "" : usernameInput.trim();
        final String password = passwordInput == null ? "" : passwordInput;

        if (proxy.isEmpty()) {
            return null;
        }

        final String host;
        final String portPart;

        if (proxy.startsWith("[")) {
            final int close = proxy.indexOf(']');
            if (close < 0 || close + 2 > proxy.length() || proxy.charAt(close + 1) != ':') {
                throw new IllegalArgumentException("Proxy must be host:port.");
            }
            host = proxy.substring(1, close).trim();
            portPart = proxy.substring(close + 2).trim();
        } else {
            final int split = proxy.lastIndexOf(':');
            if (split <= 0 || split == proxy.length() - 1) {
                throw new IllegalArgumentException("Proxy must be host:port.");
            }
            host = proxy.substring(0, split).trim();
            portPart = proxy.substring(split + 1).trim();
        }

        if (host.isEmpty()) {
            throw new IllegalArgumentException("Proxy host is required.");
        }

        final int port;
        try {
            port = Integer.parseInt(portPart);
        } catch (final NumberFormatException exception) {
            throw new IllegalArgumentException("Proxy port must be a number.");
        }

        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Proxy port must be between 1 and 65535.");
        }

        return new ProxyConfig(host, port, username, password);
    }

    public String getUsername() {
        return this.username;
    }

    public String getPassword() {
        return this.password;
    }

    public boolean hasCredentials() {
        return this.username != null && !this.username.isEmpty();
    }

    public String getDisplayAddress() {
        return this.host + ":" + this.port;
    }

    public InetSocketAddress getSocketAddress() {
        return new InetSocketAddress(this.host, this.port);
    }

    public Socks5ProxyHandler createHandler() {
        final Socks5ProxyHandler handler = hasCredentials()
                ? new Socks5ProxyHandler(getSocketAddress(), this.username, this.password)
                : new Socks5ProxyHandler(getSocketAddress());
        handler.setConnectTimeoutMillis(10000);
        return handler;
    }
}
