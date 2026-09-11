package coldplay.module.utility;

import coldplay.event.EventTarget;
import coldplay.event.EventUpdate;
import coldplay.module.Category;
import coldplay.module.Module;
import coldplay.setting.BooleanSetting;
import coldplay.setting.CleanerSetting;
import coldplay.setting.HotbarSetting;
import coldplay.setting.RangeSetting;
import coldplay.broker.ActionGuard;
import coldplay.broker.InventoryTransactions;
import coldplay.broker.PacketLog;
import coldplay.broker.SlotGuard;
import coldplay.util.InvUtil;
import coldplay.util.ItemUtil;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.gui.inventory.GuiInventory;
import net.minecraft.inventory.Container;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.item.Item;
import net.minecraft.item.ItemArmor;
import net.minecraft.item.ItemBow;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemSword;
import net.minecraft.item.ItemTool;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/** One vanilla click per delay while the survival inventory is open: armor, then hotbar, then cleaner. */
public class InvManager extends Module {

    private final RangeSetting delay = add(new RangeSetting("Delay", 120.0, 520.0, 50.0, 1000.0, 10.0).describe("Wait between inventory actions (ms): min for adjacent slots, max across the inventory, plus random hesitation."));

    private final BooleanSetting autoArmor = add(new BooleanSetting("AutoArmor", false).describe("Equip the best armour you are carrying."));
    private final BooleanSetting armorInstant = add(new BooleanSetting("ArmorInstant", false).describe("Equip armour pieces about a tick apart instead of waiting the Delay."));

    private final BooleanSetting autoHotbar = add(new BooleanSetting("AutoHotBar", false).describe("Sort your hotbar to the saved layout."));
    private final BooleanSetting hotbarInstant = add(new BooleanSetting("HotbarInstant", false).describe("Swap hotbar slots about a tick apart instead of waiting the Delay."));
    private final HotbarSetting hotbar = add(new HotbarSetting("Layout").describe("Drag items/categories onto hotbar slots; ordered = primary then fallbacks. Right-click an item to exclude it."));

    private final BooleanSetting cleaner = add(new BooleanSetting("Cleaner", false).describe("Drop junk items out of your inventory."));
    private final BooleanSetting cleanerInstant = add(new BooleanSetting("CleanerInstant", false).describe("Throw junk about a tick apart instead of waiting the Delay."));
    private final CleanerSetting cleanerItems = add(new CleanerSetting("Items").describe("Per-item Drop / Keep-one / Ignore list."));

    private final BooleanSetting autoClose = add(new BooleanSetting("Auto Close", false).describe("Close the inventory once jobs are done."));

    private static final int ARMOR = 0, HOTBAR = 1, CLEANER = 2;

    private Container active;
    private int lastSlot = -1;
    private long nextAt;
    private boolean worked;

    public InvManager() {
        super("InvManager", Category.UTILITY, "Automatic inventory management.");
        armorInstant.visibleWhen(autoArmor::get).indent(1).label("Instant Swap");
        hotbarInstant.visibleWhen(autoHotbar::get).indent(1).label("Instant Swap");
        hotbar.visibleWhen(autoHotbar::get).indent(1);
        cleanerInstant.visibleWhen(cleaner::get).indent(1).label("Instant Clean");
        cleanerItems.visibleWhen(cleaner::get).indent(1);
    }

    /** Shared with ChestStealer so one Auto Close toggle covers both screens. */
    public boolean autoCloses() {
        return autoClose.get();
    }

    @Override
    protected void onDisable() {
        active = null;
    }

    // PRE: window clicks must go out before this tick's C03.
    @EventTarget
    public void onUpdate(EventUpdate event) {
        if (!event.isPre()) return;
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP player = mc.thePlayer;
        if (player == null || mc.theWorld == null || !(mc.currentScreen instanceof GuiInventory)
                || player.openContainer != player.inventoryContainer) {
            active = null;
            return;
        }
        Container container = player.openContainer;
        InventoryTransactions transactions = InventoryTransactions.getInstance();
        transactions.begin(container, mc.currentScreen);
        if (active != container) {
            active = container;
            lastSlot = -1;
            worked = false;
            nextAt = System.currentTimeMillis() + InvUtil.reactionDelayMs();
        }
        if (transactions.isRecovering() || player.inventory.getItemStack() != null
                || ActionGuard.getInstance().playerActedLastTick() || InvUtil.isPlayerMoving(player)
                || System.currentTimeMillis() < nextAt) return;

        int[] click = next(container);
        if (click == null) {
            if (worked && autoClose.get() && !transactions.hasPending()) player.closeScreen();
            return;
        }
        if ((click[0] >= 36 || click[2] == 2) && !SlotGuard.getInstance().isFree()) return;
        if (!ActionGuard.getInstance().tryReserve(this)) return;
        PacketLog.getInstance().tagged("InvManager", () -> {
            if (!InvUtil.click(player, container, click[0], click[1], click[2])) return;
            worked = true;
            lastSlot = click[0];
            int[] after = next(container); // click already predicted locally, so this is the real next trip
            boolean instant = click[3] == ARMOR ? armorInstant.get() : click[3] == HOTBAR ? hotbarInstant.get() : cleanerInstant.get();
            nextAt = System.currentTimeMillis() + (instant ? ThreadLocalRandom.current().nextLong(50, 110)
                    : InvUtil.moveDelayMs(delay.getLo(), delay.getHi(), container, lastSlot, after == null ? -1 : after[0]));
        });
    }

    // replans from live slots every tick instead of a precomputed plan - refused clicks just fall through
    private int[] next(Container c) {
        int[] click = autoArmor.get() ? armor(c) : null;
        if (click == null && autoHotbar.get()) click = hotbar(c);
        if (click == null && cleaner.get()) click = junk(c);
        return click;
    }

    private static ItemStack at(Container c, int slot) {
        return c.getSlot(slot).getStack();
    }

    private static int[] click(Container c, int slot, int button, int mode, int feature) {
        return InventoryTransactions.getInstance().canClick(c, slot, button, mode) ? new int[]{slot, button, mode, feature} : null;
    }

    private static int firstEmpty(Container c) {
        for (int slot = 9; slot < 45; slot++) if (at(c, slot) == null) return slot;
        return -1;
    }

    // Better piece in a bag slot -> shift-click it (or hotbar-swap it) in; occupied slot gets shift-clicked out first.
    private int[] armor(Container c) {
        for (int type = 0; type < 4; type++) {
            int source = bestArmor(c, type);
            if (source < 0) continue;
            int dest = 5 + type;
            int[] click = at(c, dest) == null ? click(c, source, 0, 1, ARMOR)
                    : source >= 36 ? click(c, dest, source - 36, 2, ARMOR)
                    : firstEmpty(c) >= 0 ? click(c, dest, 0, 1, ARMOR) : null;
            if (click != null) return click;
        }
        return null;
    }

    private int bestArmor(Container c, int type) {
        ItemStack equipped = at(c, 5 + type);
        if (equipped != null && !(equipped.getItem() instanceof ItemArmor)) return -1;
        double best = scoreArmor(equipped);
        int bestSlot = -1;
        for (int slot = 9; slot < 45; slot++) {
            ItemStack stack = at(c, slot);
            if (stack == null || !(stack.getItem() instanceof ItemArmor) || ((ItemArmor) stack.getItem()).armorType != type) continue;
            double score = scoreArmor(stack);
            if (score > best) {
                best = score;
                bestSlot = slot;
            }
        }
        return bestSlot;
    }

    // greedy per hotbar slot, earlier slots claim first; the DP assignment was not worth its 60 lines
    private int[] hotbar(Container c) {
        boolean[] claimed = new boolean[45];
        List<List<String>> layout = hotbar.getSlots();
        for (int index = 0; index < 9; index++) {
            int want = -1, wantRank = Integer.MAX_VALUE;
            double wantScore = 0;
            for (int slot = 9; slot < 45; slot++) {
                ItemStack stack = at(c, slot);
                if (stack == null || claimed[slot]) continue;
                int rank = rankOf(layout.get(index), stack);
                if (rank == Integer.MAX_VALUE) continue;
                double score = scoreItem(stack);
                if (rank < wantRank || rank == wantRank && (score > wantScore || score == wantScore && slot == 36 + index)) {
                    want = slot;
                    wantRank = rank;
                    wantScore = score;
                }
            }
            if (want < 0) continue;
            claimed[want] = true;
            if (want == 36 + index) continue;
            int[] click = click(c, want, index, 2, HOTBAR);
            if (click != null) return click;
        }
        return null;
    }

    private int rankOf(List<String> prefs, ItemStack stack) {
        for (int rank = 0; rank < prefs.size(); rank++) {
            String key = prefs.get(rank);
            if (HotbarSetting.isCategoryKey(key)) {
                if (hotbar.categoryIncludes(hotbar.categoryIndexOf(key), stack)) return rank;
            } else if (!hotbar.isExcluded(key)) {
                HotbarSetting.Entry entry = hotbar.entryForKey(key);
                if (entry != null && stack.getItem() == entry.getIcon().getItem()
                        && (!entry.isSubtypes() || stack.getMetadata() == entry.getIcon().getMetadata())) return rank;
            }
        }
        return Integer.MAX_VALUE;
    }

    private int[] junk(Container c) {
        for (int slot = 9; slot < 36; slot++) {
            ItemStack stack = at(c, slot);
            if (stack == null) continue;
            // armor waiting to be equipped is not junk even if its category says drop
            if (autoArmor.get() && stack.getItem() instanceof ItemArmor
                    && bestArmor(c, ((ItemArmor) stack.getItem()).armorType) == slot) continue;
            CleanerSetting.CleanerMode mode = cleanerItems.effectiveMode(stack);
            boolean drop = mode == CleanerSetting.CleanerMode.DROP;
            for (int other = 9; mode == CleanerSetting.CleanerMode.KEEP_ONE && other < 36 && !drop; other++) {
                ItemStack twin = at(c, other);
                drop = twin != null && other != slot && HotbarSetting.keyOf(stack).equals(HotbarSetting.keyOf(twin))
                        && (twin.stackSize > stack.stackSize || twin.stackSize == stack.stackSize && other < slot);
            }
            int[] click = drop ? click(c, slot, 1, 4, CLEANER) : null;
            if (click != null) return click;
        }
        return null;
    }

    private static double scoreArmor(ItemStack stack) {
        if (stack == null || !(stack.getItem() instanceof ItemArmor)) return -1.0;
        ItemArmor armor = (ItemArmor) stack.getItem();
        // whole-set reduction as the material tier: per-piece values tie (gold/chain/iron helmets are all 2)
        int material = 0;
        for (int t = 0; t < 4; t++) material += armor.getArmorMaterial().getDamageReductionAmount(t);
        return material * 1000.0 + EnchantmentHelper.getEnchantmentLevel(Enchantment.protection.effectId, stack);
    }

    private static double scoreItem(ItemStack stack) {
        Item item = stack.getItem();
        if (item instanceof ItemSword) return ItemUtil.meleeDamage(stack);
        if (item instanceof ItemBow) {
            return EnchantmentHelper.getEnchantmentLevel(Enchantment.power.effectId, stack) * 100.0
                    + EnchantmentHelper.getEnchantmentLevel(Enchantment.punch.effectId, stack) * 10.0
                    + EnchantmentHelper.getEnchantmentLevel(Enchantment.flame.effectId, stack) * 5.0
                    + EnchantmentHelper.getEnchantmentLevel(Enchantment.infinity.effectId, stack) * 3.0;
        }
        if (item instanceof ItemTool) {
            ItemTool tool = (ItemTool) item;
            float speed = tool.getToolMaterial().getEfficiencyOnProperMaterial();
            if (speed > 1.0F) speed += ItemUtil.efficiencyBonus(stack);
            // harvest tier wins first so a diamond pick outranks a faster gold one that can't mine high-tier blocks
            return tool.getToolMaterial().getHarvestLevel() * 1000.0 + speed;
        }
        return stack.stackSize / 100.0;   // stackables rank by count; < 1.0 so real gear always outranks
    }
}
