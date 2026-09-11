package coldplay.event;

import net.minecraft.entity.Entity;

/** Fired after a non-spectator attack completes through PlayerControllerMP. */
public final class EventAttackPerformed extends Event {
    private final Entity target;
    private final boolean wasSprinting;

    public EventAttackPerformed(Entity target, boolean wasSprinting) {
        this.target = target;
        this.wasSprinting = wasSprinting;
    }

    public Entity getTarget() {
        return target;
    }

    public boolean wasSprinting() {
        return wasSprinting;
    }

    /** The attack already happened; this notification cannot cancel it or later observers. */
    @Override
    public void setCancelled(boolean cancelled) {
    }
}
