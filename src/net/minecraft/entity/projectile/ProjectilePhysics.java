package net.minecraft.entity.projectile;

import com.google.common.base.Predicate;
import java.util.Random;
import net.minecraft.block.material.Material;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.EntityRayIntercept;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.RotationMath;
import net.minecraft.util.Vec3;
import net.minecraft.world.World;

/**
 * Side-effect-free projectile primitives shared by live vanilla projectiles and prediction code.
 *
 * <p>The entity classes still own world mutation, impact handling, particles and rotation smoothing.
 * This class owns the drift-prone numerical rules: bow charge, spawn offset, heading spread,
 * segment interception and per-tick integration.</p>
 */
public final class ProjectilePhysics
{
    public static final double SPAWN_SIDE_OFFSET = 0.16D;
    public static final double SPAWN_VERTICAL_OFFSET = 0.10000000149011612D;
    public static final double ENTITY_HITBOX_EXPANSION = 0.3D;
    public static final double AIR_DRAG = (double)0.99F;
    public static final double THROWABLE_SPEED = 1.5D;
    public static final double THROWABLE_GRAVITY = (double)0.03F;
    public static final double ARROW_GRAVITY = (double)0.05F;
    public static final double ARROW_CONSTRUCTOR_SPEED_SCALE = 1.5D;
    public static final Profile ARROW_PROFILE = new Profile(
            false, true, false, AIR_DRAG, (double)0.6F, ARROW_GRAVITY,
            0.5D, 0.5D, 1.0D, ENTITY_HITBOX_EXPANSION);
    public static final Profile THROWABLE_PROFILE = new Profile(
            false, false, false, AIR_DRAG, (double)0.8F, THROWABLE_GRAVITY,
            0.25D, 0.25D, 1.0D, ENTITY_HITBOX_EXPANSION);
    private static final double SPREAD_SCALE = 0.007499999832361937D;

    private ProjectilePhysics()
    {
    }

    /** Vanilla bow charge curve, clamped to the legal 0..1 interval. */
    public static float bowCharge(int useTicks)
    {
        float charge = (float)useTicks / 20.0F;
        charge = (charge * charge + charge * 2.0F) / 3.0F;
        return MathHelper.clamp_float(charge, 0.0F, 1.0F);
    }

    /** Velocity passed to EntityArrow's shooter constructor for a given bow charge. */
    public static float bowEntityVelocity(float charge)
    {
        return charge * 2.0F;
    }

    /** Actual world-space launch speed after EntityArrow's constructor scale. */
    public static double bowLaunchSpeed(float charge)
    {
        return (double)bowEntityVelocity(charge) * ARROW_CONSTRUCTOR_SPEED_SCALE;
    }

    /** The common projectile spawn point relative to an interpolated eye position. */
    public static Vec3 spawnPosition(Vec3 eyePosition, float yaw)
    {
        float yawRadians = yaw / 180.0F * (float)Math.PI;
        return eyePosition.addVector(
                -(double)(MathHelper.cos(yawRadians) * (float)SPAWN_SIDE_OFFSET),
                -SPAWN_VERTICAL_OFFSET,
                -(double)(MathHelper.sin(yawRadians) * (float)SPAWN_SIDE_OFFSET));
    }

    public static Vec3 spawnPosition(EntityLivingBase shooter, float partialTicks)
    {
        return spawnPosition(shooter.getPositionEyes(partialTicks), shooter.rotationYaw);
    }

    /** Unit direction for the given vanilla pitch/yaw pair. */
    public static Vec3 lookDirection(float yaw, float pitch)
    {
        return RotationMath.lookVector(pitch, yaw);
    }

    /**
     * Initial direction used by {@link EntityThrowable}; its optional pitch offset historically
     * affects only the vertical component, so it cannot be represented by a normal look vector.
     */
    public static Vec3 throwableDirection(float yaw, float pitch, float verticalPitchOffset, float initialScale)
    {
        float yawRadians = yaw / 180.0F * (float)Math.PI;
        float pitchRadians = pitch / 180.0F * (float)Math.PI;
        float verticalPitchRadians = (pitch + verticalPitchOffset) / 180.0F * (float)Math.PI;
        return new Vec3(
                (double)(-MathHelper.sin(yawRadians) * MathHelper.cos(pitchRadians) * initialScale),
                (double)(-MathHelper.sin(verticalPitchRadians) * initialScale),
                (double)(MathHelper.cos(yawRadians) * MathHelper.cos(pitchRadians) * initialScale));
    }

    public static Vec3 scale(Vec3 vector, double amount)
    {
        return new Vec3(vector.xCoord * amount, vector.yCoord * amount, vector.zCoord * amount);
    }

    /**
     * Normalizes a heading, applies vanilla Gaussian spread, and scales it to launch velocity.
     * Arrows historically consume an extra random sign per component; throwable entities do not.
     */
    public static Vec3 heading(Vec3 direction, float velocity, float inaccuracy, Random random, boolean signedGaussian)
    {
        double length = (double)MathHelper.sqrt_double(
                direction.xCoord * direction.xCoord
                        + direction.yCoord * direction.yCoord
                        + direction.zCoord * direction.zCoord);
        double x = direction.xCoord / length;
        double y = direction.yCoord / length;
        double z = direction.zCoord / length;
        double spread = SPREAD_SCALE * (double)inaccuracy;

        x += gaussian(random, signedGaussian) * spread;
        y += gaussian(random, signedGaussian) * spread;
        z += gaussian(random, signedGaussian) * spread;
        return new Vec3(x * (double)velocity, y * (double)velocity, z * (double)velocity);
    }

    private static double gaussian(Random random, boolean signedGaussian)
    {
        double value = random.nextGaussian();
        return signedGaussian ? value * (double)(random.nextBoolean() ? -1 : 1) : value;
    }

    public static Vec3 nextPosition(Vec3 position, Vec3 motion)
    {
        return position.add(motion);
    }

    /** Applies drag to all axes, then gravity to Y, exactly matching projectile update order. */
    public static Vec3 nextMotion(Vec3 motion, double drag, double gravity)
    {
        return new Vec3(
                motion.xCoord * drag,
                motion.yCoord * drag - gravity,
                motion.zCoord * drag);
    }

    public static MovingObjectPosition traceBlocks(World world, Vec3 from, Vec3 to,
                                                    boolean stopOnLiquid,
                                                    boolean ignoreBlockWithoutBoundingBox,
                                                    boolean returnLastUncollidableBlock)
    {
        return world.rayTraceBlocks(from, to, stopOnLiquid,
                ignoreBlockWithoutBoundingBox, returnLastUncollidableBlock);
    }

    /** Returns the nearest padded entity-box intercept along a segment, or {@code null}. */
    public static MovingObjectPosition nearestEntityIntercept(Vec3 from, Vec3 to,
                                                               Iterable<? extends Entity> candidates,
                                                               double expansion,
                                                               Predicate<Entity> filter)
    {
        EntityRayIntercept.Result result = EntityRayIntercept.find(
                from, to, candidates, Double.MAX_VALUE,
                new EntityRayIntercept.CandidatePolicy()
                {
                    public boolean canHit(Entity candidate)
                    {
                        return filter == null || filter.apply(candidate);
                    }

                    public double expansion(Entity candidate)
                    {
                        return expansion;
                    }

                    public boolean includeStartingInside()
                    {
                        return false;
                    }

                    public EntityRayIntercept.Selection select(Entity candidate,
                                                               double candidateDistanceSq,
                                                               double currentDistanceSq)
                    {
                        return EntityRayIntercept.Selection.REPLACE;
                    }
                });
        return result == null ? null : result.asMovingObjectPosition();
    }

    /**
     * Performs one complete side-effect-free projectile tick: segment construction, block clipping,
     * entity arbitration, water-aware drag and gravity integration.
     */
    public static StepResult step(World world, StepState state, Profile profile,
                                  Entity excludedEntity, Predicate<Entity> entityFilter,
                                  boolean traceEntities, boolean inWater)
    {
        Vec3 segmentEnd = nextPosition(state.position, state.motion);
        MovingObjectPosition blockHit = traceBlocks(world, state.position, segmentEnd,
                profile.stopOnLiquid, profile.ignoreBlockWithoutBoundingBox,
                profile.returnLastUncollidableBlock);
        Vec3 entityTraceEnd = blockHit == null ? segmentEnd : blockHit.hitVec;
        MovingObjectPosition entityHit = null;

        if (traceEntities)
        {
            AxisAlignedBB query = state.bounds.addCoord(
                    state.motion.xCoord, state.motion.yCoord, state.motion.zCoord)
                    .expand(profile.queryExpansion, profile.queryExpansion, profile.queryExpansion);
            entityHit = nearestEntityIntercept(state.position, entityTraceEnd,
                    world.getEntitiesWithinAABBExcludingEntity(excludedEntity, query),
                    profile.entityExpansion, entityFilter);
        }

        MovingObjectPosition collision = entityHit == null ? blockHit : entityHit;
        return new StepResult(blockHit, entityHit, collision, segmentEnd,
                integrate(state, profile, inWater), inWater);
    }

    /** Applies the profile's position, drag and gravity rules without querying the world. */
    public static StepState integrate(StepState state, Profile profile, boolean inWater)
    {
        Vec3 position = nextPosition(state.position, state.motion);
        Vec3 motion = nextMotion(state.motion,
                inWater ? profile.waterDrag : profile.airDrag, profile.gravity);
        return new StepState(position, motion,
                state.bounds.offset(state.motion.xCoord, state.motion.yCoord, state.motion.zCoord));
    }

    /** Material-only equivalent of the inherited projectile water test, without mutating flow motion. */
    public static boolean isInWater(World world, AxisAlignedBB bounds)
    {
        AxisAlignedBB sample = bounds.expand(0.0D, -0.4000000059604645D, 0.0D)
                .contract(0.001D, 0.001D, 0.001D);
        return world.isAABBInMaterial(sample, Material.water);
    }

    public static final class Profile
    {
        private final boolean stopOnLiquid;
        private final boolean ignoreBlockWithoutBoundingBox;
        private final boolean returnLastUncollidableBlock;
        private final double airDrag;
        private final double waterDrag;
        private final double gravity;
        private final double width;
        private final double height;
        private final double queryExpansion;
        private final double entityExpansion;

        private Profile(boolean stopOnLiquidIn, boolean ignoreBlockWithoutBoundingBoxIn,
                        boolean returnLastUncollidableBlockIn, double airDragIn,
                        double waterDragIn, double gravityIn, double widthIn, double heightIn,
                        double queryExpansionIn, double entityExpansionIn)
        {
            this.stopOnLiquid = stopOnLiquidIn;
            this.ignoreBlockWithoutBoundingBox = ignoreBlockWithoutBoundingBoxIn;
            this.returnLastUncollidableBlock = returnLastUncollidableBlockIn;
            this.airDrag = airDragIn;
            this.waterDrag = waterDragIn;
            this.gravity = gravityIn;
            this.width = widthIn;
            this.height = heightIn;
            this.queryExpansion = queryExpansionIn;
            this.entityExpansion = entityExpansionIn;
        }

        public Profile withGravity(double gravityIn)
        {
            return new Profile(this.stopOnLiquid, this.ignoreBlockWithoutBoundingBox,
                    this.returnLastUncollidableBlock, this.airDrag, this.waterDrag, gravityIn,
                    this.width, this.height, this.queryExpansion, this.entityExpansion);
        }

        public AxisAlignedBB boundsAt(Vec3 position)
        {
            double halfWidth = this.width * 0.5D;
            return new AxisAlignedBB(position.xCoord - halfWidth, position.yCoord,
                    position.zCoord - halfWidth, position.xCoord + halfWidth,
                    position.yCoord + this.height, position.zCoord + halfWidth);
        }
    }

    public static final class StepState
    {
        private final Vec3 position;
        private final Vec3 motion;
        private final AxisAlignedBB bounds;

        public StepState(Vec3 positionIn, Vec3 motionIn, AxisAlignedBB boundsIn)
        {
            this.position = positionIn;
            this.motion = motionIn;
            this.bounds = boundsIn;
        }

        public static StepState from(Entity entity)
        {
            return new StepState(new Vec3(entity.posX, entity.posY, entity.posZ),
                    new Vec3(entity.motionX, entity.motionY, entity.motionZ),
                    entity.getEntityBoundingBox());
        }

        public Vec3 getPosition()
        {
            return this.position;
        }

        public Vec3 getMotion()
        {
            return this.motion;
        }

        public AxisAlignedBB getBounds()
        {
            return this.bounds;
        }
    }

    public static final class StepResult
    {
        private final MovingObjectPosition blockHit;
        private final MovingObjectPosition entityHit;
        private final MovingObjectPosition collision;
        private final Vec3 segmentEnd;
        private final StepState nextState;
        private final boolean inWater;

        private StepResult(MovingObjectPosition blockHitIn, MovingObjectPosition entityHitIn,
                           MovingObjectPosition collisionIn, Vec3 segmentEndIn,
                           StepState nextStateIn, boolean inWaterIn)
        {
            this.blockHit = blockHitIn;
            this.entityHit = entityHitIn;
            this.collision = collisionIn;
            this.segmentEnd = segmentEndIn;
            this.nextState = nextStateIn;
            this.inWater = inWaterIn;
        }

        public MovingObjectPosition getBlockHit()
        {
            return this.blockHit;
        }

        public MovingObjectPosition getEntityHit()
        {
            return this.entityHit;
        }

        public MovingObjectPosition getCollision()
        {
            return this.collision;
        }

        public Vec3 getSegmentEnd()
        {
            return this.segmentEnd;
        }

        public StepState getNextState()
        {
            return this.nextState;
        }

        public boolean isInWater()
        {
            return this.inWater;
        }
    }
}
