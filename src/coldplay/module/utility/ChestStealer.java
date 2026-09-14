package coldplay.module.utility;

import coldplay.event.EventOpenWindow;
import coldplay.event.EventTarget;
import coldplay.event.EventUpdate;
import coldplay.event.EventUse;
import coldplay.module.Category;
import coldplay.module.Module;
import coldplay.setting.ModeSetting;
import coldplay.setting.RangeSetting;
import coldplay.broker.ActionGuard;
import coldplay.broker.InventoryTransactions;
import coldplay.broker.PacketLog;
import coldplay.util.InvUtil;

import net.minecraft.block.Block;
import net.minecraft.block.BlockChest;
import net.minecraft.block.BlockEnderChest;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.gui.inventory.GuiChest;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.ContainerChest;
import net.minecraft.inventory.InventoryBasic;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.world.World;

import java.util.function.BooleanSupplier;

/** Shift-clicks stacks out of an open chest, nearest to the last click first. Hypixel mode never shows the GUI. */
public class ChestStealer extends Module {
    private static final String NORMAL = "Normal";
    private static final String HYPIXEL = "Hypixel";

    private final ModeSetting mode = add(new ModeSetting("Mode", NORMAL, NORMAL, HYPIXEL)
            .describe("Normal steals from the chest GUI. Hypixel keeps the chest hidden and takes everything in one tick."));
    private final RangeSetting delay = add(new RangeSetting("Delay", 120.0, 520.0, 50.0, 1000.0, 5.0).describe("Wait between item moves (ms): min for adjacent slots, max across the chest."));
    private final BooleanSupplier autoClose;

    private Container active;
    private boolean clickedChest, realChest;
    private int lastSlot = -1, silentId = -1;
    private long nextAt;

    public ChestStealer(BooleanSupplier autoClose) {
        super("ChestStealer", Category.UTILITY, "Auto-empties an open chest into your inventory.");
        this.autoClose = autoClose;
        addAutoOff();
        delay.visibleWhen(() -> !hypixel()).indent(1);
    }

    private boolean hypixel() {
        return HYPIXEL.equals(mode.get());
    }

    @Override
    protected void onDisable() {
        EntityPlayerSP player = Minecraft.getMinecraft().thePlayer;
        if (player != null && silentId != -1) {
            closeSilent(player);
        }
        silentId = -1;
        active = null;
        clickedChest = false;
    }

    // NPC and compass menus reuse GuiChest, so only steal from a menu opened by clicking a chest block.
    @EventTarget
    public void onUse(EventUse event) {
        MovingObjectPosition hit = event.getTarget();
        World world = Minecraft.getMinecraft().theWorld;
        clickedChest = false;
        if (world != null && hit != null && hit.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK) {
            Block block = world.getBlockState(hit.getBlockPos()).getBlock();
            clickedChest = block instanceof BlockChest || block instanceof BlockEnderChest;
        }
    }

    // The server only sees packets, so open the container without its screen.
    @EventTarget
    public void onOpenWindow(EventOpenWindow event) {
        EntityPlayerSP player = Minecraft.getMinecraft().thePlayer;
        String gui = event.getGuiId();
        if (!hypixel() || !clickedChest || player == null
                || !("minecraft:chest".equals(gui) || "minecraft:container".equals(gui))) {
            return;
        }
        player.openContainer = new ContainerChest(player.inventory,
                new InventoryBasic(event.getTitle(), event.getSlotCount()), player);
        player.openContainer.windowId = silentId = event.getWindowId();
        event.setCancelled(true);
    }

    @EventTarget
    public void onUpdate(EventUpdate event) {
        if (!event.isPre()) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP player = mc.thePlayer;
        if (player != null && silentId != -1 && player.openContainer.windowId != silentId) {
            closeSilent(player); // inventory opened over it, or the server closed it
        }
        boolean silent = silentId != -1 && mc.currentScreen == null;
        if (player == null || mc.theWorld == null || !(silent || mc.currentScreen instanceof GuiChest)
                || !(player.openContainer instanceof ContainerChest)) {
            active = null;
            return;
        }
        Container container = player.openContainer;
        InventoryTransactions transactions = InventoryTransactions.getInstance();
        transactions.begin(container, mc.currentScreen);
        if (active != container) {
            active = container;
            realChest = silent || clickedChest; // silent windows were already checked on open
            clickedChest = false;
            lastSlot = -1;
            nextAt = System.currentTimeMillis() + (silent ? 100 : InvUtil.reactionDelayMs());
        }
        if (!realChest || transactions.isRecovering() || player.inventory.getItemStack() != null
                || ActionGuard.getInstance().playerActedLastTick() || InvUtil.isPlayerMoving(player)
                || System.currentTimeMillis() < nextAt) {
            return;
        }

        int chestSlots = ((ContainerChest) container).getLowerChestInventory().getSizeInventory();
        Slot target = nearest(container, chestSlots, lastSlot);
        if (target == null) {
            // wait for pending replies; they may refute the local prediction
            if (transactions.hasPending()) {
                return;
            }
            if (silent) {
                closeSilent(player);
            } else if (autoClose.getAsBoolean()) {
                player.closeScreen();
            }
            return;
        }
        if (!ActionGuard.getInstance().tryReserve(this)) {
            return;
        }
        int slot = target.slotNumber;
        PacketLog.getInstance().tagged("ChestStealer", () -> {
            if (silent) {
                Slot s = target;
                for (int i = 0; s != null && i < chestSlots; i++) {
                    if (!InvUtil.click(player, container, s.slotNumber, 0, 1)) {
                        break;
                    }
                    s = nearest(container, chestSlots, s.slotNumber);
                }
                return;
            }
            if (!InvUtil.click(player, container, slot, 0, 1)) {
                return;
            }
            Slot next = nearest(container, chestSlots, slot); // click already predicted locally
            nextAt = System.currentTimeMillis() + InvUtil.moveDelayMs(delay.getLo(), delay.getHi(), container,
                    slot, next == null ? -1 : next.slotNumber);
            lastSlot = slot;
        });
    }

    // closeScreen() would also shut chat or the pause menu, so close by packet.
    private void closeSilent(EntityPlayerSP player) {
        boolean ours = player.openContainer.windowId == silentId;
        if (ours || player.openContainer == player.inventoryContainer) {
            InvUtil.closeWindow(player, silentId);
        }
        if (ours) {
            player.openContainer = player.inventoryContainer;
        }
        silentId = -1;
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
