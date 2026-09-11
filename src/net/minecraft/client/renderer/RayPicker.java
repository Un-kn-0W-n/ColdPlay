package net.minecraft.client.renderer;

import com.google.common.base.Predicate;
import java.util.List;
import net.minecraft.entity.Entity;
import net.minecraft.util.EntitySelectors;
import net.minecraft.util.EntityRayIntercept;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraft.world.World;
import net.optifine.reflect.Reflector;

/**
 * Side-effect-free block/entity ray picker extracted from {@link EntityRenderer#getMouseOver(float)}.
 * Modules can supply their own viewer, look vector, reach and candidate predicate without copying
 * vanilla's collision-border, riding-entity and block-occlusion rules.
 */
public final class RayPicker
{
    private RayPicker()
    {
    }

    public static Result pick(World world, Entity viewer, Vec3 eyes, Vec3 look,
                              double blockReach, double entityReach, double queryExpansion,
                              Predicate<Entity> candidateFilter,
                              boolean stopOnLiquid,
                              boolean ignoreBlockWithoutBoundingBox,
                              boolean returnLastUncollidableBlock)
    {
        Vec3 blockEnd = scaledEnd(eyes, look, blockReach);
        MovingObjectPosition blockHit = world.rayTraceBlocks(
                eyes, blockEnd, stopOnLiquid,
                ignoreBlockWithoutBoundingBox, returnLastUncollidableBlock);

        Vec3 entityEnd = scaledEnd(eyes, look, entityReach);
        AxisAlignedBB searchBox = viewer.getEntityBoundingBox()
                .offset(eyes.xCoord - viewer.posX, eyes.yCoord - viewer.posY - viewer.getEyeHeight(),
                        eyes.zCoord - viewer.posZ)
                .addCoord(look.xCoord * entityReach, look.yCoord * entityReach, look.zCoord * entityReach)
                .expand(queryExpansion, queryExpansion, queryExpansion);
        List<Entity> candidates = world.getEntitiesWithinAABBExcludingEntity(viewer, searchBox);

        boolean obstructed = blockHit != null
                && blockHit.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK;
        double blockDistanceSq = obstructed ? eyes.squareDistanceTo(blockHit.hitVec) : entityReach * entityReach;
        // A real block wins a tie. Clear-air reach includes its endpoint, as the ray segment does.
        double entityLimitSq = obstructed && blockDistanceSq <= entityReach * entityReach
                ? blockDistanceSq : Math.nextUp(entityReach * entityReach);
        EntityRayIntercept.Result intercept = EntityRayIntercept.find(
                eyes, entityEnd, candidates, entityLimitSq,
                new EntityRayIntercept.CandidatePolicy()
                {
                    public boolean canHit(Entity candidate)
                    {
                        return EntitySelectors.NOT_SPECTATING.apply(candidate)
                                && candidate.canBeCollidedWith()
                                && (candidateFilter == null || candidateFilter.apply(candidate));
                    }

                    public double expansion(Entity candidate)
                    {
                        return (double)candidate.getCollisionBorderSize();
                    }

                    public boolean includeStartingInside()
                    {
                        return true;
                    }

                    public EntityRayIntercept.Selection select(Entity candidate,
                                                               double candidateDistanceSq,
                                                               double currentDistanceSq)
                    {
                        boolean canRiderInteract = Reflector.ForgeEntity_canRiderInteract.exists()
                                && Reflector.callBoolean(candidate, Reflector.ForgeEntity_canRiderInteract);
                        if (!canRiderInteract && candidate == viewer.ridingEntity)
                        {
                            return currentDistanceSq == 0.0D
                                    ? EntityRayIntercept.Selection.REPLACE_KEEP_DISTANCE
                                    : EntityRayIntercept.Selection.REJECT;
                        }
                        return EntityRayIntercept.Selection.REPLACE;
                    }
                });

        MovingObjectPosition entityHit = intercept == null
                ? null : intercept.asMovingObjectPosition();
        double nearestDistance = intercept == null
                ? Math.sqrt(blockDistanceSq) : Math.sqrt(intercept.getDistanceSq());
        boolean entityWins = entityHit != null
                && (!obstructed || intercept.getDistanceSq() < blockDistanceSq);
        MovingObjectPosition selected = entityWins ? entityHit : blockHit;
        return new Result(blockHit, entityHit, selected, nearestDistance);
    }

    private static Vec3 scaledEnd(Vec3 start, Vec3 direction, double reach)
    {
        return start.addVector(direction.xCoord * reach, direction.yCoord * reach, direction.zCoord * reach);
    }

    /** Shared comparison used by focused-target callers and low-level parity checks. */
    public static boolean isEntityBeforeBlock(Vec3 eyes, Vec3 entityHit, MovingObjectPosition blockHit)
    {
        return blockHit == null
                || blockHit.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK
                || eyes.squareDistanceTo(entityHit) < eyes.squareDistanceTo(blockHit.hitVec);
    }

    public static MovingObjectPosition miss(Vec3 hit)
    {
        return new MovingObjectPosition(
                MovingObjectPosition.MovingObjectType.MISS,
                hit,
                (EnumFacing)null,
                new BlockPos(hit));
    }

    public static final class Result
    {
        private final MovingObjectPosition blockHit;
        private final MovingObjectPosition entityHit;
        private final MovingObjectPosition selectedHit;
        private final double entityDistance;

        private Result(MovingObjectPosition blockHit, MovingObjectPosition entityHit,
                       MovingObjectPosition selectedHit, double entityDistance)
        {
            this.blockHit = blockHit;
            this.entityHit = entityHit;
            this.selectedHit = selectedHit;
            this.entityDistance = entityDistance;
        }

        public MovingObjectPosition getBlockHit()
        {
            return this.blockHit;
        }

        public MovingObjectPosition getEntityHit()
        {
            return this.entityHit;
        }

        public Entity getEntity()
        {
            return this.selectedHit == this.entityHit && this.entityHit != null
                    ? this.entityHit.entityHit
                    : null;
        }

        public Vec3 getEntityHitVec()
        {
            return this.selectedHit == this.entityHit && this.entityHit != null
                    ? this.entityHit.hitVec
                    : null;
        }

        public MovingObjectPosition getSelectedHit()
        {
            return this.selectedHit;
        }

        public double getEntityDistance()
        {
            return this.entityDistance;
        }
    }
}
