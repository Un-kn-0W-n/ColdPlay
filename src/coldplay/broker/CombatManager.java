package coldplay.broker;

import coldplay.friend.FriendManager;
import coldplay.util.EntityTargets;
import coldplay.util.HealthResolver;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.RotationMath;
import net.minecraft.util.Vec3;
import net.minecraft.world.World;

public final class CombatManager {

    public static final String PRIORITY_HEALTH = "Health";
    public static final String PRIORITY_DISTANCE = "Distance";

    private static final CombatManager INSTANCE = new CombatManager();

    public static CombatManager getInstance() {
        return INSTANCE;
    }

    private CombatManager() {
    }

    private double reachBonus;

    public double getReachBonus() {
        return reachBonus;
    }

    public void setReachBonus(double reachBonus) {
        this.reachBonus = reachBonus;
    }

    public static final class Filters {
        public final boolean players, mobs, animals, invisible, npcs, excludeFriends;
        public final double range, fov;
        public final boolean wallCheck;

        public Filters(boolean players, boolean mobs, boolean animals, boolean invisible,
                       boolean npcs, double range, double fov, boolean excludeFriends,
                       boolean wallCheck) {
            this.players = players;
            this.mobs = mobs;
            this.animals = animals;
            this.invisible = invisible;
            this.npcs = npcs;
            this.range = range;
            this.fov = fov;
            this.excludeFriends = excludeFriends;
            this.wallCheck = wallCheck;
        }
    }

    public Entity acquire(EntityPlayerSP player, Filters f) {
        return acquire(player, f, null);
    }

    public Entity acquire(EntityPlayerSP player, Filters f, String priority) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld == null) {
            return null;
        }
        boolean byHealth = PRIORITY_HEALTH.equals(priority);
        boolean byDistance = PRIORITY_DISTANCE.equals(priority);
        Entity best = null;
        float bestScore = Float.MAX_VALUE;
        float bestTie = Float.MAX_VALUE;
        for (Entity entity : mc.theWorld.loadedEntityList) {
            if (!isValid(player, entity, f)) {
                continue;
            }
            float offset = angularOffset(player, entity);
            if (offset > f.fov) {
                continue;
            }
            if (f.wallCheck && !canSee(player, entity)) {
                continue;
            }
            float score;
            float tie = 0.0F;
            if (byHealth) {
                score = HealthResolver.resolve((EntityLivingBase) entity);
                tie = player.getDistanceToEntity(entity);
            } else if (byDistance) {
                score = player.getDistanceToEntity(entity);
            } else {
                score = offset;
            }
            if (score < bestScore || (score == bestScore && tie < bestTie)) {
                bestScore = score;
                bestTie = tie;
                best = entity;
            }
        }
        return best;
    }

    public boolean canSee(EntityPlayerSP player, Entity entity) {
        World world = Minecraft.getMinecraft().theWorld;
        if (world == null) {
            return false;
        }
        if (player.canEntityBeSeen(entity)) {
            return true;
        }
        Vec3 eyes = player.getPositionEyes(1.0F);
        Vec3 centre = new Vec3(entity.posX, entity.posY + entity.height / 2.0, entity.posZ);
        return world.rayTraceBlocks(eyes, centre) == null;
    }

    public float angularOffset(EntityPlayerSP player, Entity entity) {
        return angularOffset(player, entity, player.rotationYaw, player.rotationPitch);
    }

    /** Offset from an arbitrary look, e.g. the spoofed server one. */
    public float angularOffset(EntityPlayerSP player, Entity entity, float yaw, float pitch) {
        return RotationMath.angularError(
                player.posX, player.posY + player.getEyeHeight(), player.posZ,
                entity.posX, entity.posY + entity.height / 2.0D, entity.posZ,
                yaw, pitch);
    }

    public boolean isValid(EntityPlayerSP player, Entity entity, Filters f) {
        if (!EntityTargets.isLivingTarget(player, entity, f.invisible)) {
            return false;
        }
        if (player.getDistanceToEntity(entity) > f.range + getReachBonus()) {
            return false;
        }
        if (f.excludeFriends && FriendManager.getInstance().isFriend(entity.getName())) {
            return false;
        }

        switch (EntityTargets.classify(entity)) {
            case PLAYER:
                return f.players;
            case NPC:
                return f.npcs;
            case MOB:
                return f.mobs;
            case ANIMAL:
                return f.animals;
            default:
                return false;
        }
    }
}
