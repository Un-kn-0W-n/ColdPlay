package coldplay.module.utility;

import coldplay.event.EventTarget;
import coldplay.event.EventUpdate;
import coldplay.module.Category;
import coldplay.module.Module;
import coldplay.setting.RangeSetting;
import coldplay.broker.ActionGuard;
import coldplay.broker.InventoryTransactions;
import coldplay.broker.PacketLog;
import coldplay.util.InvUtil;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.gui.inventory.GuiChest;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.ContainerChest;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;

import java.util.function.BooleanSupplier;

/** Shift-clicks stacks out of an open chest, nearest to the last click first. */
public class ChestStealer extends Module {
    private final RangeSetting delay = add(new RangeSetting("Delay", 120.0, 520.0, 50.0, 1000.0, 5.0).describe("Wait between item moves (ms): min for adjacent slots, max across the chest."));
    private final BooleanSupplier autoClose;

    private Container active;
    private int lastSlot = -1;
    private long nextAt;

    public ChestStealer(BooleanSupplier autoClose) {
        super("ChestStealer", Category.UTILITY, "Auto-empties an open chest into your inventory.");
        this.autoClose = autoClose;
        addAutoOff();
    }

    @Override
    protected void onDisable() {
        active = null;
    }

    @EventTarget
    public void onUpdate(EventUpdate event) {
        if (!event.isPre()) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP player = mc.thePlayer;
        if (player == null || mc.theWorld == null || !(mc.currentScreen instanceof GuiChest)
                || !(player.openContainer instanceof ContainerChest) || !isChest((ContainerChest) player.openContainer)) {
            active = null;
            return;
        }
        Container container = player.openContainer;
        InventoryTransactions transactions = InventoryTransactions.getInstance();
        transactions.begin(container, mc.currentScreen);
        if (active != container) {
            active = container;
            lastSlot = -1;
            nextAt = System.currentTimeMillis() + InvUtil.reactionDelayMs();
        }
        if (transactions.isRecovering() || player.inventory.getItemStack() != null
                || ActionGuard.getInstance().playerActedLastTick() || InvUtil.isPlayerMoving(player)
                || System.currentTimeMillis() < nextAt) {
            return;
        }

        int chestSlots = ((ContainerChest) container).getLowerChestInventory().getSizeInventory();
        Slot target = nearest(container, chestSlots, lastSlot);
        if (target == null) {
            // wait for pending replies; they may refute the local prediction
            if (autoClose.getAsBoolean() && !transactions.hasPending()) {
                player.closeScreen();
            }
            return;
        }
        if (!ActionGuard.getInstance().tryReserve(this)) {
            return;
        }
        int slot = target.slotNumber;
        PacketLog.getInstance().tagged("ChestStealer", () -> {
            if (!InvUtil.click(player, container, slot, 0, 1)) {
                return;
            }
            Slot next = nearest(container, chestSlots, slot); // click already predicted locally
            nextAt = System.currentTimeMillis() + InvUtil.moveDelayMs(delay.getLo(), delay.getHi(), container,
                    slot, next == null ? -1 : next.slotNumber);
            lastSlot = slot;
        });
    }

    // NPC shop menus reuse GuiChest; real chests all have "chest" in the name.
    static boolean isChest(ContainerChest container) {
        return container.getLowerChestInventory().getDisplayName().getUnformattedText().toLowerCase().contains("chest");
    }

    static Slot nearest(Container container, int chestSlots, int fromSlot) {
        Slot from = InvUtil.slotByNumber(container, fromSlot), best = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (int i = 0; i < chestSlots; i++) {
            Slot slot = container.getSlot(i);
            if (!slot.getHasStack() || !fits(container, chestSlots, slot.getStack())
                    || !InventoryTransactions.getInstance().canClick(container, i, 0, 1)) {
                continue;
            }
            double distance = InvUtil.slotDistance(from, slot);
            if (distance < bestDistance) {
                best = slot;
                bestDistance = distance;
            }
        }
        return best;
    }

    // Container.mergeItemStack is protected and mutates, so redo the check read-only.
    static boolean fits(Container c, int chestSlots, ItemStack stack) {
        for (int i = chestSlots; i < c.inventorySlots.size(); i++) {
            ItemStack in = c.getSlot(i).getStack();
            if (in == null) {
                return true;
            }
            if (stack.isStackable() && in.getItem() == stack.getItem()
                    && (!stack.getHasSubtypes() || stack.getMetadata() == in.getMetadata())
                    && ItemStack.areItemStackTagsEqual(stack, in)
                    && in.stackSize < stack.getMaxStackSize()) {
                return true;
            }
        }
        return false;
    }
}
