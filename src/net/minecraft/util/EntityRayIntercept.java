package net.minecraft.util;

import net.minecraft.entity.Entity;

/** Nearest entity hit along a ray segment. Mouse picking and projectile collision share it and differ only in the policy. */
public final class EntityRayIntercept
{
    private EntityRayIntercept()
    {
    }

    public static Result find(Vec3 from, Vec3 to, Iterable<? extends Entity> candidates,
                              double initialDistanceSq, CandidatePolicy policy)
    {
        Entity nearest = null;
        Vec3 nearestHit = null;
        double nearestDistanceSq = initialDistanceSq;

        for (Entity candidate : candidates)
        {
            if (candidate == null || !policy.canHit(candidate))
            {
                continue;
            }

            double expansion = policy.expansion(candidate);
            AxisAlignedBB box = candidate.getEntityBoundingBox().expand(expansion, expansion, expansion);
            MovingObjectPosition intercept = box.calculateIntercept(from, to);

            if (policy.includeStartingInside() && box.isVecInside(from))
            {
                if (nearestDistanceSq >= 0.0D)
                {
                    nearest = candidate;
                    nearestHit = intercept == null ? from : intercept.hitVec;
                    nearestDistanceSq = 0.0D;
                }
                continue;
            }

            if (intercept == null)
            {
                continue;
            }

            double candidateDistanceSq = from.squareDistanceTo(intercept.hitVec);
            if (candidateDistanceSq >= nearestDistanceSq && nearestDistanceSq != 0.0D)
            {
                continue;
            }

            Selection selection = policy.select(candidate, candidateDistanceSq, nearestDistanceSq);
            if (selection == Selection.REJECT)
            {
                continue;
            }

            nearest = candidate;
            nearestHit = intercept.hitVec;
            if (selection == Selection.REPLACE)
            {
                nearestDistanceSq = candidateDistanceSq;
            }
        }

        return nearest == null ? null : new Result(nearest, nearestHit, nearestDistanceSq);
    }

    public interface CandidatePolicy
    {
        boolean canHit(Entity candidate);

        double expansion(Entity candidate);

        boolean includeStartingInside();

        Selection select(Entity candidate, double candidateDistanceSq, double currentDistanceSq);
    }

    public enum Selection
    {
        REJECT,
        REPLACE,
        REPLACE_KEEP_DISTANCE
    }

    public static final class Result
    {
        private final Entity entity;
        private final Vec3 hitVec;
        private final double distanceSq;

        private Result(Entity entityIn, Vec3 hitVecIn, double distanceSqIn)
        {
            this.entity = entityIn;
            this.hitVec = hitVecIn;
            this.distanceSq = distanceSqIn;
        }

        public Entity getEntity()
        {
            return this.entity;
        }

        public Vec3 getHitVec()
        {
            return this.hitVec;
        }

        public double getDistanceSq()
        {
            return this.distanceSq;
        }

        public MovingObjectPosition asMovingObjectPosition()
        {
            return new MovingObjectPosition(this.entity, this.hitVec);
        }
    }
}
