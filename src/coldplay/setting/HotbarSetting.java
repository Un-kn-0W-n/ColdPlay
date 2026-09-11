package coldplay.setting;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemArmor;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemBow;
import net.minecraft.item.ItemEgg;
import net.minecraft.item.ItemEnderPearl;
import net.minecraft.item.ItemExpBottle;
import net.minecraft.item.ItemFireball;
import net.minecraft.item.ItemFishingRod;
import net.minecraft.item.ItemFood;
import net.minecraft.item.ItemHoe;
import net.minecraft.item.ItemPotion;
import net.minecraft.item.ItemShears;
import net.minecraft.item.ItemSnowball;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemSword;
import net.minecraft.item.ItemTool;
import net.minecraft.util.ResourceLocation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Nine slot preference lists: first key is primary, later keys are fallbacks. Keys identify items
 * ("registry:meta") or categories ("@cat:Name"). Persists {slots, excluded}; excluded items no longer
 * match their categories. Registry entries are built lazily and shared with CleanerSetting.
 */
public class HotbarSetting extends AbstractItemPickerSetting {

    public static final int SLOTS = 9;

    // bucket ids -- also the index into `categories`, so bucket(stack) lands an item in its category
    private static final int CAT_SWORD = 0, CAT_BOW = 1, CAT_ARMOR = 2, CAT_TOOLS = 3,
            CAT_FOOD = 4, CAT_POTION = 5, CAT_THROW = 6, CAT_BLOCKS = 7, CAT_OTHER = 8;

    // one item variant in a category grid. key = "registryName:meta"; subtypes carries whether meta
    // is a real variant id (apple type, wool color -> matters) or just durability (tools -> ignore it)
    public static final class Entry {
        private final String key;
        private final ItemStack icon;
        private final boolean subtypes;

        Entry(final String key, final ItemStack icon, final boolean subtypes) {
            this.key = key;
            this.icon = icon;
            this.subtypes = subtypes;
        }

        public String getKey() { return key; }
        public ItemStack getIcon() { return icon; }
        public boolean isSubtypes() { return subtypes; }
        public String displayName() {
            try { return icon.getDisplayName(); } catch (final Throwable t) { return key; }
        }
    }

    public static final class Category {
        private final String name;
        private final ItemStack icon;
        private final List<Entry> entries = new ArrayList<>();

        Category(final String name, final ItemStack icon) {
            this.name = name;
            this.icon = icon;
        }

        public String getName() { return name; }
        public ItemStack getIcon() { return icon; }
        public List<Entry> getEntries() { return entries; }
    }

    private final List<List<String>> slots = new ArrayList<>();
    private final Set<String> excluded = new HashSet<>();

    private static final String CAT_PREFIX = "@cat:";

    private static final List<Category> CATEGORIES = buildCategories();
    private static final Map<String, Entry> BY_KEY = new HashMap<>();
    // Preserve metadata only for items whose subtype enumeration produced palette entries.
    // Collapsed items must map to :0 so inventory stacks match their selectable palette key.
    private static final Set<Item> META_KEYED = new HashSet<>();
    private static boolean BUILT;        // set at the START of ensureBuilt (re-entrancy guard)
    private static boolean PALETTE_READY; // set at the END -- keyOf only trusts META_KEYED once this is true

    public HotbarSetting(final String name) {
        super(name);
        initSlots();
    }

    private void initSlots() {
        for (int i = 0; i < SLOTS; i++) slots.add(new ArrayList<String>());
    }

    private static List<Category> buildCategories() {
        final List<Category> c = new ArrayList<>();
        c.add(new Category("Sword", new ItemStack(Items.diamond_sword)));
        c.add(new Category("Bow", new ItemStack(Items.bow)));
        c.add(new Category("Armor", new ItemStack(Items.diamond_chestplate)));
        c.add(new Category("Tools", new ItemStack(Items.diamond_pickaxe)));
        c.add(new Category("Food", new ItemStack(Items.cooked_beef)));
        c.add(new Category("Potion", new ItemStack(Items.potionitem)));
        c.add(new Category("Throwables", new ItemStack(Items.ender_pearl)));
        c.add(new Category("Blocks", new ItemStack(Blocks.stone)));
        c.add(new Category("Other", new ItemStack(Items.nether_star)));
        return c;
    }

    public static int bucket(final ItemStack stack) {
        final Item it = stack.getItem();
        if (it instanceof ItemSword) return CAT_SWORD;
        if (it instanceof ItemBow) return CAT_BOW;
        if (it instanceof ItemArmor) return CAT_ARMOR;
        if (it instanceof ItemTool || it instanceof ItemShears || it instanceof ItemHoe || it instanceof ItemFishingRod) return CAT_TOOLS;
        if (it instanceof ItemFood) return CAT_FOOD;
        if (it instanceof ItemPotion) return CAT_POTION;
        if (it instanceof ItemEnderPearl || it instanceof ItemSnowball || it instanceof ItemEgg
                || it instanceof ItemFireball || it instanceof ItemExpBottle) return CAT_THROW;
        if (it instanceof ItemBlock) return CAT_BLOCKS;
        return CAT_OTHER;
    }

    private static void ensureBuilt() {
        if (BUILT) return;
        BUILT = true;
        for (final Item item : Item.itemRegistry) {
            if (item == null) continue;
            final List<ItemStack> variants = new ArrayList<>();
            if (item.getHasSubtypes()) {
                try { item.getSubItems(item, CreativeTabs.tabAllSearch, variants); }
                catch (final Throwable ignored) { variants.clear(); }
            }
            // meta is identity only when enumeration actually produced variants -- a getSubItems that
            // returned empty (or threw) collapses to the single fallback below, so meta must be ignored
            final boolean metaKeyed = item.getHasSubtypes() && !variants.isEmpty();
            if (metaKeyed) META_KEYED.add(item);
            if (variants.isEmpty()) variants.add(new ItemStack(item));
            for (final ItemStack stack : variants) {
                if (stack == null || stack.getItem() == null) continue;
                final String key = keyOf(stack);
                if (key == null || BY_KEY.containsKey(key)) continue;
                final Entry e = new Entry(key, stack, metaKeyed);
                CATEGORIES.get(bucket(stack)).getEntries().add(e);
                BY_KEY.put(key, e);
            }
        }
        PALETTE_READY = true;
    }

    // canonical key for a stack: "registry:meta", with meta zeroed when it isn't an identity (durability
    // on gear, or an item that collapsed to one palette entry) so an inventory stack maps to the same key
    // its category Entry was built with. once the palette is built we key off META_KEYED -- before that
    // (i.e. during the build itself) fall back to getHasSubtypes so the enumerated keys come out right.
    public static String keyOf(final ItemStack stack) {
        if (stack == null || stack.getItem() == null) return null;
        final Item item = stack.getItem();
        final ResourceLocation rl = Item.itemRegistry.getNameForObject(item);
        final String base = rl == null ? ("id:" + Item.getIdFromItem(item)) : rl.toString();
        final boolean metaKeyed = PALETTE_READY ? META_KEYED.contains(item) : item.getHasSubtypes();
        return base + ":" + (metaKeyed ? stack.getMetadata() : 0);
    }

    public static String categoryRef(final int idx) { return CAT_PREFIX + CATEGORIES.get(idx).getName(); }

    public static boolean isCategoryKey(final String key) { return key != null && key.startsWith(CAT_PREFIX); }

    public int categoryIndexOf(final String key) {
        if (!isCategoryKey(key)) return -1;
        final String name = key.substring(CAT_PREFIX.length());
        for (int i = 0; i < CATEGORIES.size(); i++) {
            if (CATEGORIES.get(i).getName().equals(name)) return i;
        }
        return -1;
    }

    public boolean categoryIncludes(final int catIdx, final ItemStack stack) {
        if (stack == null || stack.getItem() == null) return false;
        ensureBuilt();   // populate META_KEYED so keyOf matches the palette before we test exclusion
        if (bucket(stack) != catIdx) return false;
        return !isExcluded(keyOf(stack));
    }

    @Override
    public List<Category> getCategories() { return sharedCategories(); }

    public static List<Category> sharedCategories() { ensureBuilt(); return CATEGORIES; }

    public Entry entryForKey(final String key) { ensureBuilt(); return BY_KEY.get(key); }

    public ItemStack iconForKey(final String key) {
        if (isCategoryKey(key)) {
            final int idx = categoryIndexOf(key);
            return idx >= 0 ? CATEGORIES.get(idx).getIcon() : null;
        }
        final Entry e = entryForKey(key);
        return e == null ? null : e.getIcon();
    }

    public boolean isExcluded(final String key) { return excluded.contains(key); }

    public List<List<String>> getSlots() { return slots; }
    public Set<String> getExcluded() { return excluded; }

    public void toggleItem(final String key) {
        if (!excluded.remove(key)) excluded.add(key);
    }

    // append to a slot's preference list (first drop = primary). re-dropping moves it to the end.
    public void dropOnSlot(final int slotIdx, final String key) {
        if (slotIdx < 0 || slotIdx >= SLOTS || key == null) return;
        final List<String> list = slots.get(slotIdx);
        list.remove(key);
        list.add(key);
    }

    public void removeFromSlot(final int slotIdx, final String key) {
        if (slotIdx < 0 || slotIdx >= SLOTS) return;
        slots.get(slotIdx).remove(key);
    }


    @Override
    public HotbarSetting describe(final String d) {
        super.describe(d);
        return this;
    }

    @Override
    public JsonElement toJson() {
        final JsonArray slotsArr = new JsonArray();
        for (int i = 0; i < SLOTS; i++) {
            final JsonArray slot = new JsonArray();
            for (final String key : slots.get(i)) {
                slot.add(new JsonPrimitive(key));
            }
            slotsArr.add(slot);
        }
        // Sort the excluded denylist so configs serialize deterministically.
        final List<String> excludedSorted = new ArrayList<>(excluded);
        Collections.sort(excludedSorted);
        final JsonArray excludedArr = new JsonArray();
        for (final String key : excludedSorted) {
            excludedArr.add(new JsonPrimitive(key));
        }
        final JsonObject obj = new JsonObject();
        obj.add("slots", slotsArr);
        obj.add("excluded", excludedArr);
        return obj;
    }

    @Override
    public void fromJson(final JsonElement json) {
        if (!json.isJsonObject()) {
            return;
        }
        final JsonObject obj = json.getAsJsonObject();
        // Clear the live state first so a restore replaces (not appends to) the defaults.
        for (final List<String> slot : slots) {
            slot.clear();
        }
        excluded.clear();
        if (obj.has("slots") && obj.get("slots").isJsonArray()) {
            final JsonArray slotsArr = obj.getAsJsonArray("slots");
            for (int i = 0; i < SLOTS && i < slotsArr.size(); i++) {
                final JsonElement el = slotsArr.get(i);
                if (!el.isJsonArray()) {
                    continue;
                }
                final List<String> dest = slots.get(i);
                for (final JsonElement key : el.getAsJsonArray()) {
                    try {
                        if (key.isJsonPrimitive() && key.getAsJsonPrimitive().isString()) {
                            dest.add(key.getAsString());
                        }
                    } catch (final Exception ignored) {
                    }
                }
            }
        }
        if (obj.has("excluded") && obj.get("excluded").isJsonArray()) {
            for (final JsonElement key : obj.getAsJsonArray("excluded")) {
                try {
                    if (key.isJsonPrimitive() && key.getAsJsonPrimitive().isString()) {
                        excluded.add(key.getAsString());
                    }
                } catch (final Exception ignored) {
                }
            }
        }
    }
}
