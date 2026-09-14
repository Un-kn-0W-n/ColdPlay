package coldplay.event;

import net.minecraft.entity.Entity;

/** Posted from PlayerControllerMP after a non-spectator attack completes. */
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

    /** No-op; the attack already happened. */
    @Override
    public void setCancelled(boolean cancelled) {
    }
}
