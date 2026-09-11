package coldplay.module.visual;

import coldplay.module.Category;
import coldplay.module.Module;
import coldplay.setting.NumberSetting;
import net.minecraft.client.Minecraft;

public class FullBright extends Module {
    private static final float FULL_BRIGHT_GAMMA = 100.0F;

    /** Persist the original gamma because a crash can leave FULL_BRIGHT_GAMMA in options.txt. */
    private final NumberSetting savedGamma = add(new NumberSetting("Saved Gamma", 1.0, 0.0, 1.0, 0.01));

    public FullBright() {
        super("FullBright", Category.VISUAL, "Maxes brightness so caves and night are fully lit.");
        savedGamma.visibleWhen(() -> false);   // bookkeeping, not a knob
    }

    @Override
    protected void onEnable() {
        float current = Minecraft.getMinecraft().gameSettings.gammaSetting;
        if (current <= 1.0F) {                 // in range, so it is the player's own setting
            savedGamma.set((double) current);
        }
        Minecraft.getMinecraft().gameSettings.gammaSetting = FULL_BRIGHT_GAMMA;
    }

    @Override
    protected void onDisable() {
        Minecraft.getMinecraft().gameSettings.gammaSetting = savedGamma.get().floatValue();
    }
}
