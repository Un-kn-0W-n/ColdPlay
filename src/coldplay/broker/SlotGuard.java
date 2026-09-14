package coldplay.broker;

import coldplay.event.EventPriority;
import coldplay.event.EventTarget;
import coldplay.event.EventUpdate;
import coldplay.util.ResourcePriority;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;

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

    private SlotGuard() {
    }

    public static SlotGuard getInstance() {
        return INSTANCE;
    }

    @EventTarget(priority = EventPriority.BROKER_RESET)
    public void onUpdate(EventUpdate event) {
        if (!event.isPre()) {
            return;
        }
        EntityPlayerSP player = Minecraft.getMinecraft().thePlayer;
        if (player != trackedPlayer) {
            reset();
            trackedPlayer = player;
        }
        if (player != null) {
            syncPlayerSlot(player.inventory.currentItem);
        }
    }

    /** A slot change the guard did not make becomes the new base and drops any override. */
    private boolean syncPlayerSlot(int cur) {
        boolean moved = lastKnownSlot != NONE && cur != lastKnownSlot;
        if (moved || baseSlot == NONE) {
            baseSlot = cur;
            owner = null;
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
        EntityPlayerSP player = Minecraft.getMinecraft().thePlayer;
        if (player == null) {
            return false;
        }
        if (syncPlayerSlot(player.inventory.currentItem)) {
            return false; // the player changed slot since the PRE poll
        }
        if (owner != null && owner != who && priority <= ownerPriority) {
            return false;
        }
        if (owner == null) {
            baseSlot = player.inventory.currentItem;
        }
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

    /** Gives up control without restoring; the current slot becomes the new base. */
    public synchronized void relinquish(Object who) {
        clearOwner(who, false);
    }

    private void clearOwner(Object who, boolean restoreBase) {
        if (owner != who) {
            return;
        }
        EntityPlayerSP player = Minecraft.getMinecraft().thePlayer;
        owner = null;
        ownerPriority = 0;
        if (player == null) {
            return;
        }
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
    }
}
