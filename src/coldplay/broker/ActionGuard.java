package coldplay.broker;

import coldplay.event.EventAttack;
import coldplay.event.EventPriority;
import coldplay.event.EventTarget;
import coldplay.event.EventUpdate;
import coldplay.event.EventUse;
import net.minecraft.client.Minecraft;

/** Reserves producer movement windows; PlayerPacketState also validates attacks after buffering. */
public final class ActionGuard {

    private static final ActionGuard INSTANCE = new ActionGuard();
    private static final Object MANUAL_OWNER = new Object();

    private boolean playerActedThisTick;
    private boolean playerActedLastTick;
    private Object reservationOwner;

    private ActionGuard() {
    }

    public static ActionGuard getInstance() {
        return INSTANCE;
    }

    // History rolls here; reservations clear only after the next movement packet.
    @EventTarget(priority = EventPriority.BROKER_RESET)
    public void onUpdate(EventUpdate event) {
        if (!event.isPre()) {
            return;
        }
        playerActedLastTick = playerActedThisTick;
        playerActedThisTick = false;
    }

    /** Producer-window boundary. Final dispatched action/pose checks belong to PlayerPacketState. */
    public synchronized void onMovementPacketQueued() {
        reservationOwner = null;
    }

    @EventTarget(priority = EventPriority.BROKER_RESET)
    public void onAttack(EventAttack event) {
        if (!recordManualInteraction()) {
            event.setCancelled(true);
        }
    }

    @EventTarget(priority = EventPriority.BROKER_RESET)
    public void onUse(EventUse event) {
        if (!recordManualInteraction()) {
            event.setCancelled(true);
        }
    }

    private synchronized boolean recordManualInteraction() {
        if (reservationOwner != null) {
            return false;
        }
        playerActedThisTick = true;
        reservationOwner = MANUAL_OWNER;
        return true;
    }

    /** Whether the player has attacked or used so far this tick (accurate from {@code EventMotion} PRE on). */
    public boolean playerActedThisTick() {
        return playerActedThisTick;
    }

    /** Whether the player attacked or used last tick (for steps deciding at {@code EventUpdate} PRE). */
    public boolean playerActedLastTick() {
        return playerActedLastTick;
    }

    public boolean isReserved() {
        return reservationOwner != null;
    }

    public boolean playerInputDown() {
        Minecraft mc = Minecraft.getMinecraft();
        return mc.gameSettings != null && (mc.gameSettings.keyBindAttack.isKeyDown()
                || mc.gameSettings.keyBindUseItem.isKeyDown());
    }

    /** Reserve from Update PRE only when the previous tick was clean. */
    public synchronized boolean tryReserveAfterCleanTick(Object who) {
        return !playerActedLastTick && tryReserve(who);
    }

    /**
     * Manual input takes priority over automation. A held token is spent even when {@code who}
     * owns it; reserve immediately before sending the interaction packet.
     */
    public synchronized boolean tryReserve(Object who) {
        if (reservationOwner != null) {
            return false;
        }
        if (playerInputDown()) {
            return false; // held physical input wins even before its click event is posted
        }
        if (playerActedThisTick) {
            return false;
        }
        reservationOwner = who;
        return true;
    }
}
