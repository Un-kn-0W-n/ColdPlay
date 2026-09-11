package coldplay.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** Single JSON codec for settings made of named boolean cells. */
public final class BooleanGridCodec {
    private BooleanGridCodec() {
    }

    public static JsonElement encode(Iterable<? extends BooleanGridEntry> entries) {
        JsonObject grid = new JsonObject();
        for (BooleanGridEntry entry : entries) {
            grid.addProperty(entry.persistenceKey(), entry.isEnabled());
        }
        return grid;
    }

    public static void decode(JsonElement json, Iterable<? extends BooleanGridEntry> entries) {
        if (json == null || !json.isJsonObject()) {
            return;
        }
        JsonObject grid = json.getAsJsonObject();
        for (BooleanGridEntry entry : entries) {
            JsonElement value = grid.get(entry.persistenceKey());
            if (value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean()) {
                entry.setEnabled(value.getAsBoolean());
            }
        }
    }
}
