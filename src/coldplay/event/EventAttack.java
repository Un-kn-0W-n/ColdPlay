package coldplay.event;

import net.minecraft.util.MovingObjectPosition;

/** Posted from Minecraft.clickMouse before a left click; cancelling suppresses it. Target may be null. */
public class EventAttack extends Event {
    private final MovingObjectPosition target;

    public EventAttack(MovingObjectPosition target) {
        this.target = target;
    }

    public MovingObjectPosition getTarget() {
        return target;
    }
}
