package coldplay.event;

import net.minecraft.util.MovingObjectPosition;

/**
 * Fired from {@code Minecraft.rightClickMouse()} when the use key triggers a right click, before the
 * use / block-placement / entity-interact packet is sent. Cancelling it suppresses the right click.
 * {@link #getTarget()} is the
 * current mouse-over (may be {@code null}); for an entity interact {@code typeOfHit == ENTITY}.
 *
 * <p>It can fire more than once per tick (vanilla drains the use key in a {@code while} loop), so
 * listeners must tolerate repeated use events within a tick.
 */
public class EventUse extends Event {
    private final MovingObjectPosition target;

    public EventUse(MovingObjectPosition target) {
        this.target = target;
    }

    public MovingObjectPosition getTarget() {
        return target;
    }
}
