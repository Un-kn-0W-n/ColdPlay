package coldplay.module.combat;

import coldplay.event.EventAttack;
import coldplay.event.EventPriority;
import coldplay.event.EventTarget;
import coldplay.module.Category;
import coldplay.module.Module;
import net.minecraft.util.MovingObjectPosition;

public class AntiMiss extends Module {
    public AntiMiss() {
        super("AntiMiss", Category.COMBAT, "Prevents left-click swings when your crosshair hits nothing.");
    }

    @EventTarget(priority = EventPriority.FALL_SAFETY)
    public void onAttack(EventAttack event) {
        MovingObjectPosition target = event.getTarget();
        if (target == null || target.typeOfHit == MovingObjectPosition.MovingObjectType.MISS) {
            event.setCancelled(true);
        }
    }
}
