package coldplay.broker;

import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.entity.Entity;

/** When our last fresh hurt status arrived. The server sends status 2 only for hits that start hurt immunity. */
public final class HurtClock {
    private static final HurtClock INSTANCE = new HurtClock();

    private EntityPlayerSP player;
    private int tick;
    private float health;

    private HurtClock() {
    }

    public static HurtClock getInstance() {
        return INSTANCE;
    }

    public void onStatus(Entity entity, byte opCode) {
        if (opCode == 2 && entity instanceof EntityPlayerSP) {
            player = (EntityPlayerSP) entity;
            tick = player.ticksExisted;
            health = player.getHealth();
        }
    }

    /** Ticks since the status arrived, or -1 when this player has none. */
    public int ticksSince(EntityPlayerSP p) {
        return p != null && p == player ? p.ticksExisted - tick : -1;
    }

    /** A rod or snowball knock starts immunity without damage, and that immunity shields nothing. */
    public boolean damaged(EntityPlayerSP p) {
        return p != null && p == player && p.getHealth() < health - 0.5F;
    }
}
