package coldplay.module.utility;

import coldplay.event.EventTarget;
import coldplay.event.EventUpdate;
import coldplay.module.Category;
import coldplay.module.Module;
import coldplay.setting.NumberSetting;

import net.minecraft.client.Minecraft;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;

public class FastPlace extends Module {
    private final NumberSetting delay = add(new NumberSetting("Delay", 0.0, 0.0, 4.0, 1.0).describe("Ticks between placements (0 = every tick, 4 = vanilla)."));

    public FastPlace() {
        super("FastPlace", Category.UTILITY, "Removes the cooldown between held block placements.");
    }

    @EventTarget
    public void onUpdate(EventUpdate event) {
        if (!event.isPre()) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null) {
            return;
        }
        // blocks only — rods/snowballs/food share rightClickDelayTimer, keep them vanilla
        ItemStack held = mc.thePlayer.getHeldItem();
        if (held == null || !(held.getItem() instanceof ItemBlock)) {
            return;
        }
        int target = delay.get().intValue();
        if (mc.rightClickDelayTimer > target) {
            mc.rightClickDelayTimer = target;
        }
    }
}