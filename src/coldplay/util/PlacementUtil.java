package coldplay.util;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.RotationMath;
import net.minecraft.util.Vec3;

import java.util.Random;
import java.util.function.BiFunction;
import java.util.function.Predicate;

public final class PlacementUtil {

    private PlacementUtil() {
    }

    /** Never UP; a bottom-face click is its own fingerprint. */
    public static final EnumFacing[] SUPPORT_ORDER = {
            EnumFacing.DOWN, EnumFacing.NORTH, EnumFacing.SOUTH, EnumFacing.WEST, EnumFacing.EAST
    };

    public static final double HIT_BAND_MIN = 0.34D; // fraction across the face
    public static final double HIT_BAND_MAX = 0.66D;
    public static final double HIT_BAND_SPAN = HIT_BAND_MAX - HIT_BAND_MIN;
    public static final double FACE_PIN = 0.499D; // just inside the face plane

    public static final double SERVER_REACH = 4.5D; // blocks; the server-side default

    /**
     * Pitch that lands a look along {@code yaw} on the queued face, or NaN when that heading cannot
     * reach it.
     */
    public static float facePitch(Vec3 eyes, Placement p, float yaw) {
        double[] h = PlayerUtil.headingVec(yaw);
        Vec3 from = new Vec3(eyes.xCoord, p.hitVec.yCoord, eyes.zCoord);
        MovingObjectPosition hit = faceHit(from, new Vec3(h[0], 0.0D, h[1]), p);
        if (hit == null) {
            return Float.NaN;
        }
        return RotationMath.pitchTo(eyes.xCoord, eyes.yCoord, eyes.zCoord,
                hit.hitVec.xCoord, hit.hitVec.yCoord, hit.hitVec.zCoord);
    }

    /** Where a server-reach ray along {@code dir} enters the queued face, or null when it misses it. */
    public static MovingObjectPosition faceHit(Vec3 from, Vec3 dir, Placement p) {
        MovingObjectPosition hit = new AxisAlignedBB(p.support, p.support.add(1, 1, 1)).calculateIntercept(
                from, from.addVector(dir.xCoord * SERVER_REACH, dir.yCoord * SERVER_REACH, dir.zCoord * SERVER_REACH));
        return hit != null && hit.sideHit == p.face ? hit : null;
    }

    public static final class Placement {
        public final BlockPos target;
        public final BlockPos support;
        public final EnumFacing face;
        public final Vec3 hitVec;

        public Placement(BlockPos target, BlockPos support, EnumFacing face, Vec3 hitVec) {
            this.target = target;
            this.support = support;
            this.face = face;
            this.hitVec = hitVec;
        }
    }

    /**
     * The ray hit along {@code look} if it lands on the pending support face, else {@code null}.
     * Same raytrace flags as vanilla getMouseOver.
     */
    public static MovingObjectPosition rayTraceGate(Placement p, EntityPlayerSP player, WorldClient world, Minecraft mc, Vec3 look) {
        Vec3 eyes = player.getPositionEyes(1.0F);
        double reach = mc.playerController.getBlockReachDistance();
        MovingObjectPosition mop = RayTraceUtil.traceToLook(
                world, eyes, look, reach, false, false, true);
        return RayTraceUtil.matchesBlock(mop, p.support, p.face) ? mop : null;
    }

    /** Whether the eyes are on or outside the clicked face of a full-block support. */
    public static boolean sideClickLegal(EnumFacing face, BlockPos support, Vec3 eyes) {
        switch (face) {
            case NORTH: return eyes.zCoord <= support.getZ();
            case SOUTH: return eyes.zCoord >= support.getZ() + 1.0D;
            case WEST:  return eyes.xCoord <= support.getX();
            case EAST:  return eyes.xCoord >= support.getX() + 1.0D;
            case UP:    return eyes.yCoord >= support.getY() + 1.0D;
            case DOWN:  return eyes.yCoord <= support.getY();
            default:    return false;
        }
    }

    /**
     * First support face around {@code target} that passes the solid, side, reach and sight gates.
     * Supports matching {@code fallbackOnly} are returned only when nothing else qualifies.
     */
    public static Placement find(WorldClient world, BlockPos target, Vec3 eyes, double reach,
                                 BiFunction<BlockPos, EnumFacing, Vec3> hitPoints,
                                 Predicate<BlockPos> fallbackOnly) {
        Placement fallback = null;
        for (EnumFacing direction : SUPPORT_ORDER) {
            BlockPos support = target.offset(direction);
            if (!world.getBlockState(support).getBlock().getMaterial().isSolid()) {
                continue;
            }
            EnumFacing face = direction.getOpposite();
            if (!sideClickLegal(face, support, eyes)) {
                continue;
            }
            Vec3 hit = hitPoints.apply(support, face);
            if (hit == null || eyes.distanceTo(hit) > reach) {
                continue;
            }
            Placement placement = new Placement(target, support, face, hit);
            if (!sightClear(world, eyes, placement)) {
                continue;
            }
            if (!fallbackOnly.test(support)) {
                return placement;
            }
            if (fallback == null) {
                fallback = placement;
            }
        }
        return fallback;
    }

    private static boolean sightClear(WorldClient world, Vec3 eyes, Placement placement) {
        MovingObjectPosition hit = RayTraceUtil.trace(world, eyes, placement.hitVec, false, false, true);
        return RayTraceUtil.matchesBlock(hit, placement.support, placement.face);
    }

    /** Random hit point in the central band of {@code face}. */
    public static Vec3 randomHitVec(Random rand, BlockPos support, EnumFacing face) {
        double first = HIT_BAND_MIN + rand.nextDouble() * HIT_BAND_SPAN;
        double second = HIT_BAND_MIN + rand.nextDouble() * HIT_BAND_SPAN;
        Vec3 center = Vec3.atBlockCenter(support);
        double x = center.xCoord;
        double y = center.yCoord;
        double z = center.zCoord;
        double pin = face.getAxisDirection() == EnumFacing.AxisDirection.POSITIVE ? FACE_PIN : -FACE_PIN;
        switch (face.getAxis()) {
            case X: x += pin; y = support.getY() + first; z = support.getZ() + second; break;
            case Y: y += pin; x = support.getX() + first; z = support.getZ() + second; break;
            case Z: z += pin; x = support.getX() + first; y = support.getY() + second; break;
        }
        return new Vec3(x, y, z);
    }
}
