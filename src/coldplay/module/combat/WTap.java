package coldplay.module.combat;

import coldplay.broker.SprintGuard;
import coldplay.event.EventAttackPerformed;
import coldplay.event.EventStrafe;
import coldplay.event.EventTarget;
import coldplay.module.Category;
import coldplay.module.Module;
import coldplay.setting.BooleanSetting;
import coldplay.setting.NumberSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;

public class WTap extends Module {
    public final NumberSetting chance = add(new NumberSetting("Chance", 100.0, 0.0, 100.0, 1.0).describe("Percent of qualifying hits to reset sprint on."));
    public final BooleanSetting stap = add(new BooleanSetting("STap", false).describe("At close range, tap backwards (S-tap) for a harder reset."));
    public final NumberSetting distance = add(new NumberSetting("Distance", 3.0, 0.0, 6.0, 0.1).describe("Max distance (blocks) to S-tap instead of W-tap."));

    // 0 = idle, 1 = drop sprint, 2 = restart it.
    private int phase;
    private boolean backTap;

    public WTap() {
        super("WTap", Category.COMBAT, "Resets sprint on every hit so each lands with full sprint knockback.");
        distance.visibleWhen(stap::get).indent(1);
    }

    @Override
    public String getSuffix() {
        return Math.round(chance.get()) + "%";
    }

    @Override
    protected void onDisable() {
        phase = 0;
        backTap = false;
    }

    @EventTarget
    public void onAttack(EventAttackPerformed event) {
        if (phase != 0) {
            return;
        }
        EntityPlayerSP player = Minecraft.getMinecraft().thePlayer;
        if (player == null || !event.wasSprinting() || SprintGuard.getInstance().isKept()) {
            return;
        }
        if (Math.random() * 100.0 >= chance.get()) {
            return;
        }

        backTap = stap.get() && player.getDistanceToEntity(event.getTarget()) <= distance.get();
        phase = 1;
    }

    @EventTarget
    public void onStrafe(EventStrafe event) {
        EntityPlayerSP player = Minecraft.getMinecraft().thePlayer;
        if (player == null) {
            phase = 0;
            return;
        }

        if (phase == 1) {
            // Below 0.8 makes vanilla send STOP_SPRINTING.
            event.setForward(backTap ? (event.isSneak() ? -0.3F : -1.0F) : 0.0F);
            phase = 2;
        } else if (phase == 2) {
            if (coldplay.util.PlayerUtil.canVanillaSprint(player, event.getForward())) {
                player.setSprinting(true);
            }
            phase = 0;
            backTap = false;
        }
    }
}
