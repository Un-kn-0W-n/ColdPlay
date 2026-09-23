package coldplay.config;

import coldplay.gui.GuiStyle;
import coldplay.hud.HudState;
import coldplay.module.Category;
import coldplay.module.ModuleManager;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import org.lwjglx.input.Keyboard;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** Loads and saves config.json and the named profiles under configs/; ConfigCodec owns the JSON shapes. */
public class ConfigManager {

    public static class PanelState {
        public int x;
        public int y;
        public boolean collapsed;

        public PanelState(int x, int y, boolean collapsed) {
            this.x = x;
            this.y = y;
            this.collapsed = collapsed;
        }
    }

    private final File directory;
    private final JsonStore<JsonObject> rootStore;
    private final ConfigCodec codec = new ConfigCodec();
    private final Map<Category, PanelState> panelStates = new HashMap<Category, PanelState>();
    private final HudState hudState;
    private int panelLayoutWidth; // 0 until the first load or ClickGUI open
    private int panelLayoutHeight;
    private GuiStyle guiStyle = GuiStyle.SMOKE;
    private int[] milkWindow; // top-left, null until the Milk window is first moved

    public ConfigManager(HudState hudState) {
        this.hudState = hudState;
        this.directory = new File(Minecraft.getMinecraft().mcDataDir, "coldplay");
        this.rootStore = jsonStore(new File(directory, "config.json"), "config");
    }

    public int getGuiOpenKey() {
        return Keyboard.KEY_RSHIFT;
    }

    public GuiStyle getGuiStyle() {
        return guiStyle;
    }

    public void setGuiStyle(GuiStyle guiStyle) {
        this.guiStyle = guiStyle;
    }

    public int[] getMilkWindow() {
        return milkWindow;
    }

    public void setMilkWindow(int x, int y) {
        milkWindow = new int[]{x, y};
    }

    public PanelState getPanelState(Category category) {
        return panelStates.get(category);
    }

    public void putPanelState(Category category, int x, int y, boolean collapsed) {
        panelStates.put(category, new PanelState(x, y, collapsed));
    }

    /** Re-anchors panel positions to a new screen size; call before layout and before saving. */
    public void reflowPanels(int width, int height) {
        if (width <= 0 || height <= 0) {
            return;
        }
        if (panelLayoutWidth > 0 && panelLayoutHeight > 0) {
            for (PanelState state : panelStates.values()) {
                state.x = HudState.reanchor(state.x, panelLayoutWidth, width);
                state.y = HudState.reanchor(state.y, panelLayoutHeight, height);
            }
        }
        panelLayoutWidth = width;
        panelLayoutHeight = height;
    }

    public void load(ModuleManager moduleManager) {
        JsonObject root = rootStore.load();
        codec.applyRoot(root, moduleManager, panelStates, hudState);
        guiStyle = codec.readGuiStyle(root);
        milkWindow = codec.readMilkWindow(root);
        // Panels and HUD anchors share the persisted reference screen size.
        panelLayoutWidth = hudState.getLayoutWidth();
        panelLayoutHeight = hudState.getLayoutHeight();
    }

    public void save(ModuleManager moduleManager) {
        reflowPanels(hudState.getLayoutWidth(), hudState.getLayoutHeight());
        rootStore.save(codec.encodeRoot(moduleManager, panelStates, hudState, guiStyle, milkWindow));
    }

    private File configsDir() {
        return new File(directory, "configs");
    }

    private File profileFile(String name) {
        return new File(configsDir(), name + ".json");
    }

    private JsonStore<JsonObject> profileStore(String name) {
        return jsonStore(profileFile(name), "profile '" + name + "'");
    }

    private static JsonStore<JsonObject> jsonStore(File file, String label) {
        return new JsonStore<JsonObject>(file, JsonObject.class,
                new Supplier<JsonObject>() {
                    public JsonObject get() {
                        return new JsonObject();
                    }
                }, label);
    }

    public List<String> listConfigs() {
        List<String> names = new ArrayList<String>();
        File[] files = configsDir().listFiles();
        if (files == null) {
            return names;
        }
        for (File file : files) {
            String name = file.getName();
            if (name.endsWith(".json")) {
                names.add(name.substring(0, name.length() - ".json".length()));
            }
        }
        Collections.sort(names, String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    public boolean saveProfile(String name, ModuleManager moduleManager) {
        return profileStore(name).save(codec.encodeProfile(moduleManager));
    }

    public boolean loadProfile(String name, ModuleManager moduleManager) {
        return profileFile(name).exists()
                && codec.applyProfile(profileStore(name).load(), moduleManager);
    }

    public boolean renameProfile(String oldName, String newName) {
        File source = profileFile(oldName);
        File target = profileFile(newName);
        return source.exists() && !target.exists() && source.renameTo(target);
    }

    public boolean deleteProfile(String name) {
        return profileFile(name).delete();
    }

    /** Strips characters illegal in Windows/NTFS filenames and trailing dots/spaces. */
    public static String sanitizeName(String raw) {
        if (raw == null) {
            return "";
        }
        String result = raw.replaceAll("[\\\\/:*?\"<>|\\x00-\\x1F]", "").trim();
        int end = result.length();
        while (end > 0 && (result.charAt(end - 1) == '.' || result.charAt(end - 1) == ' ')) {
            end--;
        }
        return result.substring(0, end);
    }
}
