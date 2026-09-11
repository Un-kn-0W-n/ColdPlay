package coldplay.event;

import net.minecraft.util.MovingObjectPosition;

/**
 * Fired from {@code Minecraft.clickMouse()} when the attack key triggers a left click, before the
 * swing/attack happens. Cancelling it suppresses the click. {@link #getTarget()} is the current
 * mouse-over (may be {@code null}); for an entity attack {@code typeOfHit == ENTITY} and
 * {@code entityHit} is the victim.
 */
public class EventAttack extends Event {
    private final MovingObjectPosition target;

    public EventAttack(MovingObjectPosition target) {
        this.target = target;
    }

    public MovingObjectPosition getTarget() {
        return target;
    }
}
