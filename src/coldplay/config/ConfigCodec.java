package coldplay.config;

import coldplay.friend.FriendManager;
import coldplay.hud.HudState;
import coldplay.module.Category;
import coldplay.module.Module;
import coldplay.module.ModuleManager;
import coldplay.setting.Setting;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.Map;

/** JSON codec for the full client config and for module-only profiles. */
public final class ConfigCodec {

    public JsonObject encodeRoot(ModuleManager moduleManager,
                                 Map<Category, ConfigManager.PanelState> panelStates,
                                 HudState hudState) {
        JsonObject root = new JsonObject();
        root.add("layout", encodeLayout(hudState));
        root.add("panels", encodePanels(panelStates));
        root.add("hud", encodeHud(hudState));
        root.add("modules", encodeModules(moduleManager));
        root.add("friends", encodeFriends());
        return root;
    }

    public JsonObject encodeProfile(ModuleManager moduleManager) {
        JsonObject root = new JsonObject();
        root.add("modules", encodeModules(moduleManager));
        return root;
    }

    public void applyRoot(JsonObject root, ModuleManager moduleManager,
                          Map<Category, ConfigManager.PanelState> panelStates,
                          HudState hudState) {
        if (root == null) {
            return;
        }
        applyLayout(root, hudState);
        applyPanels(root, panelStates);
        applyHud(root, hudState);
        applyProfile(root, moduleManager);
        applyFriends(root);
    }

    /** Returns false when the root has no usable "modules" section. */
    public boolean applyProfile(JsonObject root, ModuleManager moduleManager) {
        if (root == null || !root.has("modules") || !root.get("modules").isJsonObject()) {
            return false;
        }
        applyModules(moduleManager, root.getAsJsonObject("modules"));
        return true;
    }

    /** Screen size the anchors were captured at, so a config saved at another GUI scale re-anchors correctly. */
    private JsonObject encodeLayout(HudState hudState) {
        JsonObject layout = new JsonObject();
        layout.addProperty("w", hudState.getLayoutWidth());
        layout.addProperty("h", hudState.getLayoutHeight());
        return layout;
    }

    private void applyLayout(JsonObject root, HudState hudState) {
        if (root.has("layout") && root.get("layout").isJsonObject()) {
            JsonObject layout = root.getAsJsonObject("layout");
            hudState.setLayoutSize(optInt(layout, "w", 0), optInt(layout, "h", 0));
        }
    }

    private JsonObject encodePanels(Map<Category, ConfigManager.PanelState> panelStates) {
        JsonObject panels = new JsonObject();
        for (Map.Entry<Category, ConfigManager.PanelState> entry : panelStates.entrySet()) {
            ConfigManager.PanelState state = entry.getValue();
            JsonObject panel = new JsonObject();
            panel.addProperty("x", state.x);
            panel.addProperty("y", state.y);
            panel.addProperty("collapsed", state.collapsed);
            panels.add(entry.getKey().name(), panel);
        }
        return panels;
    }

    private void applyPanels(JsonObject root,
                             Map<Category, ConfigManager.PanelState> panelStates) {
        if (!root.has("panels") || !root.get("panels").isJsonObject()) {
            return;
        }
        JsonObject panels = root.getAsJsonObject("panels");
        for (Category category : Category.values()) {
            try {
                JsonElement element = panels.get(category.name());
                if (element != null && element.isJsonObject()) {
                    JsonObject panel = element.getAsJsonObject();
                    panelStates.put(category, new ConfigManager.PanelState(
                            optInt(panel, "x", 0),
                            optInt(panel, "y", 0),
                            panel.has("collapsed") && panel.get("collapsed").getAsBoolean()));
                }
            } catch (Exception ignored) {
            }
        }
    }

    private JsonObject encodeHud(HudState hudState) {
        JsonObject hud = new JsonObject();
        for (Map.Entry<String, HudState.Position> entry : hudState.getPositions().entrySet()) {
            JsonObject position = new JsonObject();
            position.addProperty("x", entry.getValue().x);
            position.addProperty("y", entry.getValue().y);
            hud.add(entry.getKey(), position);
        }
        return hud;
    }

    private void applyHud(JsonObject root, HudState hudState) {
        if (!root.has("hud") || !root.get("hud").isJsonObject()) {
            return;
        }
        for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject("hud").entrySet()) {
            try {
                if (entry.getValue().isJsonObject()) {
                    JsonObject position = entry.getValue().getAsJsonObject();
                    hudState.put(entry.getKey(),
                            optInt(position, "x", 0),
                            optInt(position, "y", 0));
                }
            } catch (Exception ignored) {
            }
        }
    }

    private JsonArray encodeFriends() {
        JsonArray friends = new JsonArray();
        FriendManager manager = FriendManager.getInstance();
        for (String name : manager.getFriends()) {
            JsonObject friend = new JsonObject();
            friend.addProperty("name", name);
            friend.addProperty("color", manager.getColor(name));
            friends.add(friend);
        }
        return friends;
    }

    private void applyFriends(JsonObject root) {
        try {
            if (!root.has("friends") || !root.get("friends").isJsonArray()) {
                return;
            }
            Map<String, Integer> entries = new LinkedHashMap<String, Integer>();
            for (JsonElement element : root.getAsJsonArray("friends")) {
                try {
                    if (element.isJsonObject()) {
                        JsonObject friend = element.getAsJsonObject();
                        entries.put(friend.get("name").getAsString(), optInt(friend, "color", 0));
                    } else {
                        entries.put(element.getAsString(), 0);
                    }
                } catch (Exception ignored) {
                }
            }
            FriendManager.getInstance().setFriends(entries);
        } catch (Exception ignored) {
        }
    }

    private JsonObject encodeModules(ModuleManager moduleManager) {
        JsonObject modules = new JsonObject();
        for (Module module : moduleManager.getModules()) {
            JsonObject encoded = new JsonObject();
            encoded.addProperty("enabled", module.isEnabled());
            encoded.addProperty("keyBind", module.getKeyBind());
            JsonObject settings = new JsonObject();
            for (Setting<?> setting : module.getSettings()) {
                JsonElement value = setting.toJson();
                if (value != null) {
                    settings.add(setting.getName(), value);
                }
            }
            encoded.add("settings", settings);
            modules.add(module.getName(), encoded);
        }
        return modules;
    }

    private void applyModules(ModuleManager moduleManager, JsonObject modules) {
        for (Module module : moduleManager.getModules()) {
            try {
                JsonElement element = modules.get(module.getName());
                if (element == null || !element.isJsonObject()) {
                    continue;
                }
                JsonObject encoded = element.getAsJsonObject();
                if (encoded.has("keyBind")) {
                    module.setKeyBind(optInt(encoded, "keyBind", module.getKeyBind()));
                }
                if (encoded.has("settings") && encoded.get("settings").isJsonObject()) {
                    applySettings(module, encoded.getAsJsonObject("settings"));
                }
                if (encoded.has("enabled")
                        && !moduleManager.setEnabled(module, encoded.get("enabled").getAsBoolean())) {
                    System.err.println("[ColdPlay] Failed to restore module " + module.getName());
                }
            } catch (Exception ignored) {
            }
        }
    }

    private void applySettings(Module module, JsonObject encoded) {
        for (Setting<?> setting : module.getSettings()) {
            JsonElement value = encoded.get(setting.getName());
            if (value == null) {
                continue;
            }
            try {
                setting.fromJson(value);
            } catch (Exception ignored) {
            }
        }
    }

    private static int optInt(JsonObject object, String key, int fallback) {
        try {
            return object.has(key) ? object.get(key).getAsInt() : fallback;
        } catch (Exception ignored) {
            return fallback;
        }
    }
}
