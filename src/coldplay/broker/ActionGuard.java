package coldplay.broker;

import coldplay.event.EventAttack;
import coldplay.event.EventPriority;
import coldplay.event.EventTarget;
import coldplay.event.EventUpdate;
import coldplay.event.EventUse;
import net.minecraft.client.Minecraft;

/** Lets one owner per movement-packet window attack or use an item; manual input always wins. */
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

    // Reservations clear in onMovementPacketQueued, not here.
    @EventTarget(priority = EventPriority.BROKER_RESET)
    public void onUpdate(EventUpdate event) {
        if (!event.isPre()) {
            return;
        }
        playerActedLastTick = playerActedThisTick;
        playerActedThisTick = false;
    }

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

    public boolean playerActedThisTick() {
        return playerActedThisTick;
    }

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

    public synchronized boolean tryReserveAfterCleanTick(Object who) {
        return !playerActedLastTick && tryReserve(who);
    }

    /** Reserve right before sending; a held reservation fails even for its own owner. */
    public synchronized boolean tryReserve(Object who) {
        if (reservationOwner != null) {
            return false;
        }
        if (playerInputDown()) {
            return false; // held input wins before its click event posts
        }
        if (playerActedThisTick) {
            return false;
        }
        reservationOwner = who;
        return true;
    }
}
