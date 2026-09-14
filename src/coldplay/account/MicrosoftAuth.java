package coldplay.account;

import com.google.gson.Gson;

import java.io.IOException;
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.Map;

/** Turns a Microsoft refresh token into a Minecraft access token via Xbox Live and XSTS. Blocking; run off the render thread. */
public final class MicrosoftAuth {
    private static final String CLIENT_ID = "00000000402B5328";
    private static final String TOKEN_URL = "https://login.live.com/oauth20_token.srf";
    private static final String XBL_URL = "https://user.auth.xboxlive.com/user/authenticate";
    private static final String XSTS_URL = "https://xsts.auth.xboxlive.com/xsts/authorize";
    private static final String MC_LOGIN_URL = "https://api.minecraftservices.com/authentication/login_with_xbox";

    private static final Gson GSON = new Gson();

    /** Minecraft access token plus the rotated refresh token to save. */
    public static final class MsaAuthResult {
        public final String minecraftToken;
        public final String refreshToken;

        MsaAuthResult(final String minecraftToken, final String refreshToken) {
            this.minecraftToken = minecraftToken;
            this.refreshToken = refreshToken;
        }
    }

    public MsaAuthResult loginWithRefreshToken(final String refreshTokenInput) throws IOException {
        final String refreshToken = refreshTokenInput == null ? "" : refreshTokenInput.trim();
        if (refreshToken.isEmpty()) {
            throw new IOException("Refresh token is required.");
        }

        final String form = "client_id=" + CLIENT_ID
                + "&grant_type=refresh_token"
                + "&scope=" + URLEncoder.encode("service::user.auth.xboxlive.com::MBI_SSL", "UTF-8")
                + "&refresh_token=" + URLEncoder.encode(refreshToken, "UTF-8");
        final TokenResponse tokenResponse = postForm(TOKEN_URL, form,
                "Refresh token rejected by Microsoft (expired? paste a new one).", TokenResponse.class);
        final String msAccessToken = tokenResponse == null ? null : tokenResponse.access_token;
        if (msAccessToken == null) {
            throw new IOException("Microsoft token response was missing access_token.");
        }
        // Microsoft may rotate the refresh token; keep ours if none came back.
        String newRefreshToken = tokenResponse.refresh_token;
        if (newRefreshToken == null || newRefreshToken.isEmpty()) {
            newRefreshToken = refreshToken;
        }

        // login.live.com/MBI_SSL requires "t="; the Azure/MSAL "d=" prefix is rejected here.
        final XblResponse xblResponse = postJson(XBL_URL, new XblRequest("t=" + msAccessToken),
                "Xbox Live authentication failed.", XblResponse.class);
        final String xblToken = xblResponse == null ? null : xblResponse.Token;
        final String userHash = extractUserHash(xblResponse);
        if (xblToken == null || userHash == null) {
            throw new IOException("Xbox Live response was missing a token or user hash.");
        }

        final XblResponse xstsResponse = postXsts(new XstsRequest(xblToken), XblResponse.class);
        final String xstsToken = xstsResponse == null ? null : xstsResponse.Token;
        if (xstsToken == null) {
            throw new IOException("XSTS response was missing a token.");
        }

        final McLoginResponse mcResponse = postJson(MC_LOGIN_URL,
                new McLoginRequest("XBL3.0 x=" + userHash + ";" + xstsToken),
                "Minecraft Services login failed.", McLoginResponse.class);
        final String minecraftToken = mcResponse == null ? null : mcResponse.access_token;
        if (minecraftToken == null) {
            throw new IOException("Minecraft login response was missing access_token.");
        }

        return new MsaAuthResult(minecraftToken, newRefreshToken);
    }

    private static <T> T postForm(final String url, final String body, final String rejectMessage,
                                   final Class<T> responseType) throws IOException {
        return send(url, "application/x-www-form-urlencoded", body, rejectMessage, responseType);
    }

    private static <T> T postJson(final String url, final Object requestBody, final String rejectMessage,
                                   final Class<T> responseType) throws IOException {
        return send(url, "application/json", GSON.toJson(requestBody), rejectMessage, responseType);
    }

    private static <T> T postXsts(final Object requestBody, final Class<T> responseType) throws IOException {
        return send(XSTS_URL, "application/json", GSON.toJson(requestBody),
                "Xbox security token (XSTS) request was rejected. The account may have no Xbox profile, be in an unsupported region, or need age verification.",
                responseType);
    }

    private static <T> T send(final String urlString, final String contentType, final String body,
                               final String rejectMessage, final Class<T> responseType) throws IOException {
        final Map<String, String> headers = new HashMap<String, String>();
        headers.put("Content-Type", contentType);
        headers.put("Accept", "application/json");

        final String responseBody;
        try {
            responseBody = Http.request(urlString, "POST", headers, body);
        } catch (final Http.HttpStatusException e) {
            if (e.code == 401 || e.code == 403) {
                throw new IOException(rejectMessage);
            }
            throw new IOException(rejectMessage + " (HTTP " + e.code + ")");
        }

        try {
            return GSON.fromJson(responseBody, responseType);
        } catch (final Exception e) {
            throw new IOException("Unexpected response from " + urlString + ".");
        }
    }

    private static String extractUserHash(final XblResponse response) {
        try {
            return response.DisplayClaims.xui[0].uhs;
        } catch (final Exception ignored) {
            return null;
        }
    }

    // Gson maps these field names directly to the case-sensitive wire format.

    private static final class TokenResponse {
        private String access_token;
        private String refresh_token;
    }

    private static final class XblRequest {
        private final XblRequestProperties Properties;
        private final String RelyingParty = "http://auth.xboxlive.com";
        private final String TokenType = "JWT";

        XblRequest(final String rpsTicket) {
            this.Properties = new XblRequestProperties(rpsTicket);
        }

        private static final class XblRequestProperties {
            private final String AuthMethod = "RPS";
            private final String SiteName = "user.auth.xboxlive.com";
            private final String RpsTicket;

            XblRequestProperties(final String rpsTicket) {
                this.RpsTicket = rpsTicket;
            }
        }
    }

    private static final class XstsRequest {
        private final XstsRequestProperties Properties;
        private final String RelyingParty = "rp://api.minecraftservices.com/";
        private final String TokenType = "JWT";

        XstsRequest(final String xblToken) {
            this.Properties = new XstsRequestProperties(xblToken);
        }

        private static final class XstsRequestProperties {
            private final String SandboxId = "RETAIL";
            private final String[] UserTokens;

            XstsRequestProperties(final String xblToken) {
                this.UserTokens = new String[] { xblToken };
            }
        }
    }

    private static final class XblResponse {
        private String Token;
        private DisplayClaims DisplayClaims;

        private static final class DisplayClaims {
            private Xui[] xui;
        }

        private static final class Xui {
            private String uhs;
        }
    }

    private static final class McLoginRequest {
        private final String identityToken;

        McLoginRequest(final String identityToken) {
            this.identityToken = identityToken;
        }
    }

    private static final class McLoginResponse {
        private String access_token;
    }
}
