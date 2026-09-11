package coldplay.util;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityOtherPlayerMP;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.INpc;
import net.minecraft.entity.passive.EntityAmbientCreature;
import net.minecraft.entity.passive.EntityAnimal;
import net.minecraft.entity.passive.EntityWaterMob;
import net.minecraft.entity.player.EntityPlayer;

/**
 * Shared classification keeps combat targeting and visual selection consistent. Callers apply
 * their own category switches and range limits.
 */
public final class EntityTargets {

    private EntityTargets() {
    }

    /** Stable, mutually-exclusive entity buckets shared by combat and visual modules. */
    public enum Type {
        PLAYER,
        NPC,
        MOB,
        ANIMAL,
        OTHER
    }

    /**
     * Classifies an entity once, with NPC taking precedence over player because servers commonly
     * represent non-player characters as {@code EntityOtherPlayerMP}. Callers decide which buckets
     * are enabled and how each bucket should be displayed.
     */
    public static Type classify(Entity entity) {
        if (entity == null) {
            return Type.OTHER;
        }
        if (isNpc(entity)) {
            return Type.NPC;
        }
        if (entity instanceof EntityPlayer) {
            return Type.PLAYER;
        }
        if (isMob(entity)) {
            return Type.MOB;
        }
        if (isAnimal(entity)) {
            return Type.ANIMAL;
        }
        return Type.OTHER;
    }

    public static boolean isLivingTarget(Entity self, Entity entity, boolean includeInvisible) {
        if (entity == self) {
            return false;
        }
        if (!(entity instanceof EntityLivingBase) || !entity.isEntityAlive()) {
            return false;
        }
        return includeInvisible || !entity.isInvisible();
    }

    /**
     * Passive animals. Never tests {@code IAnimals} — both {@code IMob} and {@code INpc} extend it, so it
     * would also match hostiles and villagers. Lists the concrete passive bases (covers squid and bats too).
     */
    public static boolean isAnimal(Entity entity) {
        return entity instanceof EntityAnimal
                || entity instanceof EntityWaterMob
                || entity instanceof EntityAmbientCreature;
    }

    public static boolean isMob(Entity entity) {
        return entity instanceof net.minecraft.entity.monster.IMob;
    }

    /** Heuristic NPC detection for 1.8.9 (no vanilla NPC type): villagers, negative ids, off-tab-list players. */
    public static boolean isNpc(Entity entity) {
        if (entity instanceof INpc) {
            return true;
        }
        if (entity.getEntityId() < 0) {
            return true; // servers commonly give fake/NPC entities negative ids
        }
        if (entity instanceof EntityOtherPlayerMP) {
            // A player entity absent from the tab list is a player-shaped NPC, not a real client.
            NetHandlerPlayClient net = Minecraft.getMinecraft().getNetHandler();
            return net != null && net.getPlayerInfo(entity.getUniqueID()) == null;
        }
        return false;
    }
}
