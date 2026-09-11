package coldplay.util;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.multiplayer.WorldClient;

public final class EdgeUtil {

    private static final double DIAGONAL = Math.sqrt(0.5D);
    private static final double[][] DIRECTIONS = {
            {1.0D, 0.0D}, {-1.0D, 0.0D}, {0.0D, 1.0D}, {0.0D, -1.0D},
            {DIAGONAL, DIAGONAL}, {-DIAGONAL, DIAGONAL},
            {DIAGONAL, -DIAGONAL}, {-DIAGONAL, -DIAGONAL}
    };

    /** Baseline lead for ordinary grounded travel; faster motion needs a longer probe. */
    public static final double MIN_PROBE = 0.3D;

    private EdgeUtil() {
    }

    /** True if there's a drop on any side of where we're stood; mirrors vanilla's sneak edge-stop probe. */
    public static boolean isAtAnyEdge(EntityPlayerSP player, WorldClient world, double lookahead) {
        lookahead = Math.max(lookahead, MIN_PROBE);
        for (double[] dir : DIRECTIONS) {
            if (!world.hasSupportAtOffset(player, dir[0] * lookahead, dir[1] * lookahead)) {
                return true;
            }
        }
        return false;
    }

    /** Grounded fall risk along current motion, or the intended key heading when starting from rest. */
    public static boolean isApproachingEdge(Minecraft mc, EntityPlayerSP player, double lookahead) {
        WorldClient world = mc.theWorld;
        if (player == null || world == null || !player.onGround) {
            return false;
        }
        double speed = Math.sqrt(player.motionX * player.motionX + player.motionZ * player.motionZ);
        double dirX = player.motionX;
        double dirZ = player.motionZ;
        if (speed < 1.0E-3D) {
            if (!PlayerUtil.anyMoveKeyDown(mc)) {
                return false;
            }
            double[] heading = PlayerUtil.headingVec(PlayerUtil.movementYaw(mc, player));
            dirX = heading[0];
            dirZ = heading[1];
        }
        lookahead = Math.max(Math.max(lookahead, MIN_PROBE), speed + 0.1D);
        // A long probe can land beyond a hole; also check near this tick's displacement.
        return isEdgeAlong(player, world, dirX, dirZ, lookahead)
                || isEdgeAlong(player, world, dirX, dirZ, Math.min(speed + 0.1D, MIN_PROBE));
    }

    /**
     * Probe the normalized travel direction to catch diagonal drops that cardinal probes straddle.
     * A near-zero direction returns false because there is no travel to predict.
     */
    public static boolean isEdgeAlong(EntityPlayerSP player, WorldClient world,
                                      double dirX, double dirZ, double lookahead) {
        double len = Math.sqrt(dirX * dirX + dirZ * dirZ);
        if (len < 1.0E-4D) {
            return false;
        }
        return !world.hasSupportAtOffset(player, dirX / len * lookahead, dirZ / len * lookahead);
    }
}
