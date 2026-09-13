package coldplay.util;

import net.minecraft.block.Block;
import net.minecraft.block.BlockBed;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.init.Blocks;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class BedUtil {

    private BedUtil() {
    }

    public static final class Bed {
        public final BlockPos foot, head;

        public Bed(BlockPos foot, BlockPos head) {
            this.foot = foot;
            this.head = head;
        }
    }

    private static BlockPos footOf(BlockPos pos, IBlockState state) {
        if (state.getValue(BlockBed.PART) == BlockBed.EnumPartType.HEAD) {
            return pos.offset(((EnumFacing) state.getValue(BlockBed.FACING)).getOpposite());
        }
        return pos;
    }

    public static Bed findNearestBed(int radius, BlockPos excludeFoot) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null || mc.theWorld == null) {
            return null;
        }
        Vec3 eyes = mc.thePlayer.getPositionEyes(1.0F);
        BlockPos base = new BlockPos(mc.thePlayer);
        Set<BlockPos> seenFeet = new HashSet<>();
        Bed best = null;
        double bestDistSq = Double.MAX_VALUE;
        BlockPos from = base.add(-radius, -radius, -radius);
        BlockPos to = base.add(radius, radius, radius);
        for (BlockPos.MutableBlockPos mutablePos : BlockPos.getAllInBoxMutable(from, to)) {
            IBlockState state = mc.theWorld.getBlockState(mutablePos);
            if (state.getBlock() != Blocks.bed) {
                continue;
            }
            // The mutable iterator reuses one object; copy before storing.
            BlockPos foot = footOf(new BlockPos(mutablePos), state);
            if (!seenFeet.add(foot) || foot.equals(excludeFoot)) {
                continue;
            }
            BlockPos head = foot.offset((EnumFacing) mc.theWorld.getBlockState(foot).getValue(BlockBed.FACING));
            double cx = (foot.getX() + head.getX() + 1.0D) * 0.5D;
            double cy = foot.getY() + 0.28125D; // bed AABB y-midpoint
            double cz = (foot.getZ() + head.getZ() + 1.0D) * 0.5D;
            double distSq = eyes.squareDistanceTo(new Vec3(cx, cy, cz));
            if (distSq < bestDistSq) {
                bestDistSq = distSq;
                best = new Bed(foot, head);
            }
        }
        return best;
    }

    // This scan can cover 256 blocks, so walk loaded chunk storage directly.
    public static List<BlockPos> findBedHalvesInRange(net.minecraft.client.multiplayer.WorldClient world,
            net.minecraft.client.entity.EntityPlayerSP player, int rangeBlocks, double maxDistSq) {
        List<BlockPos> halves = new ArrayList<>();
        int chunkRadius = (rangeBlocks + 15) >> 4;
        int playerChunkX = (int) player.posX >> 4;
        int playerChunkZ = (int) player.posZ >> 4;
        int minSectionY = (int) player.posY - rangeBlocks;
        int maxSectionY = (int) player.posY + rangeBlocks;
        int bedId = Block.getIdFromBlock(Blocks.bed);

        for (int cx = playerChunkX - chunkRadius; cx <= playerChunkX + chunkRadius; cx++) {
            for (int cz = playerChunkZ - chunkRadius; cz <= playerChunkZ + chunkRadius; cz++) {
                net.minecraft.world.chunk.Chunk chunk = world.getChunkFromChunkCoords(cx, cz);
                if (!chunk.isLoaded()) {
                    continue;
                }
                for (net.minecraft.world.chunk.storage.ExtendedBlockStorage storage : chunk.getBlockStorageArray()) {
                    if (storage == null || storage.isEmpty()
                            || storage.getYLocation() + 15 < minSectionY || storage.getYLocation() > maxSectionY) {
                        continue;
                    }
                    // State id is blockId << 4 | meta; skips a registry lookup per cell.
                    char[] data = storage.getData();
                    for (int i = 0; i < data.length; i++) {
                        if ((data[i] >> 4) != bedId) {
                            continue;
                        }
                        BlockPos pos = new BlockPos((cx << 4) + (i & 15), storage.getYLocation() + (i >> 8),
                                (cz << 4) + ((i >> 4) & 15));
                        if (pos.distanceSq(player.posX, player.posY, player.posZ) <= maxDistSq) {
                            halves.add(pos);
                        }
                    }
                }
            }
        }
        return halves;
    }

    private static List<BlockPos> adjacentPositions(Bed bed) {
        Set<BlockPos> set = new HashSet<>();
        for (EnumFacing face : EnumFacing.values()) {
            set.add(bed.foot.offset(face));
            set.add(bed.head.offset(face));
        }
        set.remove(bed.foot);
        set.remove(bed.head);
        return new ArrayList<>(set);
    }

    // Hypixel lets us hit the bed once any neighbouring block is air.
    public static boolean isBedExposed(Bed bed) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld == null) {
            return false;
        }
        for (BlockPos pos : adjacentPositions(bed)) {
            if (mc.theWorld.isAirBlock(pos)) {
                return true;
            }
        }
        return false;
    }

    // Prefer close, visible cover. Skip the floor below the bed; some servers dislike that dig.
    public static BlockPos pickAdjacentToBreak(Bed bed, Vec3 eyes, double maxRange) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld == null) {
            return null;
        }
        List<CandidateProbe> probes = new ArrayList<>();
        for (BlockPos pos : adjacentPositions(bed)) {
            Vec3 center = blockCenter(pos);
            probes.add(new CandidateProbe(pos,
                    RayTraceUtil.trace(mc.theWorld, eyes, center, false, false, false)));
        }
        BreakCandidate candidate = selectBreakCandidate(bed, probes, eyes, maxRange, true, true);
        return candidate == null ? null : candidate.pos;
    }

    // Try both bed centres, then slightly higher angles for the next visible tunnel block.
    public static MovingObjectPosition firstBlockToward(Bed bed, Vec3 eyes, double maxRange) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld == null) {
            return null;
        }
        Vec3[] ends = {
                new Vec3(bed.foot.getX() + 0.5D, bed.foot.getY() + 0.28125D, bed.foot.getZ() + 0.5D),
                new Vec3(bed.head.getX() + 0.5D, bed.head.getY() + 0.28125D, bed.head.getZ() + 0.5D),
                blockCenter(bed.foot),
                blockCenter(bed.head),
        };
        List<CandidateProbe> probes = new ArrayList<>();
        for (Vec3 end : ends) {
            MovingObjectPosition hit = RayTraceUtil.trace(mc.theWorld, eyes, end, false, false, true);
            if (RayTraceUtil.isBlockHit(hit)) {
                probes.add(new CandidateProbe(hit.getBlockPos(), hit));
            }
        }
        BreakCandidate candidate = selectBreakCandidate(bed, probes, eyes, maxRange, false, false);
        if (candidate == null || candidate.hit == null) {
            return null;
        }
        candidate.hit.hitVec = clampIntoFace(candidate.hit.hitVec, candidate.pos, candidate.hit.sideHit);
        return candidate.hit;
    }

    /**
     * Shared validation and scoring. Returns the best candidate, or the first valid one when
     * {@code chooseBest} is false.
     */
    private static BreakCandidate selectBreakCandidate(Bed bed, Iterable<CandidateProbe> probes,
                                                        Vec3 eyes, double maxRange,
                                                        boolean skipFloor, boolean chooseBest) {
        Minecraft mc = Minecraft.getMinecraft();
        BreakCandidate best = null;
        for (CandidateProbe probe : probes) {
            BlockPos pos = probe.pos;
            if (skipFloor && (pos.equals(bed.foot.down()) || pos.equals(bed.head.down()))) {
                continue;
            }
            IBlockState state = mc.theWorld.getBlockState(pos);
            if (state.getBlock() == Blocks.air || state.getBlock() == Blocks.bedrock
                    || state.getBlock().getBlockHardness(mc.theWorld, pos) < 0.0F
                    || reachBlock(pos) > maxRange) {
                continue;
            }
            double score = eyes.squareDistanceTo(blockCenter(pos));
            boolean visible = probe.hit != null
                    && probe.hit.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK
                    && pos.equals(probe.hit.getBlockPos());
            if (visible) {
                score *= 0.3D;
            }
            if (pos.equals(bed.foot.up()) || pos.equals(bed.head.up())) {
                score *= 0.8D;
            }
            BreakCandidate candidate = new BreakCandidate(pos, visible ? probe.hit : null, score);
            if (!chooseBest) {
                return candidate;
            }
            if (best == null || candidate.score < best.score) {
                best = candidate;
            }
        }
        return best;
    }

    private static Vec3 blockCenter(BlockPos pos) {
        return Vec3.atBlockCenter(pos);
    }

    private static final class CandidateProbe {
        final BlockPos pos;
        final MovingObjectPosition hit;

        CandidateProbe(BlockPos pos, MovingObjectPosition hit) {
            this.pos = pos;
            this.hit = hit;
        }
    }

    private static final class BreakCandidate {
        final BlockPos pos;
        final MovingObjectPosition hit;
        final double score;

        BreakCandidate(BlockPos pos, MovingObjectPosition hit, double score) {
            this.pos = pos;
            this.hit = hit;
            this.score = score;
        }
    }

    // Keep the recheck ray off edges and just inside the hit face.
    private static Vec3 clampIntoFace(Vec3 hit, BlockPos pos, EnumFacing face) {
        double fx = clampAxis(hit.xCoord - pos.getX(), face.getFrontOffsetX());
        double fy = clampAxis(hit.yCoord - pos.getY(), face.getFrontOffsetY());
        double fz = clampAxis(hit.zCoord - pos.getZ(), face.getFrontOffsetZ());
        return new Vec3(pos.getX() + fx, pos.getY() + fy, pos.getZ() + fz);
    }

    private static double clampAxis(double frac, int faceOffset) {
        if (faceOffset != 0) {
            return frac - faceOffset * 0.001D;
        }
        return MathHelper.clamp_double(frac, 0.34D, 0.66D);
    }

    public static double reachBlock(BlockPos pos) {
        Minecraft mc = Minecraft.getMinecraft();
        if (pos == null || mc.thePlayer == null) {
            return Double.MAX_VALUE;
        }
        Vec3 eyes = mc.thePlayer.getPositionEyes(1.0F);
        double x = MathHelper.clamp_double(eyes.xCoord, pos.getX(), pos.getX() + 1.0D);
        double y = MathHelper.clamp_double(eyes.yCoord, pos.getY(), pos.getY() + 1.0D);
        double z = MathHelper.clamp_double(eyes.zCoord, pos.getZ(), pos.getZ() + 1.0D);
        return eyes.distanceTo(new Vec3(x, y, z));
    }
}
