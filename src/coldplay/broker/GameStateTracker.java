package coldplay.broker;

import coldplay.event.EventPriority;
import coldplay.event.EventTarget;
import coldplay.event.EventUpdate;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.world.WorldSettings;

/**
 * Detects game transitions from respawn/join notifications and actual per-tick position deltas,
 * avoiding unreliable post-respawn S08 distances. BedTracker shares its event priority, so this
 * tracker must register first to publish the transition before BedTracker reads it.
 */
public final class GameStateTracker {

    private static final GameStateTracker INSTANCE = new GameStateTracker();

    public static GameStateTracker getInstance() {
        return INSTANCE;
    }

    /** A jump over 40 blocks in one tick is treated as a spawn teleport. */
    private static final double TRANSITION_DIST_SQ = 40.0 * 40.0;

    /** Set from the respawn/join handlers; consumed on the next tick. Volatile: netty may touch it. */
    private volatile boolean transitionPending;

    private boolean transitionThisTick;
    private boolean respawnThisTick;
    private double jumpDistanceThisTick;
    private boolean combatInactive;
    private boolean combatEndedThisTick;

    private double lastPosX, lastPosY, lastPosZ;
    private boolean hasLastPos;

    private GameStateTracker() {
    }

    /** Flagged from {@code NetHandlerPlayClient.handleRespawn/handleJoinGame}; next tick consumes it. */
    public void markTransitionPending() {
        transitionPending = true;
    }

    /** True on the tick a game transition was detected (respawn/join packet or a 40+ block jump). */
    public boolean transitionThisTick() {
        return transitionThisTick;
    }

    /** True on the tick a respawn/join arrived, independent of any distance threshold. */
    public boolean respawnThisTick() {
        return respawnThisTick;
    }

    /** Blocks moved this tick (0 when idle) — lets consumers apply their own distance threshold. */
    public double jumpDistanceThisTick() {
        return jumpDistanceThisTick;
    }

    public boolean combatEndedThisTick() {
        return combatEndedThisTick;
    }

    public boolean isCombatInactive() {
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP player = mc.thePlayer;
        // Server spectators can stay alive in adventure mode. Landing only clears isFlying.
        return player != null && (!player.isEntityAlive() || player.isSpectator()
                || mc.playerController != null
                && mc.playerController.getCurrentGameType() == WorldSettings.GameType.ADVENTURE
                && player.capabilities.allowFlying);
    }

    @EventTarget(priority = EventPriority.STATE_TRACKING)
    public void onUpdate(EventUpdate event) {
        if (!event.isPre()) {
            return;
        }
        // Clear first so a stale true can't leak past an early return.
        transitionThisTick = false;
        respawnThisTick = false;
        jumpDistanceThisTick = 0.0;
        combatEndedThisTick = false;

        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null || mc.theWorld == null) {
            combatInactive = false;
            return;
        }

        boolean inactive = isCombatInactive();
        combatEndedThisTick = inactive && !combatInactive;
        combatInactive = inactive;
        if (combatEndedThisTick) {
            mc.thePlayer.sendQueue.getNetworkManager().getPlayerPackets().invalidateActions();
        }

        double px = mc.thePlayer.posX;
        double py = mc.thePlayer.posY;
        double pz = mc.thePlayer.posZ;

        boolean respawn = transitionPending;
        transitionPending = false;

        boolean jump = false;
        if (hasLastPos) {
            double dx = px - lastPosX;
            double dy = py - lastPosY;
            double dz = pz - lastPosZ;
            double distSq = dx * dx + dy * dy + dz * dz;
            jumpDistanceThisTick = Math.sqrt(distSq);
            jump = distSq > TRANSITION_DIST_SQ;
        }

        respawnThisTick = respawn;
        transitionThisTick = respawn || jump;

        // Sample every tick (GUI open included): jumps are measured against this, and the respawn
        // packet + the spawn S08 can land in the same tick.
        lastPosX = px;
        lastPosY = py;
        lastPosZ = pz;
        hasLastPos = true;
    }
}
