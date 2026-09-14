package coldplay.util;

import com.google.common.base.Predicate;
import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.client.C0DPacketCloseWindow;

import java.util.concurrent.ThreadLocalRandom;

/** Shared inventory clicks, timing and hotbar lookups. */
public final class InvUtil {
    private InvUtil() { }

    private static final int HOTBAR_SIZE = 9;
    private static final double FULL_ROW_PX = 144.0;
    private static final double MIN_RAMP_MS = 180.0;
    private static final double JITTER_MS = 18.0;
    private static final double HESITATE_CHANCE = 0.10;
    private static final double HESITATE_MS = 260.0;

    public static boolean isPlayerMoving(final EntityPlayerSP p) {
        final double dx = p.posX - p.prevPosX;
        final double dz = p.posZ - p.prevPosZ;
        return p.isSprinting()
                || p.isSneaking()
                || p.movementInput.moveForward != 0.0F
                || p.movementInput.moveStrafe != 0.0F
                || p.movementInput.jump
                || dx * dx + dz * dz > 9.0E-4D; // vanilla's position-send threshold
    }

    public static int findHotbarSlot(final EntityPlayerSP player, final Predicate<ItemStack> predicate) {
        if (player == null || predicate == null) {
            return -1;
        }
        for (int slot = 0; slot < HOTBAR_SIZE; slot++) {
            if (predicate.apply(player.inventory.mainInventory[slot])) {
                return slot;
            }
        }
        return -1;
    }

    // Full cubes take precedence because placement modules rely on their geometry.
    public static int bestHotbarBlockSlot(final EntityPlayerSP player) {
        int best = -1, bestCount = -1;
        int fallback = -1, fallbackCount = -1;
        for (int i = 0; i < HOTBAR_SIZE; i++) {
            final ItemStack stack = player.inventory.mainInventory[i];
            if (stack == null || !(stack.getItem() instanceof ItemBlock)) {
                continue;
            }
            final Block block = Block.getBlockFromItem(stack.getItem());
            if (block != null && block.isFullCube()) {
                if (stack.stackSize > bestCount) {
                    bestCount = stack.stackSize;
                    best = i;
                }
            } else if (stack.stackSize > fallbackCount) {
                fallbackCount = stack.stackSize;
                fallback = i;
            }
        }
        return best != -1 ? best : fallback;
    }

    public static boolean click(EntityPlayerSP player, Container container, int slot, int button, int mode) {
        Minecraft mc = Minecraft.getMinecraft();
        if (player == null || mc.playerController == null || container == null || player.openContainer != container) {
            return false;
        }
        if (mode == 2 && (button < 0 || button >= HOTBAR_SIZE)) {
            return false;
        }
        if (mode == 4 && (slot < 9 || slot > 35 || player.inventory.getItemStack() != null)) {
            return false;
        }
        return mc.playerController.automatedWindowClick(container.windowId, slot, button, mode, player);
    }

    /** Tells the server a window closed without touching the current screen. */
    public static void closeWindow(EntityPlayerSP player, int windowId) {
        player.sendQueue.addToSendQueue(new C0DPacketCloseWindow(windowId));
    }

    public static Slot slotByNumber(final Container c, final int slotNumber) {
        return c != null && slotNumber >= 0 && slotNumber < c.inventorySlots.size() ? c.getSlot(slotNumber) : null;
    }

    public static double slotDistance(final Slot a, final Slot b) {
        if (a == null || b == null) {
            return 0.0;
        }
        final double dx = a.xDisplayPosition - b.xDisplayPosition;
        final double dy = a.yDisplayPosition - b.yDisplayPosition;
        return Math.sqrt(dx * dx + dy * dy);
    }

    // Delay scales with the distance of the upcoming move.
    public static long moveDelayMs(final double lo, final double hi, final Container c, final int from, final int to) {
        return moveDelayMs(lo, hi, slotDistance(slotByNumber(c, from), slotByNumber(c, to)));
    }

    static long moveDelayMs(final double lo, final double hi, final double distancePx) {
        final ThreadLocalRandom r = ThreadLocalRandom.current();
        final double ramp = Math.max(hi - lo, MIN_RAMP_MS);
        final double frac = Math.min(1.0, distancePx / FULL_ROW_PX);
        final double jitter = (r.nextDouble() - 0.5) * 2.0 * JITTER_MS;
        final long wait = (long) Math.max(lo, lo + frac * ramp + jitter);
        return r.nextDouble() < HESITATE_CHANCE ? wait + (long) (r.nextDouble() * HESITATE_MS) : wait;
    }

    public static long reactionDelayMs() {
        return (long) ThreadLocalRandom.current().nextDouble(300.0, 600.0);
    }
}
