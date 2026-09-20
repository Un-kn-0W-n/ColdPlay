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

public class ChestStealer extends Module {

    private static final String NORMAL = "Normal";
    private static final String HYPIXEL = "Hypixel";
    private static final long OPEN_TIMEOUT_MS = 3000L;
    private static final long SILENT_START_DELAY_MS = 100L;
    private static final long SILENT_TIMEOUT_MS = 5000L;
    private static final int PLAYER_SLOTS = 36;

    private final ModeSetting mode = add(new ModeSetting("Mode", NORMAL, NORMAL, HYPIXEL)
            .describe("Normal steals from the chest GUI. Hypixel keeps the chest hidden and takes everything in one tick."));
    private final RangeSetting delay = add(new RangeSetting("Delay", 120.0, 520.0, 50.0, 1000.0, 5.0)
            .describe("Wait between item moves (ms): min for adjacent slots, max across the chest, plus random hesitation."));
    private final BooleanSetting autoClose = add(new BooleanSetting("Auto Close", false)
            .describe("Close the chest once there is nothing left worth taking."));

    private Container activeContainer;
    private boolean openedByPlayer;
    private int silentWindowId = -1;
    private int lastSlot = -1;
    private long lastChestUseAt;
    private long silentOpenedAt;
    private long nextMoveAt;

    public ChestStealer() {
        super("ChestStealer", Category.UTILITY, "Auto-empties an open chest into your inventory.");
        addAutoOff();
        delay.visibleWhen(() -> !isHypixel()).indent(1);
        autoClose.visibleWhen(() -> !isHypixel()).indent(1);
    }

    @Override
    protected void onDisable() {
        EntityPlayerSP player = Minecraft.getMinecraft().thePlayer;
        if (player != null) {
            closeSilentWindow(player);
        }
        silentWindowId = -1;
        silentOpenedAt = 0L;
        activeContainer = null;
        openedByPlayer = false;
        lastSlot = -1;
        lastChestUseAt = 0L;
        nextMoveAt = 0L;
    }

    @EventTarget
    public void onUse(EventUse event) {
        lastChestUseAt = isChest(event.getTarget()) ? System.currentTimeMillis() : 0L;
    }

    @EventTarget
    public void onOpenWindow(EventOpenWindow event) {
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP player = mc.thePlayer;
        String guiId = event.getGuiId();

        if (!isHypixel() || player == null || mc.currentScreen != null || !usedChestRecently()
                || event.getWindowId() == 0 || event.getSlotCount() <= 0
                || !("minecraft:chest".equals(guiId) || "minecraft:container".equals(guiId))) {
            return;
        }

        ContainerChest container = new ContainerChest(player.inventory,
                new InventoryBasic(event.getTitle(), event.getSlotCount()), player);
        container.windowId = event.getWindowId();
        player.openContainer = container;
        silentWindowId = container.windowId;
        silentOpenedAt = System.currentTimeMillis();
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
            silentWindowId = -1;
            activeContainer = null;
            return;
        }

        if (silentWindowId != -1 && (player.openContainer.windowId != silentWindowId
                || mc.currentScreen != null
                || System.currentTimeMillis() - silentOpenedAt > SILENT_TIMEOUT_MS)) {
            closeSilentWindow(player);
        }

        boolean silent = silentWindowId != -1;
        if ((!silent && !(mc.currentScreen instanceof GuiChest))
                || !(player.openContainer instanceof ContainerChest)) {
            activeContainer = null;
            return;
        }

        Container container = player.openContainer;
        InventoryTransactions transactions = InventoryTransactions.getInstance();
        transactions.begin(container, mc.currentScreen);

        if (activeContainer != container) {
            activeContainer = container;
            openedByPlayer = silent || usedChestRecently();
            lastChestUseAt = 0L;
            lastSlot = -1;
            nextMoveAt = System.currentTimeMillis()
                    + (silent ? SILENT_START_DELAY_MS : InvUtil.reactionDelayMs());
        }

        if (!openedByPlayer || transactions.isRecovering() || player.inventory.getItemStack() != null
                || System.currentTimeMillis() < nextMoveAt) {
            return;
        }

        int chestSlots = container.inventorySlots.size() - PLAYER_SLOTS;
        Slot target = findNearestSlot(container, chestSlots, lastSlot);
        if (target == null) {
            if (!transactions.hasPending()) {
                if (silent) {
                    closeSilentWindow(player);
                } else if (autoClose.get()) {
                    player.closeScreen();
                }
            }
            return;
        }

        if (silent) {
            moveAll(player, container, chestSlots, target);
        } else {
            moveOne(player, container, chestSlots, target);
        }
    }

    private void moveAll(EntityPlayerSP player, Container container, int chestSlots, Slot target) {
        if (InvUtil.isPlayerMoving(player) || !ActionGuard.getInstance().tryReserve(this)) {
            return;
        }

        for (int moved = 0; moved < chestSlots && target != null; moved++) {
            if (!InvUtil.click(player, container, target.slotNumber, 0, 1)) {
                break;
            }
            target = findNearestSlot(container, chestSlots, target.slotNumber);
        }
    }

    private void moveOne(EntityPlayerSP player, Container container, int chestSlots, Slot target) {
        if (!ActionGuard.getInstance().tryReserve(this)
                || !InvUtil.click(player, container, target.slotNumber, 0, 1)) {
            return;
        }

        lastSlot = target.slotNumber;
        Slot next = findNearestSlot(container, chestSlots, lastSlot);
        nextMoveAt = System.currentTimeMillis() + InvUtil.moveDelayMs(delay.getLo(), delay.getHi(),
                container, lastSlot, next == null ? -1 : next.slotNumber);
    }

    private void closeSilentWindow(EntityPlayerSP player) {
        if (silentWindowId != -1 && player.openContainer.windowId == silentWindowId) {
            InvUtil.closeWindow(player, silentWindowId);
            player.openContainer = player.inventoryContainer;
        }
        silentWindowId = -1;
        silentOpenedAt = 0L;
    }

    private boolean isHypixel() {
        return HYPIXEL.equals(mode.get());
    }

    private boolean usedChestRecently() {
        return System.currentTimeMillis() - lastChestUseAt < OPEN_TIMEOUT_MS;
    }

    private static boolean isChest(MovingObjectPosition hit) {
        World world = Minecraft.getMinecraft().theWorld;
        if (world == null || hit == null || hit.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK) {
            return false;
        }
        Block block = world.getBlockState(hit.getBlockPos()).getBlock();
        return block instanceof BlockChest || block instanceof BlockEnderChest;
    }

    private static Slot findNearestSlot(Container container, int chestSlots, int fromSlot) {
        InventoryTransactions transactions = InventoryTransactions.getInstance();
        Slot origin = InvUtil.slotByNumber(container, fromSlot);
        Slot nearest = null;
        double nearestDistance = Double.POSITIVE_INFINITY;
        boolean hasSpace = hasEmptyPlayerSlot(container, chestSlots);

        for (int i = 0; i < chestSlots; i++) {
            Slot slot = container.getSlot(i);
            if (!slot.getHasStack() || !transactions.canClick(container, i, 0, 1)
                    || (!hasSpace && !canMergeIntoPlayerInventory(container, chestSlots, slot.getStack()))) {
                continue;
            }

            double distance = InvUtil.slotDistance(origin, slot);
            if (distance < nearestDistance) {
                nearest = slot;
                nearestDistance = distance;
            }
        }
        return nearest;
    }

    private static boolean hasEmptyPlayerSlot(Container container, int chestSlots) {
        for (int i = chestSlots; i < container.inventorySlots.size(); i++) {
            if (container.getSlot(i).getStack() == null) {
                return true;
            }
        }
        return false;
    }

    private static boolean canMergeIntoPlayerInventory(Container container, int chestSlots, ItemStack stack) {
        if (!stack.isStackable()) {
            return false;
        }

        for (int i = chestSlots; i < container.inventorySlots.size(); i++) {
            ItemStack existing = container.getSlot(i).getStack();
            if (existing != null && existing.stackSize < existing.getMaxStackSize()
                    && existing.getItem() == stack.getItem()
                    && (!stack.getHasSubtypes() || existing.getMetadata() == stack.getMetadata())
                    && ItemStack.areItemStackTagsEqual(stack, existing)) {
                return true;
            }
        }
        return false;
    }
}
