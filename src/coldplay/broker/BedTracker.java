package coldplay.broker;

import coldplay.event.EventPriority;
import coldplay.event.EventTarget;
import coldplay.event.EventUpdate;
import coldplay.util.BedUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.init.Blocks;
import net.minecraft.util.BlockPos;

/**
 * Captures the nearest bed at game start, allowing late chunks to settle before locking it for the
 * world. Runs independently of Breaker so the player's bed is known when Breaker is enabled.
 */
public final class BedTracker {

    private static final BedTracker INSTANCE = new BedTracker();

    public static BedTracker getInstance() {
        return INSTANCE;
    }

    /** You spawn a few blocks from your bed. */
    private static final int OWN_BED_SCAN_RANGE = 12;
    /** After terrain loads, keep re-taking the nearest for a few more ticks before locking. */
    private static final int SETTLE_TICKS = 5;
    /** Throttle the 25^3 cube scan. */
    private static final int SCAN_EVERY = 4;

    private BlockPos ownBedFoot;
    private boolean capturing;
    private int settleTicks;
    private int captureAge;
    /** Locked our real bed this world -> a later respawn must not re-lock an enemy bed. */
    private boolean bedLockedThisGame;
    /** New WorldClient == new game: the one place a locked bed is forgotten. */
    private WorldClient lastWorld;

    private BedTracker() {
    }

    /** Foot of the bed we spawned next to, or null before the first capture / after it's broken. */
    public BlockPos ownBedFoot() {
        return ownBedFoot;
    }

    /** Before every module (high priority), so consumers read a fresh bed each tick. */
    @EventTarget(priority = EventPriority.STATE_TRACKING)
    public void onUpdate(EventUpdate event) {
        if (!event.isPre()) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null || mc.theWorld == null) {
            ownBedFoot = null;
            capturing = false;
            bedLockedThisGame = false;
            lastWorld = null;
            return;
        }

        if (mc.theWorld != lastWorld) {
            lastWorld = mc.theWorld;
            openCaptureWindow();
        }

        // After locking, respawns must not replace our broken bed with an enemy bed.
        if (!bedLockedThisGame && GameStateTracker.getInstance().transitionThisTick()) {
            openCaptureWindow();
        }

        if (capturing) {
            if (captureAge++ % SCAN_EVERY == 0) {
                BedUtil.Bed bed = BedUtil.findNearestBed(OWN_BED_SCAN_RANGE, null);
                if (bed != null) {
                    ownBedFoot = bed.foot;
                }
            }
            boolean terrainReady = mc.thePlayer.sendQueue != null && mc.thePlayer.sendQueue.isDoneLoadingTerrain();
            if (terrainReady && ownBedFoot != null) {
                if (--settleTicks <= 0) {
                    capturing = false;
                    bedLockedThisGame = true;
                }
            } else {
                settleTicks = SETTLE_TICKS;
            }
        }

        // allowEmpty=false prevents unloaded chunks reading as air from permanently clearing the lock.
        if (!capturing && ownBedFoot != null
                && mc.theWorld.isBlockLoaded(ownBedFoot, false)
                && mc.theWorld.getBlockState(ownBedFoot).getBlock() != Blocks.bed) {
            ownBedFoot = null;
        }
    }

    private void openCaptureWindow() {
        ownBedFoot = null;
        bedLockedThisGame = false;
        capturing = true;
        settleTicks = SETTLE_TICKS;
        captureAge = 0;
    }
}
