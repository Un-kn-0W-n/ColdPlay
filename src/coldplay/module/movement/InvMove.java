package coldplay.module.movement;

import coldplay.event.EventTarget;
import coldplay.event.EventUpdate;
import coldplay.module.Category;
import coldplay.module.Module;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.client.settings.KeyBinding;

public class InvMove extends Module {
    public InvMove() {
        super("InvMove", Category.MOVEMENT, "Lets you move while an inventory/container GUI is open.");
    }

    @EventTarget
    public void onUpdate(EventUpdate event) {
        if (!event.isPre()) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null || !(mc.currentScreen instanceof GuiContainer)) {
            return;
        }

        GameSettings gameSettings = mc.gameSettings;
        force(gameSettings.keyBindForward);
        force(gameSettings.keyBindBack);
        force(gameSettings.keyBindLeft);
        force(gameSettings.keyBindRight);
        force(gameSettings.keyBindJump);
        force(gameSettings.keyBindSneak);
    }

    private static void force(KeyBinding kb) {
        KeyBinding.setKeyBindState(kb.getKeyCode(), GameSettings.isKeyDown(kb));
    }
}
