package coldplay.module.utility;

import coldplay.broker.ActionGuard;
import coldplay.broker.InventoryTransactions;
import coldplay.event.EventOpenWindow;
import coldplay.event.EventTarget;
import coldplay.event.EventUpdate;
import coldplay.event.EventUse;
import coldplay.module.Category;
import coldplay.module.Module;
import coldplay.setting.BooleanSetting;
import coldplay.setting.ModeSetting;
import coldplay.setting.RangeSetting;
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

/** Quick-moves an open chest into the player inventory, nearest slot first. */
public class ChestStealer extends Module {

    private static final String NORMAL = "Normal", HYPIXEL = "Hypixel";
    /** A chest the player opened shows up within this; anything later is a menu the server opened. */
    private static final long OPEN_WITHIN_MS = 3000L;
    private static final long SILENT_START_MS = 100L;
    /** Every ContainerChest appends the player's 27 main slots and 9 hotbar slots after the chest. */
    private static final int PLAYER_SLOTS = 36;

    private final ModeSetting mode = add(new ModeSetting("Mode", NORMAL, NORMAL, HYPIXEL)
            .describe("Normal steals from the chest GUI. Hypixel keeps the chest hidden and takes everything in one tick."));
    private final RangeSetting delay = add(new RangeSetting("Delay", 120.0, 520.0, 50.0, 1000.0, 5.0)
            .describe("Wait between item moves (ms): min for adjacent slots, max across the chest."));
    private final BooleanSetting autoClose = add(new BooleanSetting("Auto Close", false)
            .describe("Close the chest once there is nothing left worth taking."));

    private Container active;
    private boolean ours;       // the open container is a chest the player asked for
    private int silentId = -1;  // window kept hidden from the player, -1 when none
    private int lastSlot = -1;
    private long chestUseAt;
    private long nextAt;

    public ChestStealer() {
        super("ChestStealer", Category.UTILITY, "Auto-empties an open chest into your inventory.");
        addAutoOff();
        delay.visibleWhen(() -> !hypixel()).indent(1);
    }

    private boolean hypixel() {
        return HYPIXEL.equals(mode.get());
    }

    @Override
    protected void onDisable() {
        EntityPlayerSP player = Minecraft.getMinecraft().thePlayer;
        if (player != null) {
            closeSilent(player);
        }
        silentId = -1;
        active = null;
        chestUseAt = 0L;
    }

    /** Only a chest the player just right-clicked is fair game; server menus reuse the chest GUI. */
    @EventTarget
    public void onUse(EventUse event) {
        chestUseAt = isChest(event.getTarget()) ? System.currentTimeMillis() : 0L;
    }

    /** Hypixel mode: swallow the chest window and rebuild it client-side, so no GUI ever opens. */
    @EventTarget
    public void onOpenWindow(EventOpenWindow event) {
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP player = mc.thePlayer;
        String gui = event.getGuiId();
        // Window 0 is the player's own inventory, and a screen already up means taking over
        // openContainer would void every click the player makes in it.
        if (!hypixel() || player == null || mc.currentScreen != null || !opened()
                || event.getWindowId() == 0 || event.getSlotCount() <= 0
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
        if (player == null || mc.theWorld == null) {
            silentId = -1; // that window belonged to a session that is gone
            active = null;
            return;
        }
        // A hidden window has to go the moment it stops being hidden: while it is the open
        // container, every manual click the player makes is dropped before it is sent.
        if (silentId != -1 && (player.openContainer.windowId != silentId || mc.currentScreen != null)) {
            closeSilent(player);
        }
        boolean silent = silentId != -1;
        if (!(silent || mc.currentScreen instanceof GuiChest)
                || !(player.openContainer instanceof ContainerChest)) {
            active = null;
            return;
        }

        Container container = player.openContainer;
        InventoryTransactions transactions = InventoryTransactions.getInstance();
        transactions.begin(container, mc.currentScreen);
        if (active != container) {
            active = container;
            ours = silent || opened(); // latched once: emptying a chest outlasts the click window
            chestUseAt = 0L;
            lastSlot = -1;
            nextAt = System.currentTimeMillis() + (silent ? SILENT_START_MS : InvUtil.reactionDelayMs());
        }
        if (!ours || transactions.isRecovering() || player.inventory.getItemStack() != null
                || System.currentTimeMillis() < nextAt) {
            return;
        }

        // Count the slots the container actually built, not the ones the inventory claims:
        // ContainerChest lays out size/9 rows, so a window whose slot count is not a multiple of
        // nine reports more than it holds, and the overshoot lands on the player's own slots.
        int chestSlots = container.inventorySlots.size() - PLAYER_SLOTS;
        Slot target = nearest(container, chestSlots, lastSlot);
        if (target == null) {
            if (transactions.hasPending()) {
                return; // a click is still in flight, the chest may not be empty yet
            }
            if (silent) {
                closeSilent(player);
            } else if (autoClose.get()) {
                player.closeScreen();
            }
        } else if (silent) {
            // No GUI to look slow behind, so take the lot at once - but not mid-stride, since
            // clicking a window while walking is the tell this mode exists to avoid.
            if (!InvUtil.isPlayerMoving(player) && reserve()) {
                for (int i = 0; i < chestSlots && target != null; i++) {
                    if (!InvUtil.click(player, container, target.slotNumber, 0, 1)) {
                        break;
                    }
                    target = nearest(container, chestSlots, target.slotNumber);
                }
            }
        } else if (reserve() && InvUtil.click(player, container, target.slotNumber, 0, 1)) {
            lastSlot = target.slotNumber;
            Slot next = nearest(container, chestSlots, lastSlot);
            nextAt = System.currentTimeMillis() + InvUtil.moveDelayMs(delay.getLo(), delay.getHi(),
                    container, lastSlot, next == null ? -1 : next.slotNumber);
        }
    }

    private boolean opened() {
        return System.currentTimeMillis() - chestUseAt < OPEN_WITHIN_MS;
    }

    /** One automated action per movement window, and never on the tick after the player acted. */
    private boolean reserve() {
        return ActionGuard.getInstance().tryReserveAfterCleanTick(this);
    }

    private static boolean isChest(MovingObjectPosition hit) {
        World world = Minecraft.getMinecraft().theWorld;
        if (world == null || hit == null || hit.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK) {
            return false;
        }
        Block block = world.getBlockState(hit.getBlockPos()).getBlock();
        return block instanceof BlockChest || block instanceof BlockEnderChest;
    }

    private void closeSilent(EntityPlayerSP player) {
        if (silentId != -1 && player.openContainer.windowId == silentId) {
            InvUtil.closeWindow(player, silentId);
            player.openContainer = player.inventoryContainer;
        }
        silentId = -1;
    }

    /** Closest takeable chest slot to {@code fromSlot}, so the delay tracks how far the hand moves. */
    private static Slot nearest(Container container, int chestSlots, int fromSlot) {
        InventoryTransactions transactions = InventoryTransactions.getInstance();
        Slot from = InvUtil.slotByNumber(container, fromSlot), best = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (int i = 0; i < chestSlots; i++) {
            Slot slot = container.getSlot(i);
            if (!slot.getHasStack() || !transactions.canClick(container, i, 0, 1)
                    || !fits(container, chestSlots, slot.getStack())) {
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

    /** Room left for this stack; without it a full inventory gets clicked forever. */
    private static boolean fits(Container container, int chestSlots, ItemStack stack) {
        for (int i = chestSlots; i < container.inventorySlots.size(); i++) {
            ItemStack in = container.getSlot(i).getStack();
            if (in == null || stack.isStackable() && in.stackSize < in.getMaxStackSize()
                    && in.getItem() == stack.getItem()
                    && (!stack.getHasSubtypes() || in.getMetadata() == stack.getMetadata())
                    && ItemStack.areItemStackTagsEqual(stack, in)) {
                return true;
            }
        }
        return false;
    }
}
