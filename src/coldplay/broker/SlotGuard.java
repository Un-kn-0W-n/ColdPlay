package coldplay.broker;

import coldplay.event.EventPriority;
import coldplay.event.EventTarget;
import coldplay.event.EventUpdate;
import coldplay.util.ResourcePriority;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;

/**
 * Arbitrates temporary hotbar overrides while retaining the player's chosen slot for restoration.
 * Higher-priority requests may evict an owner; player-driven changes always take priority.
 * Synchronous Update PRE writes let vanilla flush C09 during its usual controller sync.
 */
public final class SlotGuard {

    private static final SlotGuard INSTANCE = new SlotGuard();
    private static final int NONE = -1;

    private Object owner;
    /** Priority the current owner acquired at; only meaningful while {@code owner != null}. */
    private int ownerPriority;
    /** The slot the player genuinely selected; what {@link #release} restores to. */
    private int baseSlot = NONE;
    /** The last {@code currentItem} value the guard set or saw, used to spot player-driven changes. */
    private int lastKnownSlot = NONE;
    /** Respawn and server switch replace the player between ticks; a new identity resets the guard. */
    private EntityPlayerSP trackedPlayer;

    private SlotGuard() {
    }

    public static SlotGuard getInstance() {
        return INSTANCE;
    }

    /**
     * Runs before any module each tick (max priority). Detects player-initiated slot changes and keeps
     * {@link #baseSlot} pointed at the player's real selection.
     */
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

    /** Player input wins: a slot the guard did not set becomes the base and drops any override. */
    private boolean syncPlayerSlot(int cur) {
        boolean moved = lastKnownSlot != NONE && cur != lastKnownSlot;
        if (moved || baseSlot == NONE) {
            baseSlot = cur;
            owner = null;
        }
        lastKnownSlot = cur;
        return moved;
    }

    /**
     * Requests normal-priority control. The current owner may reassert its slot.
     */
    public synchronized boolean request(Object who, int slot) {
        return request(who, slot, ResourcePriority.NORMAL);
    }

    /**
     * A strictly higher priority evicts the current holder. Callers detect eviction through
     * {@link #isHeldBy}. Eviction retains the base slot so a transient override cannot become the
     * player's restored selection.
     */
    public synchronized boolean request(Object who, int slot, int priority) {
        if (slot < 0 || slot > 8) {
            return false;
        }
        EntityPlayerSP player = Minecraft.getMinecraft().thePlayer;
        if (player == null) {
            return false;
        }
        if (syncPlayerSlot(player.inventory.currentItem)) {
            return false; // hotbar key/scroll landed since the PRE poll: the player's pick stands this tick
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

    /** Release control and restore the player's base slot, exactly once. No-op unless {@code who} owns it. */
    public synchronized void release(Object who) {
        clearOwner(who, true);
    }

    /**
     * Give up control <em>without</em> restoring — the currently-held slot becomes the new base. Used
     * when a module is configured to stay on the slot it switched to (e.g. AutoTool's SwitchBack off).
     */
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
