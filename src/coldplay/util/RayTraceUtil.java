package coldplay.util;

import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraft.world.World;

/** Shared block ray-trace construction and hit validation. */
public final class RayTraceUtil
{
    private RayTraceUtil()
    {
    }

    public static MovingObjectPosition trace(World world, Vec3 start, Vec3 end,
                                             boolean stopOnLiquid,
                                             boolean ignoreBlockWithoutBoundingBox,
                                             boolean returnLastUncollidableBlock)
    {
        return world.rayTraceBlocks(start, end, stopOnLiquid,
                ignoreBlockWithoutBoundingBox, returnLastUncollidableBlock);
    }

    public static MovingObjectPosition traceToLook(World world, Vec3 start, Vec3 look, double reach,
                                                    boolean stopOnLiquid,
                                                    boolean ignoreBlockWithoutBoundingBox,
                                                    boolean returnLastUncollidableBlock)
    {
        Vec3 end = start.addVector(look.xCoord * reach, look.yCoord * reach, look.zCoord * reach);
        return trace(world, start, end, stopOnLiquid,
                ignoreBlockWithoutBoundingBox, returnLastUncollidableBlock);
    }

    public static boolean isBlockHit(MovingObjectPosition hit)
    {
        return hit != null && hit.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK;
    }

    public static boolean matchesBlock(MovingObjectPosition hit, BlockPos block)
    {
        return isBlockHit(hit) && block.equals(hit.getBlockPos());
    }

    public static boolean matchesBlock(MovingObjectPosition hit, BlockPos block, EnumFacing face)
    {
        return matchesBlock(hit, block) && hit.sideHit == face;
    }
}
