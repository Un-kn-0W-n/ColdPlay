package coldplay.broker;

import net.minecraft.inventory.Container;
import net.minecraft.inventory.ContainerPlayer;
import net.minecraft.item.ItemStack;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/** Main-thread recovery for automated clicks. Successful clicks remain fully pipelined. */
public final class InventoryTransactions {
    private static final InventoryTransactions INSTANCE = new InventoryTransactions();

    /** Sweeps (one per consumer tick) a click may go unanswered before the server counts as having dropped it. */
    static final int DROP_GRACE_SWEEPS = 40;

    private static final class Operation {
        final int slot, destination, key;
        final boolean unequip, fullStackDrop;
        ItemStack source, target;
        boolean awaitingSnapshot = true;
        boolean needsSpace;

        Operation(Container container, int slot, int button, int mode) {
            this.slot = slot;
            this.destination = mode == 2 ? container.inventorySlots.size() - 9 + button : -1;
            this.key = key(slot, button, mode);
            this.unequip = container instanceof ContainerPlayer && mode == 1 && slot >= 5 && slot < 9;
            this.fullStackDrop = container instanceof ContainerPlayer && mode == 4 && button == 1
                    && slot >= 9 && slot < 45 && container.getSlot(slot).getHasStack();
        }
    }

    private Container container;
    private Object session;
    private boolean recovering;
    private long revision;
    private int idleSweeps;
    private final Map<Short, Operation> pending = new HashMap<Short, Operation>();
    private final Map<Integer, Operation> refused = new HashMap<Integer, Operation>();

    public static InventoryTransactions getInstance() {
        return INSTANCE;
    }

    /** A reopened GUI or replacement container starts a new session; also polls for dropped clicks. */
    public void begin(Container container, Object session) {
        if (this.container != container || this.session != session) {
            reset();
            this.container = container;
            this.session = session;
            return;
        }
        // Every server path that reaches slotClick answers with S32, so a click that is never answered is
        // one the server never ran: a foreign window id, or the refusal window a rejected transaction
        // leaves open. Stop planning on it instead of stacking further predictions on a state it lacks.
        // cannot restore the slots - pipelined predictions have no safe unwind order. They
        // stand until the server next sends them; serialise on hasPending() if that is not enough.
        if (recovering || pending.isEmpty()) {
            idleSweeps = 0;
        } else if (++idleSweeps > DROP_GRACE_SWEEPS) {
            pending.clear();
            recovering = true;
            revision++;
        }
    }

    public void reset() {
        revision++;
        container = null;
        session = null;
        recovering = false;
        idleSweeps = 0;
        pending.clear();
        refused.clear();
    }

    private static int key(int slot, int button, int mode) {
        return (slot << 8) | (button << 4) | mode;
    }

    public boolean isRecovering() {
        return recovering;
    }

    /** Unconfirmed automated clicks in the current container session. */
    public boolean hasPending() {
        return !pending.isEmpty();
    }

    /** Changes only when session or recovery state changes which clicks are eligible. */
    public long getRevision() {
        return revision;
    }

    public boolean canClick(Container container, int slot, int button, int mode) {
        return container != null && this.container == container && !recovering
                && slot >= 0 && slot < container.inventorySlots.size()
                && !refused.containsKey(key(slot, button, mode));
    }

    public void sent(Container container, short id, int slot, int button, int mode) {
        if (this.container == container) {
            pending.put(id, new Operation(container, slot, button, mode));
        }
    }

    /** Unmatched transaction IDs are not click failures (servers also use them as heartbeats). */
    public void confirmed(Container container, short id, boolean accepted) {
        if (this.container != container) {
            return;
        }
        Operation operation = pending.remove(id);
        if (operation != null) idleSweeps = 0;
        if (operation == null && !accepted && id >= 0) {
            // IDs count up from 0 per container: a rejected manual click predates every pending click
            // above it, and the server drops those without replying. Heartbeats are negative.
            // ignores short wraparound after 32k clicks in one container
            if (pending.keySet().removeIf(later -> later > id)) {
                // Those clicks went unanswered, and the server refuses every further one until our C0F
                // lands. Hold automation until the resync a rejection always carries.
                recovering = true;
                revision++;
            }
        } else if (operation != null && !accepted) {
            refused.put(operation.key, operation);
            recovering = true;
            revision++;
            // Later predictions depended on the rejected local inventory. The full resync replaces them.
            pending.clear();
        } else if (operation != null && !recovering && operation.fullStackDrop
                && !container.getSlot(operation.slot).getHasStack()) {
            // Vanilla acknowledges a successful drop without sending the emptied slot again.
            if (refused.values().removeIf(failed -> !failed.awaitingSnapshot && failed.needsSpace)) revision++;
        }
    }

    /** Called with authoritative contents, after vanilla applies the full inventory resync. */
    public void contents(Container container, ItemStack[] stacks) {
        if (this.container != container) {
            return;
        }
        // Authoritative contents supersede every prediction still in flight, including clicks the server
        // dropped without replying; those ids would otherwise pin hasPending() true for the session.
        pending.clear();
        idleSweeps = 0;
        int refusedBefore = refused.size();
        Iterator<Operation> it = refused.values().iterator();
        while (it.hasNext()) {
            Operation operation = it.next();
            ItemStack source = stack(stacks, operation.slot);
            ItemStack target = stack(stacks, operation.destination);
            if (operation.awaitingSnapshot) {
                operation.source = copy(source);
                operation.target = copy(target);
                operation.needsSpace = operation.unequip && !hasArmorSpace(stacks);
                operation.awaitingSnapshot = false;
            } else if (!ItemStack.areItemStacksEqual(operation.source, source)
                    || !ItemStack.areItemStacksEqual(operation.target, target)
                    || operation.needsSpace && hasArmorSpace(stacks)) {
                it.remove();
            }
        }
        if (recovering || refused.size() != refusedBefore) revision++;
        recovering = false;
    }

    /** Only authoritative changes unlock a failed operation; unrelated optimistic clicks cannot. */
    public void slotChanged(Container container, int slot, ItemStack stack) {
        if (this.container != container || recovering) {
            return;
        }
        if (refused.values().removeIf(operation -> !operation.awaitingSnapshot
                && (operation.slot == slot && !ItemStack.areItemStacksEqual(operation.source, stack)
                || operation.destination == slot && !ItemStack.areItemStacksEqual(operation.target, stack)
                || operation.needsSpace && slot >= 9 && slot < 45 && stack == null))) revision++;
    }

    // Equipped armor quick-moves into the main inventory or hotbar, never the crafting grid.
    private static boolean hasArmorSpace(ItemStack[] stacks) {
        for (int slot = 9; slot < 45 && slot < stacks.length; slot++) {
            if (stacks[slot] == null) return true;
        }
        return false;
    }

    private static ItemStack stack(ItemStack[] stacks, int slot) {
        return slot >= 0 && slot < stacks.length ? stacks[slot] : null;
    }

    private static ItemStack copy(ItemStack stack) {
        return stack == null ? null : stack.copy();
    }
}
