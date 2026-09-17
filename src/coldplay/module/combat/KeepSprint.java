package coldplay.module.combat;

import coldplay.broker.SprintGuard;
import coldplay.event.EventAttackPerformed;
import coldplay.event.EventTarget;
import coldplay.module.Category;
import coldplay.module.Module;
import coldplay.setting.NumberSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.entity.player.EntityPlayer;

public class KeepSprint extends Module {
    public final NumberSetting chance = add(new NumberSetting("Chance", 100.0, 0.0, 100.0, 1.0).describe("Percent of qualifying hits to restore sprint on."));

    public KeepSprint() {
        super("KeepSprint", Category.COMBAT, "Restores sprint after a hit so the server keeps crediting your swings as sprint hits.");
    }

    @Override
    public String getSuffix() {
        return Math.round(chance.get()) + "%";
    }

    @EventTarget
    public void onAttack(EventAttackPerformed event) {
        EntityPlayerSP player = Minecraft.getMinecraft().thePlayer;
        if (player == null || !event.wasSprinting() || player.isSprinting()) {
            return;
        }
        if (!(event.getTarget() instanceof EntityPlayer)) {
            return;
        }
        if (Math.random() * 100.0 >= chance.get()) {
            return;
        }
        SprintGuard.getInstance().suppress();
    }
}
