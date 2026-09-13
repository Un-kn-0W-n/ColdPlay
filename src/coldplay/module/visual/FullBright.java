package coldplay.module.visual;

import coldplay.module.Category;
import coldplay.module.Module;
import coldplay.setting.NumberSetting;
import net.minecraft.client.Minecraft;

public class FullBright extends Module {
    private static final float FULL_BRIGHT_GAMMA = 100.0F;

    private final NumberSetting savedGamma = add(new NumberSetting("Saved Gamma", 1.0, 0.0, 1.0, 0.01));

    public FullBright() {
        super("FullBright", Category.VISUAL, "Maxes brightness so caves and night are fully lit.");
        savedGamma.visibleWhen(() -> false); // hidden; persisted in case a crash leaves gamma at 100
    }

    @Override
    protected void onEnable() {
        float current = Minecraft.getMinecraft().gameSettings.gammaSetting;
        if (current <= 1.0F) { // in range, so not our own 100
            savedGamma.set((double) current);
        }
        Minecraft.getMinecraft().gameSettings.gammaSetting = FULL_BRIGHT_GAMMA;
    }

    @Override
    protected void onDisable() {
        Minecraft.getMinecraft().gameSettings.gammaSetting = savedGamma.get().floatValue();
    }
}
