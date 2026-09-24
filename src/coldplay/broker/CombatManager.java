package coldplay.broker;

import coldplay.friend.FriendManager;
import coldplay.util.EntityTargets;
import coldplay.util.HealthResolver;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.RotationMath;
import net.minecraft.util.Vec3;
import net.minecraft.world.World;

public final class CombatManager {

    public static final String PRIORITY_HEALTH = "Health";
    public static final String PRIORITY_DISTANCE = "Distance";

    /** How far off the hitbox faces the visibility samples sit, in blocks. */
    private static final double VISIBILITY_INSET = 0.05;

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

    private long nextAttackAt; // ms, 0 when KillAura has no target

    public long getNextAttackAt() {
        return nextAttackAt;
    }

    public void setNextAttackAt(long nextAttackAt) {
        this.nextAttackAt = nextAttackAt;
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

    /**
     * True while any part of the entity is visible from our eyes, not just its middle. The cheap eye-to-eye
     * and eye-to-centre rays answer the common cases first; only when both are blocked do we sample the
     * hitbox itself, so a target that is merely half covered - a head over a wall, legs under an overhang,
     * a shoulder past a corner - still counts as seen instead of being dropped like a fully hidden one.
     */
    public boolean canSee(EntityPlayerSP player, Entity entity) {
        World world = Minecraft.getMinecraft().theWorld;
        if (world == null) {
            return false;
        }
        if (player.canEntityBeSeen(entity)) {
            return true;
        }
        Vec3 eyes = player.getPositionEyes(1.0F);
        if (reaches(world, eyes, entity.posX, entity.posY + entity.height / 2.0, entity.posZ)) {
            return true;
        }
        AxisAlignedBB box = entity.getEntityBoundingBox();
        // Pull the samples off the faces, or a target flush against a wall would sample inside that wall.
        double insetX = Math.min(VISIBILITY_INSET, (box.maxX - box.minX) / 4.0);
        double insetY = Math.min(VISIBILITY_INSET, (box.maxY - box.minY) / 4.0);
        double insetZ = Math.min(VISIBILITY_INSET, (box.maxZ - box.minZ) / 4.0);
        double[] xs = {box.minX + insetX, (box.minX + box.maxX) / 2.0, box.maxX - insetX};
        double[] zs = {box.minZ + insetZ, (box.minZ + box.maxZ) / 2.0, box.maxZ - insetZ};
        double[] ys = {box.maxY - insetY, (box.minY + box.maxY) / 2.0, box.minY + insetY};
        for (int k = 0; k < ys.length; k++) {
            for (int i = 0; i < xs.length; i++) {
                for (int j = 0; j < zs.length; j++) {
                    // Dead centre of the box is the ray we already cast above.
                    if (i == 1 && j == 1 && k == 1) {
                        continue;
                    }
                    if (reaches(world, eyes, xs[i], ys[k], zs[j])) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean reaches(World world, Vec3 eyes, double x, double y, double z) {
        return world.rayTraceBlocks(eyes, new Vec3(x, y, z)) == null;
    }

    public float angularOffset(EntityPlayerSP player, Entity entity) {
        return angularOffset(player, entity, player.rotationYaw, player.rotationPitch);
    }

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
        if (BotTracker.getInstance().isBot(entity)) {
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
