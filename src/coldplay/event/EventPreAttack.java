package coldplay.event;

import net.minecraft.entity.Entity;

/**
 * Posted from PlayerControllerMP.attackEntity before the held-item sync, so a listener that changes
 * the slot still has its C09 queued ahead of this attack's C02. Unlike {@link EventAttack}, which
 * only covers a manual left click, this fires for every attack the client makes, including the ones
 * modules dispatch themselves.
 */
public final class EventPreAttack extends Event {
    private final Entity target;

    public EventPreAttack(Entity target) {
        this.target = target;
    }

    public Entity getTarget() {
        return target;
    }
}
