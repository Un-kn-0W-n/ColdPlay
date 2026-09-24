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
    private static final int VETO_TICKS = 10; // one hurt window, only a damaging hit resets the server's sprint

    public final NumberSetting chance = add(new NumberSetting("Chance", 100.0, 0.0, 100.0, 1.0).describe("Percent of qualifying hits to restore sprint on."));

    private int lastVeto = -VETO_TICKS;

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
        if (player == null || !event.wasSprinting() || player.isSprinting() || SprintGuard.getInstance().isKept()) {
            return;
        }
        if (!(event.getTarget() instanceof EntityPlayer)) {
            return;
        }
        // a veto on every hit flips sprint off and on about ten times a second
        if (Math.abs(player.ticksExisted - lastVeto) < VETO_TICKS) {
            return;
        }
        if (Math.random() * 100.0 >= chance.get()) {
            return;
        }
        lastVeto = player.ticksExisted;
        SprintGuard.getInstance().suppress();
    }
}
