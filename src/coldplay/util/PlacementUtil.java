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

    /** Support faces to click, in order. Never UP: a bottom-face click is its own fingerprint. */
    public static final EnumFacing[] SUPPORT_ORDER = {
            EnumFacing.DOWN, EnumFacing.NORTH, EnumFacing.SOUTH, EnumFacing.WEST, EnumFacing.EAST
    };

    /** Central legal band of a face for hit points: [0.34, 0.66] from the support corner, never dead-centre. */
    public static final double HIT_BAND_MIN = 0.34D;
    public static final double HIT_BAND_MAX = 0.66D;
    public static final double HIT_BAND_SPAN = HIT_BAND_MAX - HIT_BAND_MIN;
    /** Face-axis offset from the support centre: on the face plane, just inside the block. */
    public static final double FACE_PIN = 0.499D;

    /** Block-interaction reach a server checks against (the attribute default), used even where the client reports creative 5.0. */
    public static final double SERVER_REACH = 4.5D;

    /**
     * Pitch that puts a look along {@code yaw} from {@code eyes} onto the queued face at the hit
     * point's height, or NaN when that heading cannot reach the face: eyes not yet past its plane
     * (the horizontal ray then leaves the support through the far face) or beside it.
     */
    public static float facePitch(Vec3 eyes, Placement p, float yaw) {
        double[] h = PlayerUtil.headingVec(yaw);
        Vec3 from = new Vec3(eyes.xCoord, p.hitVec.yCoord, eyes.zCoord);
        MovingObjectPosition hit = new AxisAlignedBB(p.support, p.support.add(1, 1, 1)).calculateIntercept(
                from, from.addVector(h[0] * SERVER_REACH, 0.0D, h[1] * SERVER_REACH));
        if (hit == null || hit.sideHit != p.face) {
            return Float.NaN;
        }
        return RotationMath.pitchTo(eyes.xCoord, eyes.yCoord, eyes.zCoord,
                hit.hitVec.xCoord, hit.hitVec.yCoord, hit.hitVec.zCoord);
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
     * First block a ray from the eyes along {@code look} hits within reach, iff it is the pending
     * support on the pending face; {@code null} = hold fire this tick. Same raytrace flags as
     * vanilla getMouseOver, so a pass means a legit client clicking this face could have produced
     * the identical packet.
     */
    public static MovingObjectPosition rayTraceGate(Placement p, EntityPlayerSP player, WorldClient world, Minecraft mc, Vec3 look) {
        Vec3 eyes = player.getPositionEyes(1.0F);
        double reach = mc.playerController.getBlockReachDistance();
        MovingObjectPosition mop = RayTraceUtil.traceToLook(
                world, eyes, look, reach, false, false, true);
        return RayTraceUtil.matchesBlock(mop, p.support, p.face) ? mop : null;
    }

    /**
     * Whether the eyes are on the legal side to click this face of the support: a real player behind
     * and above the bridge line can only ever see/click faces angled back toward them. Top faces are
     * always legal; bottom faces never reach here (not in {@link #SUPPORT_ORDER}).
     */
    public static boolean sideClickLegal(EnumFacing face, BlockPos support, Vec3 eyes) {
        switch (face) {
            case NORTH: return eyes.zCoord - support.getZ() < 0.6D;
            case SOUTH: return eyes.zCoord - support.getZ() > 0.4D;
            case WEST:  return eyes.xCoord - support.getX() < 0.6D;
            case EAST:  return eyes.xCoord - support.getX() > 0.4D;
            default:    return true;
        }
    }

    /**
     * First support face around {@code target} that passes the solid, legal-side, reach and
     * line-of-sight gates, in {@link #SUPPORT_ORDER}. {@code hitPoints} produces the click point for a
     * candidate face (may return {@code null} to veto it); supports matching {@code fallbackOnly} are
     * only returned when no other face qualifies. Shared by real placement and render-time previews so
     * the two cannot drift.
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

    /**
     * Keep the face axis just inside the block and sample the other axes within the central band.
     * Use the caller's RNG so modules retain independent random streams.
     */
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
