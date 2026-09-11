package coldplay.module.combat;

import coldplay.event.EventTarget;
import coldplay.event.EventUpdate;
import coldplay.module.Category;
import coldplay.module.Module;
import coldplay.setting.ModeSetting;
import coldplay.setting.NumberSetting;
import coldplay.util.CpsDelay;

import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;

import java.util.Random;

public class AutoClicker extends Module {
    private static final String LEFT = "Left", RIGHT = "Right";

    public final ModeSetting button = add(new ModeSetting("Button", LEFT, LEFT, RIGHT)
            .describe("Which mouse button to auto-click."));
    public final NumberSetting minCps = add(new NumberSetting("Min CPS", 8.0, 1.0, 20.0, 1.0)
            .describe("Slowest click rate (clicks per second)."));
    public final NumberSetting maxCps = add(new NumberSetting("Max CPS", 12.0, 1.0, 20.0, 1.0)
            .describe("Fastest click rate (clicks per second)."));

    private final Random random = new Random();
    private long nextClickAt;

    public AutoClicker() {
        super("AutoClicker", Category.COMBAT,
                "Auto-clicks at a randomized CPS while you hold the mouse button (drives vanilla clicks, no packets).");
    }

    @Override
    protected void onDisable() {
        nextClickAt = 0L;
    }

    @EventTarget
    public void onUpdate(EventUpdate event) {
        if (!event.isPre()) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null || mc.theWorld == null || mc.currentScreen != null) {
            nextClickAt = 0L;
            return;
        }

        KeyBinding kb = RIGHT.equals(button.get())
                ? mc.gameSettings.keyBindUseItem
                : mc.gameSettings.keyBindAttack;

        if (!kb.isKeyDown()) {
            nextClickAt = 0L;
            return;
        }

        long now = System.currentTimeMillis();
        if (nextClickAt == 0L) {
            // Vanilla handles the physical press; generated clicks start after the delay.
            nextClickAt = now + delayMs();
            return;
        }
        if (now >= nextClickAt) {
            // one generated click per tick (~20 CPS); queue more if higher CPS is ever allowed.
            KeyBinding.onTick(kb.getKeyCode());
            nextClickAt = now + delayMs();
        }
    }

    private long delayMs() {
        return CpsDelay.sample(random, minCps.get(), maxCps.get());
    }
}
