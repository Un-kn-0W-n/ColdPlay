package coldplay.account;

/**
 * Saved account serialized by Gson to {@code coldplay/alts.json}. Tokens are stored in plaintext.
 * A missing refresh token identifies legacy accounts that reuse the stored access token.
 */
public class Alt {
    private String username;
    private String uuid;
    private String token;
    private String refreshToken; // Microsoft refresh token (null = legacy token-only alt)
    private boolean cracked; // Missing in older files defaults to premium.

    public Alt() {
    }

    public Alt(final String username, final String uuid, final String token, final String refreshToken,
               final boolean cracked) {
        this.username = username;
        this.uuid = uuid;
        this.token = token;
        this.refreshToken = refreshToken;
        this.cracked = cracked;
    }

    public String getUsername() {
        return this.username;
    }

    public String getUuid() {
        return this.uuid;
    }

    public String getToken() {
        return this.token == null ? "" : this.token;
    }

    public String getRefreshToken() {
        return this.refreshToken == null ? "" : this.refreshToken;
    }

    void setRefreshToken(final String refreshToken) {
        this.refreshToken = refreshToken;
    }

    public boolean isCracked() {
        return this.cracked;
    }
}
