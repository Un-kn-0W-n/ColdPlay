package coldplay.broker;

import coldplay.event.EventPriority;
import coldplay.event.EventTarget;
import coldplay.event.EventUpdate;
import coldplay.util.ResourcePriority;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.multiplayer.WorldClient;

/**
 * Arbitrates temporary hotbar slot overrides and restores the player's own slot on release.
 * A higher priority evicts the current owner; a slot change by the player always wins.
 */
public final class SlotGuard {

    private static final SlotGuard INSTANCE = new SlotGuard();
    private static final int NONE = -1;

    private Object owner;
    private int ownerPriority;
    private int baseSlot = NONE;
    private int lastKnownSlot = NONE;
    private EntityPlayerSP trackedPlayer;
    private WorldClient trackedWorld;
    private boolean restorePending;

    private SlotGuard() {
    }

    public static SlotGuard getInstance() {
        return INSTANCE;
    }

    @EventTarget(priority = EventPriority.BROKER_RESET)
    public synchronized void onUpdate(EventUpdate event) {
        if (!event.isPre()) {
            return;
        }
        EntityPlayerSP player = syncSession();
        if (player != null) {
            syncPlayerSlot(player.inventory.currentItem);
            restoreIfSafe(player);
        }
    }

    /** Requests and releases can arrive before the next PRE after a world/player change. */
    private EntityPlayerSP syncSession() {
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP player = mc.thePlayer;
        if (player != trackedPlayer || mc.theWorld != trackedWorld) {
            reset();
            trackedPlayer = player;
            trackedWorld = mc.theWorld;
        }
        return player;
    }

    /** A slot change the guard did not make becomes the new base and drops any override. */
    private boolean syncPlayerSlot(int cur) {
        boolean moved = lastKnownSlot != NONE && cur != lastKnownSlot;
        if (moved || baseSlot == NONE) {
            baseSlot = cur;
            owner = null;
            ownerPriority = 0;
            restorePending = false;
        }
        lastKnownSlot = cur;
        return moved;
    }

    public synchronized boolean request(Object who, int slot) {
        return request(who, slot, ResourcePriority.NORMAL);
    }

    /** A strictly higher priority evicts the current owner, who can see that through {@link #isHeldBy}. */
    public synchronized boolean request(Object who, int slot, int priority) {
        if (slot < 0 || slot > 8) {
            return false;
        }
        EntityPlayerSP player = syncSession();
        if (player == null) {
            return false;
        }
        if (syncPlayerSlot(player.inventory.currentItem)) {
            return false; // the player changed slot since the PRE poll
        }
        if (owner != null && owner != who && priority <= ownerPriority) {
            return false;
        }
        if (owner == null && !restorePending) {
            baseSlot = player.inventory.currentItem;
        }
        // A new override inherits the original base, not an unrestored temporary slot.
        restorePending = false;
        owner = who;
        ownerPriority = priority;
        player.inventory.currentItem = slot;
        lastKnownSlot = slot;
        return true;
    }

    /** Restores the base slot; no-op unless {@code who} owns the guard. */
    public synchronized void release(Object who) {
        clearOwner(who, true);
    }

    /**
     * Gives up control now, but waits for item use and GUIs to finish before restoring.
     * The pending restore survives the caller being disabled; manual slot changes and
     * session changes cancel it, and a new requester inherits the original base slot.
     */
    public synchronized void releaseWhenSafe(Object who) {
        EntityPlayerSP player = ownedPlayer(who);
        if (player == null) {
            return;
        }
        owner = null;
        ownerPriority = 0;
        restorePending = true;
        restoreIfSafe(player);
    }

    private void restoreIfSafe(EntityPlayerSP player) {
        if (!restorePending || player.isUsingItem() || Minecraft.getMinecraft().currentScreen != null) {
            return;
        }
        if (baseSlot >= 0 && baseSlot <= 8) {
            player.inventory.currentItem = baseSlot;
        }
        lastKnownSlot = player.inventory.currentItem;
        restorePending = false;
    }

    /** Gives up control without restoring; the current slot becomes the new base. */
    public synchronized void relinquish(Object who) {
        clearOwner(who, false);
    }

    /** Poll before releasing too: a manual scroll since PRE must never be undone. */
    private EntityPlayerSP ownedPlayer(Object who) {
        EntityPlayerSP player = syncSession();
        if (player != null) {
            syncPlayerSlot(player.inventory.currentItem);
        }
        return owner == who ? player : null;
    }

    private void clearOwner(Object who, boolean restoreBase) {
        EntityPlayerSP player = ownedPlayer(who);
        if (player == null) {
            return;
        }
        owner = null;
        ownerPriority = 0;
        restorePending = false;
        if (restoreBase && baseSlot >= 0 && baseSlot <= 8) {
            player.inventory.currentItem = baseSlot;
        } else if (!restoreBase) {
            baseSlot = player.inventory.currentItem;
        }
        lastKnownSlot = player.inventory.currentItem;
    }

    public boolean isHeldBy(Object who) {
        return owner == who;
    }

    public boolean isFree() {
        return owner == null;
    }

    public boolean isBusyAbove(int priority) {
        return owner != null && ownerPriority > priority;
    }

    private void reset() {
        owner = null;
        ownerPriority = 0;
        baseSlot = NONE;
        lastKnownSlot = NONE;
        restorePending = false;
    }
}
