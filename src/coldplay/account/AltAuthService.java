package coldplay.account;

import com.google.gson.Gson;
import net.minecraft.util.Session;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Resolves a Minecraft-services bearer token to a session. Callers must run network work off the render thread.
 */
public final class AltAuthService {
    private static final String PROFILE_URL = "https://api.minecraftservices.com/minecraft/profile";
    private static final Gson GSON = new Gson();

    public Session login(final String tokenInput) throws IOException {
        final String token = tokenInput == null ? "" : tokenInput.trim();
        if (token.isEmpty()) {
            throw new IOException("MS token is required.");
        }

        final Map<String, String> headers = new HashMap<String, String>();
        headers.put("Authorization", "Bearer " + token);
        headers.put("Accept", "application/json");

        final String responseBody;
        try {
            responseBody = Http.request(PROFILE_URL, "GET", headers, null);
        } catch (final Http.HttpStatusException e) {
            if (e.code == 401 || e.code == 403) {
                throw new IOException("Token rejected by Minecraft Services.");
            }
            throw new IOException("Minecraft Services returned HTTP " + e.code + (e.body.isEmpty() ? "." : ": " + e.body));
        }

        final ProfileResponse profile = GSON.fromJson(responseBody, ProfileResponse.class);
        if (profile == null || isEmpty(profile.id) || isEmpty(profile.name)) {
            throw new IOException("Minecraft profile response was missing id or name.");
        }

        return new Session(profile.name, profile.id, token, "mojang");
    }

    private static boolean isEmpty(final String value) {
        return value == null || value.trim().isEmpty();
    }

    private static class ProfileResponse {
        private String id;
        private String name;
    }
}
