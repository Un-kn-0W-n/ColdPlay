package coldplay.broker;

import coldplay.event.EventPriority;
import coldplay.event.EventTarget;
import coldplay.event.EventUpdate;
import coldplay.util.BedUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.init.Blocks;
import net.minecraft.util.BlockPos;

/** Locks onto the player's own bed at game start once nearby chunks have settled. */
public final class BedTracker {

    private static final BedTracker INSTANCE = new BedTracker();

    public static BedTracker getInstance() {
        return INSTANCE;
    }

    private static final int OWN_BED_SCAN_RANGE = 12; // blocks
    private static final int SETTLE_TICKS = 5;
    private static final int SCAN_EVERY = 4; // ticks

    private BlockPos ownBedFoot;
    private boolean capturing;
    private int settleTicks;
    private int captureAge;
    private boolean bedLockedThisGame;
    private WorldClient lastWorld;

    private BedTracker() {
    }

    /** Null until captured, and again once the bed is broken. */
    public BlockPos ownBedFoot() {
        return ownBedFoot;
    }

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

        // Once locked, a respawn must not re-capture an enemy bed.
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

        // allowEmpty=false, otherwise an unloaded chunk reads as air and clears the lock.
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
