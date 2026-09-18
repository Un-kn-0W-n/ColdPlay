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

public class ChestStealer extends Module {

    private static final String NORMAL = "Normal", HYPIXEL = "Hypixel";
    private static final long SILENT_OPEN_MS = 100L, IDLE_RESCAN_MS = 150L;

    private final ModeSetting mode = add(new ModeSetting("Mode", NORMAL, NORMAL, HYPIXEL).describe("Normal steals from the chest GUI. Hypixel keeps the chest hidden and takes everything in one tick."));
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

    @EventTarget
    public void onUse(EventUse event) {
        MovingObjectPosition hit = event.getTarget();
        World world = Minecraft.getMinecraft().theWorld;
        Block block = world == null || hit == null || hit.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK
                ? null : world.getBlockState(hit.getBlockPos()).getBlock();
        clickedChest = block instanceof BlockChest || block instanceof BlockEnderChest;
    }

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
            closeSilent(player);
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
            realChest = silent || clickedChest;
            clickedChest = false;
            lastSlot = -1;
            nextAt = System.currentTimeMillis() + (silent ? SILENT_OPEN_MS : InvUtil.reactionDelayMs());
        }
        if (!realChest || transactions.isRecovering() || player.inventory.getItemStack() != null
                || System.currentTimeMillis() < nextAt) {
            return;
        }
        int chestSlots = ((ContainerChest) container).getLowerChestInventory().getSizeInventory();
        Slot target = nearest(container, chestSlots, lastSlot);
        if (target != null) {
            if (!InvUtil.isPlayerMoving(player) && ActionGuard.getInstance().tryReserveAfterCleanTick(this)) {
                steal(player, container, chestSlots, target, silent);
            }
        } else if (!transactions.hasPending()) {
            if (silent) {
                closeSilent(player);
            } else if (autoClose.getAsBoolean()) {
                player.closeScreen();
            } else {
                nextAt = System.currentTimeMillis() + IDLE_RESCAN_MS;
            }
        }
    }

    private void steal(EntityPlayerSP player, Container container, int chestSlots, Slot target, boolean silent) {
        PacketLog.getInstance().tagged("ChestStealer", () -> {
            Slot next = target;
            for (int i = 0, limit = silent ? chestSlots : 1; next != null && i < limit; i++) {
                int from = next.slotNumber;
                if (!InvUtil.click(player, container, from, 0, 1)) {
                    return;
                }
                next = nearest(container, chestSlots, from);
                if (!silent) {
                    lastSlot = from;
                    nextAt = System.currentTimeMillis() + InvUtil.moveDelayMs(delay.getLo(), delay.getHi(),
                            container, from, next == null ? -1 : next.slotNumber);
                }
            }
        });
    }

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
