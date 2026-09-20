package coldplay.module.movement;

import coldplay.broker.SprintGuard;
import coldplay.event.EventPriority;
import coldplay.event.EventStrafe;
import coldplay.event.EventTarget;
import coldplay.event.EventUpdate;
import coldplay.module.Category;
import coldplay.module.Module;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.client.settings.KeyBinding;

public class InvMove extends Module {

    private boolean controllingKeys;

    public InvMove() {
        super("InvMove", Category.MOVEMENT, "Lets you move while an inventory/container GUI is open.");
    }

    @EventTarget
    public void onUpdate(EventUpdate event) {
        if (!event.isPre()) {
            return;
        }

        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null || mc.theWorld == null || !(mc.currentScreen instanceof GuiContainer)) {
            releaseKeys();
            return;
        }

        controllingKeys = true;
        updateMovementKeys(mc.gameSettings, true);
    }

    @EventTarget(priority = EventPriority.DRAIN)
    public void onStrafe(EventStrafe event) {
        if (Minecraft.getMinecraft().currentScreen instanceof GuiContainer) {
            SprintGuard.getInstance().suppress();
            event.setSneak(false);
        }
    }

    @Override
    protected void onDisable() {
        releaseKeys();
    }

    private void releaseKeys() {
        if (!controllingKeys) {
            return;
        }

        Minecraft mc = Minecraft.getMinecraft();
        GameSettings settings = mc.gameSettings;
        boolean physical = mc.thePlayer != null && mc.theWorld != null && mc.currentScreen == null;
        updateMovementKeys(settings, physical);
        setKeys(physical, settings.keyBindSneak, settings.keyBindSprint);
        controllingKeys = false;
    }

    private static void updateMovementKeys(GameSettings settings, boolean physical) {
        setKeys(physical, settings.keyBindForward, settings.keyBindBack, settings.keyBindLeft,
                settings.keyBindRight, settings.keyBindJump);
    }

    private static void setKeys(boolean physical, KeyBinding... keys) {
        for (KeyBinding key : keys) {
            KeyBinding.setKeyBindState(key.getKeyCode(), physical && GameSettings.isKeyDown(key));
        }
    }
}
