package coldplay.module.utility;

import coldplay.broker.ActionGuard;
import coldplay.broker.InventoryTransactions;
import coldplay.broker.SlotGuard;
import coldplay.event.EventTarget;
import coldplay.event.EventUpdate;
import coldplay.module.Category;
import coldplay.module.Module;
import coldplay.setting.BooleanSetting;
import coldplay.setting.CleanerSetting;
import coldplay.setting.HotbarSetting;
import coldplay.setting.RangeSetting;
import coldplay.util.InvUtil;
import coldplay.util.ItemUtil;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.inventory.GuiInventory;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.inventory.Container;
import net.minecraft.item.Item;
import net.minecraft.item.ItemArmor;
import net.minecraft.item.ItemBow;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemSword;
import net.minecraft.item.ItemTool;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/** One inventory click per delay while the survival inventory is open: armor, then hotbar, then cleaner. */
public class InvManager extends Module {

    private final RangeSetting delay = add(new RangeSetting("Delay", 120.0, 520.0, 50.0, 1000.0, 10.0)
            .describe("Wait between inventory actions (ms): min for adjacent slots, max across the inventory, plus random hesitation."));

    private final BooleanSetting autoArmor = add(new BooleanSetting("AutoArmor", false).describe("Equip the best armour you are carrying."));
    private final BooleanSetting armorInstant = add(new BooleanSetting("ArmorInstant", false).describe("Equip armour pieces about a tick apart instead of waiting the Delay."));

    private final BooleanSetting autoHotbar = add(new BooleanSetting("AutoHotBar", false).describe("Sort your hotbar to the saved layout."));
    private final BooleanSetting hotbarInstant = add(new BooleanSetting("HotbarInstant", false).describe("Swap hotbar slots about a tick apart instead of waiting the Delay."));
    private final HotbarSetting hotbar = add(new HotbarSetting("Layout").describe("Drag items/categories onto hotbar slots; ordered = primary then fallbacks. Right-click an item to exclude it."));

    private final BooleanSetting cleaner = add(new BooleanSetting("Cleaner", false).describe("Drop junk items out of your inventory."));
    private final BooleanSetting cleanerInstant = add(new BooleanSetting("CleanerInstant", false).describe("Throw junk about a tick apart instead of waiting the Delay."));
    private final CleanerSetting cleanerItems = add(new CleanerSetting("Items").describe("Per-item Drop / Keep-one / Ignore list."));

    private final BooleanSetting autoClose = add(new BooleanSetting("Auto Close", false).describe("Close the inventory once jobs are done."));

    // plan[3]: which feature produced the click, so it picks the matching Instant toggle
    private static final int ARMOR = 0, HOTBAR = 1, CLEANER = 2;
    private static final long MANUAL_HOLD_MS = 800L; // hands off this long after a click of your own

    private GuiScreen session;
    private boolean hotbarFree; // another module is holding a hotbar slot, leave 36-44 alone
    private boolean handsOn;    // the player was working the inventory last tick
    private boolean worked;
    private long nextAt;

    public InvManager() {
        super("InvManager", Category.UTILITY, "Automatic inventory management.");
        armorInstant.visibleWhen(autoArmor::get).indent(1).label("Instant Swap");
        hotbarInstant.visibleWhen(autoHotbar::get).indent(1).label("Instant Swap");
        hotbar.visibleWhen(autoHotbar::get).indent(1);
        cleanerInstant.visibleWhen(cleaner::get).indent(1).label("Instant Clean");
        cleanerItems.visibleWhen(cleaner::get).indent(1);
    }

    @Override
    protected void onDisable() {
        session = null;
        handsOn = false;
    }

    @EventTarget
    public void onUpdate(EventUpdate event) {
        if (!event.isPre()) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP player = mc.thePlayer;
        if (player == null || mc.theWorld == null || !(mc.currentScreen instanceof GuiInventory)
                || player.openContainer != player.inventoryContainer) {
            session = null;
            return;
        }
        Container c = player.openContainer;
        InventoryTransactions transactions = InventoryTransactions.getInstance();
        transactions.begin(c, mc.currentScreen);
        if (session != mc.currentScreen) {
            session = mc.currentScreen;
            handsOn = false;
            worked = false;
            nextAt = System.currentTimeMillis() + InvUtil.reactionDelayMs();
        }
        if (transactions.isRecovering()) {
            return;
        }
        // The player is working the inventory themselves: something on the cursor, a click they
        // just made, or a craft sitting in the 2x2 grid. The tick they let go still waits out one
        // reaction, so the module never fires the instant their hands come off.
        boolean busy = player.inventory.getItemStack() != null || transactions.manualWithin(MANUAL_HOLD_MS) || crafting(c);
        if (busy || handsOn) {
            handsOn = busy;
            nextAt = System.currentTimeMillis() + InvUtil.reactionDelayMs();
            return;
        }
        if (System.currentTimeMillis() < nextAt) {
            return;
        }

        hotbarFree = SlotGuard.getInstance().isFree();
        int[] plan = plan(c);
        if (plan == null) {
            if (worked && autoClose.get() && !transactions.hasPending()) {
                player.closeScreen();
            }
            return;
        }
        if (!ActionGuard.getInstance().tryReserve(this)) {
            return; // another module owns this movement window
        }
        if (!InvUtil.click(player, c, plan[0], plan[1], plan[2])) {
            return; // refused (InvMove, resync); replanned from live slots next tick
        }
        worked = true;
        boolean instant = plan[3] == ARMOR ? armorInstant.get() : plan[3] == HOTBAR ? hotbarInstant.get() : cleanerInstant.get();
        int[] next = plan(c); // the click already applied locally
        nextAt = System.currentTimeMillis() + (instant ? ThreadLocalRandom.current().nextLong(40, 160)
                : InvUtil.moveDelayMs(delay.getLo(), delay.getHi(), c, plan[0], next == null ? -1 : next[0]));
    }

    private static boolean crafting(Container c) {
        for (int slot = 0; slot < 5; slot++) {
            if (at(c, slot) != null) {
                return true;
            }
        }
        return false;
    }

    /** Next click to send as {slot, button, mode, feature}, or null when there is nothing to do. */
    private int[] plan(Container c) {
        int[] click = autoArmor.get() ? armor(c) : null;
        if (click == null && autoHotbar.get() && hotbarFree) {
            click = hotbar(c);
        }
        if (click == null && cleaner.get()) {
            click = junk(c);
        }
        return click;
    }

    private static ItemStack at(Container c, int slot) {
        return c.getSlot(slot).getStack();
    }

    private static int[] click(Container c, int slot, int button, int mode, int feature) {
        return InventoryTransactions.getInstance().canClick(c, slot, button, mode)
                ? new int[]{slot, button, mode, feature} : null;
    }

    private static int firstEmpty(Container c) {
        for (int slot = 9; slot < 45; slot++) {
            if (at(c, slot) == null) {
                return slot;
            }
        }
        return -1;
    }

    // Armor slots 5-8 run helmet to boots, the same order as ItemArmor.armorType.
    private int[] armor(Container c) {
        for (int type = 0; type < 4; type++) {
            int dest = 5 + type;
            int source = bestArmor(c, type);
            if (source < 0) {
                continue;
            }
            int[] click = null;
            if (at(c, dest) == null) {
                // straight quick-move into the empty armor slot
                if (source < 36 || hotbarFree) {
                    click = click(c, source, 0, 1, ARMOR);
                }
            } else if (source >= 36) {
                // swap the worn piece with the hotbar slot holding the better one
                if (hotbarFree) {
                    click = click(c, dest, source - 36, 2, ARMOR);
                }
            } else {
                // take the worn piece off first; it quick-moves into 9-45, hotbar included
                int empty = firstEmpty(c);
                if (empty >= 0 && (empty < 36 || hotbarFree)) {
                    click = click(c, dest, 0, 1, ARMOR);
                }
            }
            if (click != null) {
                return click;
            }
        }
        return null;
    }

    /** Inventory slot holding a strictly better piece of this armor type, or -1. */
    private int bestArmor(Container c, int type) {
        ItemStack equipped = at(c, 5 + type);
        if (equipped != null && !(equipped.getItem() instanceof ItemArmor)) {
            return -1; // something else is in the slot, leave it alone
        }
        double best = scoreArmor(equipped);
        int bestSlot = -1;
        for (int slot = 9; slot < 45; slot++) {
            ItemStack stack = at(c, slot);
            // the armor slot's own isItemValid is what vanilla asks when you shift-click a piece
            if (stack == null || !c.getSlot(5 + type).isItemValid(stack)) {
                continue;
            }
            double score = scoreArmor(stack);
            if (score > best) {
                best = score;
                bestSlot = slot;
            }
        }
        return bestSlot;
    }

    // Greedy; earlier hotbar slots claim their best match first.
    private int[] hotbar(Container c) {
        boolean[] claimed = new boolean[45];
        List<List<String>> layout = hotbar.getSlots();
        for (int index = 0; index < 9; index++) {
            int want = -1, wantRank = Integer.MAX_VALUE;
            double wantScore = 0.0;
            for (int slot = 9; slot < 45; slot++) {
                ItemStack stack = at(c, slot);
                if (stack == null || claimed[slot]) {
                    continue;
                }
                int rank = rankOf(layout.get(index), stack);
                if (rank == Integer.MAX_VALUE) {
                    continue;
                }
                double score = scoreItem(stack);
                // ties go to the item already sitting in this hotbar slot, so it stays put
                if (rank < wantRank || rank == wantRank
                        && (score > wantScore || score == wantScore && slot == 36 + index)) {
                    want = slot;
                    wantRank = rank;
                    wantScore = score;
                }
            }
            if (want < 0) {
                continue;
            }
            claimed[want] = true;
            if (want == 36 + index) {
                continue;
            }
            int[] click = click(c, want, index, 2, HOTBAR);
            if (click != null) {
                return click;
            }
        }
        return null;
    }

    /** Position of this stack in the slot's preference list; MAX_VALUE when it is not wanted there. */
    private int rankOf(List<String> prefs, ItemStack stack) {
        for (int rank = 0; rank < prefs.size(); rank++) {
            String key = prefs.get(rank);
            if (HotbarSetting.isCategoryKey(key)) {
                if (hotbar.categoryIncludes(hotbar.categoryIndexOf(key), stack)) {
                    return rank;
                }
            } else if (!hotbar.isExcluded(key)) {
                HotbarSetting.Entry entry = hotbar.entryForKey(key);
                if (entry != null && stack.getItem() == entry.getIcon().getItem()
                        && (!entry.isSubtypes() || stack.getMetadata() == entry.getIcon().getMetadata())) {
                    return rank;
                }
            }
        }
        return Integer.MAX_VALUE;
    }

    private int[] junk(Container c) {
        for (int slot = 9; slot < 36; slot++) {
            ItemStack stack = at(c, slot);
            if (stack == null) {
                continue;
            }
            // armor waiting to be equipped is not junk even if its category says drop
            if (autoArmor.get() && stack.getItem() instanceof ItemArmor
                    && bestArmor(c, ((ItemArmor) stack.getItem()).armorType) == slot) {
                continue;
            }
            CleanerSetting.CleanerMode mode = cleanerItems.effectiveMode(stack);
            boolean drop = mode == CleanerSetting.CleanerMode.DROP
                    || mode == CleanerSetting.CleanerMode.KEEP_ONE && isSpare(c, slot, stack);
            int[] click = drop ? click(c, slot, 1, 4, CLEANER) : null;
            if (click != null) {
                return click;
            }
        }
        return null;
    }

    /** Keep-one: another stack of the same item is the one worth keeping, so this one goes. */
    private static boolean isSpare(Container c, int slot, ItemStack stack) {
        String key = HotbarSetting.keyOf(stack);
        for (int other = 9; key != null && other < 36; other++) {
            ItemStack twin = at(c, other);
            if (other == slot || twin == null || !key.equals(HotbarSetting.keyOf(twin))) {
                continue;
            }
            if (twin.stackSize > stack.stackSize || twin.stackSize == stack.stackSize && other < slot) {
                return true;
            }
        }
        return false;
    }

    private static double scoreArmor(ItemStack stack) {
        if (stack == null || !(stack.getItem() instanceof ItemArmor)) {
            return -1.0;
        }
        ItemArmor armor = (ItemArmor) stack.getItem();
        // per-piece reduction ties (gold, chain and iron helmets are all 2), so sum the set
        int material = 0;
        for (int type = 0; type < 4; type++) {
            material += armor.getArmorMaterial().getDamageReductionAmount(type);
        }
        return material * 1000.0 + EnchantmentHelper.getEnchantmentLevel(Enchantment.protection.effectId, stack);
    }

    private static double scoreItem(ItemStack stack) {
        Item item = stack.getItem();
        if (item instanceof ItemSword) {
            return ItemUtil.meleeDamage(stack);
        }
        if (item instanceof ItemBow) {
            return EnchantmentHelper.getEnchantmentLevel(Enchantment.power.effectId, stack) * 100.0
                    + EnchantmentHelper.getEnchantmentLevel(Enchantment.punch.effectId, stack) * 10.0
                    + EnchantmentHelper.getEnchantmentLevel(Enchantment.flame.effectId, stack) * 5.0
                    + EnchantmentHelper.getEnchantmentLevel(Enchantment.infinity.effectId, stack) * 3.0;
        }
        if (item instanceof ItemTool) {
            ItemTool tool = (ItemTool) item;
            float speed = tool.getToolMaterial().getEfficiencyOnProperMaterial();
            if (speed > 1.0F) {
                speed += ItemUtil.efficiencyBonus(stack);
            }
            // harvest tier first; a gold pick is faster but cannot mine high-tier blocks
            return tool.getToolMaterial().getHarvestLevel() * 1000.0 + speed;
        }
        return stack.stackSize / 100.0; // < 1.0 so gear always outranks stackables
    }
}
