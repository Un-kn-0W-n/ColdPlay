package coldplay.event;

import net.minecraft.util.MovingObjectPosition;

/** Posted from Minecraft.rightClickMouse before a right click; cancelling suppresses it. Target may be null. */
public class EventUse extends Event {
    private final MovingObjectPosition target;

    public EventUse(MovingObjectPosition target) {
        this.target = target;
    }

    public MovingObjectPosition getTarget() {
        return target;
    }
}
