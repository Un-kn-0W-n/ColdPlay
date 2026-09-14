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

/** Entity classification shared by combat and visual modules. */
public final class EntityTargets {

    private EntityTargets() {
    }

    public enum Type {
        PLAYER,
        NPC,
        MOB,
        ANIMAL,
        OTHER
    }

    /** NPC wins over player because servers often represent NPCs as {@code EntityOtherPlayerMP}. */
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

    /** Does not test {@code IAnimals}, which {@code IMob} and {@code INpc} also extend. */
    public static boolean isAnimal(Entity entity) {
        return entity instanceof EntityAnimal
                || entity instanceof EntityWaterMob
                || entity instanceof EntityAmbientCreature;
    }

    public static boolean isMob(Entity entity) {
        return entity instanceof net.minecraft.entity.monster.IMob;
    }

    /** Heuristic; 1.8.9 has no NPC entity type. */
    public static boolean isNpc(Entity entity) {
        if (entity instanceof INpc) {
            return true;
        }
        if (entity.getEntityId() < 0) {
            return true; // servers commonly give fake/NPC entities negative ids
        }
        if (entity instanceof EntityOtherPlayerMP) {
            // Player entities missing from the tab list are NPCs.
            NetHandlerPlayClient net = Minecraft.getMinecraft().getNetHandler();
            return net != null && net.getPlayerInfo(entity.getUniqueID()) == null;
        }
        return false;
    }
}
