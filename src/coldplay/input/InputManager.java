package coldplay.input;

import coldplay.ColdPlay;
import coldplay.event.EventKey;
import coldplay.event.EventTarget;
import coldplay.event.EventUpdate;
import coldplay.module.Module;
import coldplay.module.ModuleManager;
import coldplay.util.ChatUtil;

import net.minecraft.client.Minecraft;

/** Routes key presses to the GUI-open bind and module keybinds. */
public class InputManager {

    private boolean hintShown;

    @EventTarget
    public void onUpdate(EventUpdate event) {
        if (!event.isPre()) {
            return;
        }
        if (!hintShown && Minecraft.getMinecraft().thePlayer != null) {
            hintShown = true;
            ChatUtil.info("Press Right Shift to open the ColdPlay GUI. Type .help for commands.");
        }
    }

    @EventTarget
    public void onKey(EventKey event) {
        int key = event.getKey();
        ColdPlay coldPlay = ColdPlay.getInstance();

        if (key == coldPlay.getConfigManager().getGuiOpenKey()) {
            Minecraft.getMinecraft().displayGuiScreen(coldPlay.getConfigManager().getGuiStyle().createScreen());
            return;
        }

        boolean changed = false;
        ModuleManager moduleManager = coldPlay.getModuleManager();
        for (Module module : moduleManager.getModules()) {
            if (module.getKeyBind() == key) {
                changed |= moduleManager.toggle(module);
            }
        }
        if (changed) {
            coldPlay.saveConfig();
        }
    }
}
