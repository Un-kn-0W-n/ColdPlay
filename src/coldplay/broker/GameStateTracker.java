package coldplay.broker;

import coldplay.event.EventPriority;
import coldplay.event.EventTarget;
import coldplay.event.EventUpdate;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.world.WorldSettings;

/**
 * Flags the tick a game transition happens (respawn/join packet or a large position jump).
 * Must register before BedTracker, which shares its priority and reads the flag the same tick.
 */
public final class GameStateTracker {

    private static final GameStateTracker INSTANCE = new GameStateTracker();

    public static GameStateTracker getInstance() {
        return INSTANCE;
    }

    private static final double TRANSITION_DIST_SQ = 40.0 * 40.0;

    private volatile boolean transitionPending; // written from the netty thread

    private boolean transitionThisTick;
    private boolean respawnThisTick;
    private double jumpDistanceThisTick;
    private boolean combatInactive;
    private boolean combatEndedThisTick;

    private double lastPosX, lastPosY, lastPosZ;
    private boolean hasLastPos;

    private GameStateTracker() {
    }

    public void markTransitionPending() {
        transitionPending = true;
    }

    public boolean transitionThisTick() {
        return transitionThisTick;
    }

    public boolean respawnThisTick() {
        return respawnThisTick;
    }

    public double jumpDistanceThisTick() {
        return jumpDistanceThisTick;
    }

    public boolean combatEndedThisTick() {
        return combatEndedThisTick;
    }

    public boolean isCombatInactive() {
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP player = mc.thePlayer;
        // Server spectators are alive adventure-mode players with allowFlying; landing only clears isFlying.
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

        lastPosX = px;
        lastPosY = py;
        lastPosZ = pz;
        hasLastPos = true;
    }
}
