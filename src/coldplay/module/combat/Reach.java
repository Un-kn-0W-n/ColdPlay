package coldplay.module.combat;

import coldplay.event.EventTarget;
import coldplay.event.EventUpdate;
import coldplay.module.Category;
import coldplay.module.Module;
import coldplay.setting.NumberSetting;
import coldplay.broker.CombatManager;

public class Reach extends Module {
    public final NumberSetting amount = add(new NumberSetting("Amount", 0.4, 0.1, 0.4, 0.05)
            .describe("Extra attack reach in blocks, added to every combat module."));

    public Reach() {
        super("Reach", Category.COMBAT, "Extends attack reach for all combat modules.");
    }

    @Override
    protected void onEnable() {
        CombatManager.getInstance().setReachBonus(amount.get());
    }

    @Override
    protected void onDisable() {
        CombatManager.getInstance().setReachBonus(0.0);
    }

    /** Re-push every tick so a live slider drag reaches consumers without them ever polling us. */
    @EventTarget
    public void onUpdate(EventUpdate event) {
        if (event.isPre()) {
            CombatManager.getInstance().setReachBonus(amount.get());
        }
    }
}
