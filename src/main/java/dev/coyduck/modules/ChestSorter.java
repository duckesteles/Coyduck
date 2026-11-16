package dev.coyduck.modules;

import dev.coyduck.events.TickEvent;
import dev.coyduck.feature.BoolSetting;
import dev.coyduck.feature.EnumSetting;
import dev.coyduck.feature.Feature;
import dev.coyduck.feature.Features;
import dev.coyduck.feature.IntSetting;
import dev.coyduck.feature.ItemListSetting;
import dev.coyduck.feature.Setting;
import dev.coyduck.feature.SettingGroup;
import dev.coyduck.utils.PacketBatch;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.component.type.ItemEnchantmentsComponent;
import net.minecraft.component.type.PotionContentsComponent;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroup;
import net.minecraft.item.ItemGroups;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.world.World;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

public class ChestSorter extends Feature {
    private static final int PLAYER_SLOTS = 36;
    private static final int UNLISTED = 1_000_000;

    public enum Order {
        Smart,
        Name,
        Count
    }

    public enum Shulkers {
        Color,
        Contents,
        Name
    }

    private record Click(int slot, SlotActionType action) {}

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Order> order = sgGeneral.add(new EnumSetting.Builder<Order>()
        .name("order")
        .description("Smart follows the creative menu.")
        .defaultValue(Order.Smart)
        .build()
    );

    private final Setting<Shulkers> shulkers = sgGeneral.add(new EnumSetting.Builder<Shulkers>()
        .name("shulkers")
        .description("What shulker boxes are sorted by first.")
        .defaultValue(Shulkers.Color)
        .build()
    );

    private final Setting<Boolean> merge = sgGeneral.add(new BoolSetting.Builder()
        .name("merge")
        .description("Merges partial stacks first.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> dumpHotbar = sgGeneral.add(new BoolSetting.Builder()
        .name("dump-hotbar")
        .description("Dump empties the hotbar too.")
        .defaultValue(true)
        .build()
    );

    private final Setting<List<Item>> keep = sgGeneral.add(new ItemListSetting.Builder()
        .name("keep")
        .description("Never dumped.")
        .defaultValue(Items.TOTEM_OF_UNDYING, Items.ENCHANTED_GOLDEN_APPLE, Items.END_CRYSTAL, Items.ENDER_PEARL)
        .build()
    );

    private final Setting<Boolean> takeSearch = sgGeneral.add(new BoolSetting.Builder()
        .name("take-search")
        .description("Take grabs only search matches.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> speed = sgGeneral.add(new IntSetting.Builder()
        .name("clicks-per-tick")
        .description("0 does it all at once.")
        .defaultValue(0)
        .range(0, 40)
        .sliderRange(0, 20)
        .build()
    );

    private final ArrayDeque<Click> queue = new ArrayDeque<>();
    private final Object2IntOpenHashMap<Item> creative = new Object2IntOpenHashMap<>();
    private World creativeWorld;
    private int queueSync = -1;

    public ChestSorter() {
        super("chest-sorter", "Sort, take and dump buttons for storage.");
    }

    public static ChestSorter active() {
        ChestSorter module = Features.get(ChestSorter.class);
        return module != null && module.isActive() ? module : null;
    }

    @Override
    public void onDeactivate() {
        queue.clear();
    }

    public void sort(ScreenHandler handler) {
        int size = containerSize(handler);
        if (size < 2) return;
        if (!handler.getCursorStack().isEmpty()) {
            warning("Put down the item on your cursor first.");
            return;
        }
        Plan plan = new Plan(handler, size);
        if (merge.get()) plan.merge();
        plan.arrange(comparator());
        run(handler, plan.clicks);
    }

    public void take(ScreenHandler handler, String query) {
        ChestSearch search = ChestSearch.active();
        boolean filter = takeSearch.get() && search != null && query != null && !query.isBlank();
        List<Click> clicks = new ArrayList<>();
        for (int i = 0; i < containerSize(handler); i++) {
            ItemStack stack = handler.getSlot(i).getStack();
            if (stack.isEmpty() || filter && !search.matches(stack, query)) continue;
            clicks.add(new Click(i, SlotActionType.QUICK_MOVE));
        }
        run(handler, clicks);
    }

    public void dump(ScreenHandler handler) {
        int start = containerSize(handler);
        int end = start + (dumpHotbar.get() ? PLAYER_SLOTS : PLAYER_SLOTS - 9);
        List<Item> kept = keep.get();
        List<Click> clicks = new ArrayList<>();
        for (int i = start; i < end; i++) {
            ItemStack stack = handler.getSlot(i).getStack();
            if (stack.isEmpty() || kept.contains(stack.getItem())) continue;
            clicks.add(new Click(i, SlotActionType.QUICK_MOVE));
        }
        run(handler, clicks);
    }

    private void run(ScreenHandler handler, List<Click> clicks) {
        if (clicks.isEmpty() || mc.player == null || mc.interactionManager == null) return;
        if (speed.get() == 0) {
            PacketBatch.run(() -> {
                for (Click click : clicks) send(handler.syncId, click);
            });
            return;
        }
        queue.clear();
        queue.addAll(clicks);
        queueSync = handler.syncId;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (queue.isEmpty()) return;
        if (mc.player == null || mc.player.currentScreenHandler.syncId != queueSync) {
            queue.clear();
            return;
        }
        for (int i = 0; i < speed.get() && !queue.isEmpty(); i++) send(queueSync, queue.poll());
    }

    private void send(int syncId, Click click) {
        mc.interactionManager.clickSlot(syncId, click.slot, 0, click.action, mc.player);
    }

    private static int containerSize(ScreenHandler handler) {
        return Math.max(0, handler.slots.size() - PLAYER_SLOTS);
    }

    private static boolean same(ItemStack a, ItemStack b) {
        return ItemStack.areItemsAndComponentsEqual(a, b);
    }

    private Comparator<ItemStack> comparator() {
        Comparator<ItemStack> boxes = shulkerOrder();
        Comparator<ItemStack> smart = Comparator.comparingInt(this::group)
            .thenComparing((a, b) -> isShulker(a) && isShulker(b) ? boxes.compare(a, b) : 0)
            .thenComparingInt(this::rank)
            .thenComparing(ChestSorter::potion)
            .thenComparing(ChestSorter::custom)
            .thenComparing(Comparator.comparingInt(ChestSorter::enchantPower).reversed())
            .thenComparingInt(ItemStack::getDamage)
            .thenComparing(Comparator.comparingInt(ItemStack::getCount).reversed());
        return switch (order.get()) {
            case Smart -> smart;
            case Name -> Comparator.comparing(ChestSorter::name).thenComparing(smart);
            case Count -> Comparator.comparingInt(ItemStack::getCount).reversed().thenComparing(smart);
        };
    }

    private int group(ItemStack stack) {
        return isShulker(stack) ? rank(Items.SHULKER_BOX) : rank(stack);
    }

    private Comparator<ItemStack> shulkerOrder() {
        Comparator<ItemStack> color = Comparator.comparingInt(this::rank);
        Comparator<ItemStack> contents = Comparator.comparingInt(this::contentRank).thenComparing(Comparator.comparingInt(ChestSorter::contentCount).reversed());
        Comparator<ItemStack> label = Comparator.comparing(ChestSorter::custom);
        return switch (shulkers.get()) {
            case Color -> color.thenComparing(contents).thenComparing(label);
            case Contents -> contents.thenComparing(color).thenComparing(label);
            case Name -> label.thenComparing(color).thenComparing(contents);
        };
    }

    private int rank(ItemStack stack) {
        return rank(stack.getItem());
    }

    private int rank(Item item) {
        loadCreative();
        return creative.getOrDefault(item, UNLISTED + Registries.ITEM.getRawId(item));
    }

    private void loadCreative() {
        if (mc.world == null || mc.world == creativeWorld && !creative.isEmpty()) return;
        creativeWorld = mc.world;
        creative.clear();
        ItemGroups.updateDisplayContext(mc.world.getEnabledFeatures(), false, mc.world.getRegistryManager());
        int index = 0;
        for (ItemGroup group : ItemGroups.getGroupsToDisplay()) {
            if (group.getType() != ItemGroup.Type.CATEGORY) continue;
            for (ItemStack stack : group.getDisplayStacks()) {
                if (!creative.containsKey(stack.getItem())) creative.put(stack.getItem(), index);
                index++;
            }
        }
    }

    private int contentRank(ItemStack shulker) {
        ContainerComponent container = shulker.get(DataComponentTypes.CONTAINER);
        if (container == null) return Integer.MAX_VALUE;
        Object2IntOpenHashMap<Item> totals = new Object2IntOpenHashMap<>();
        for (ItemStack inside : container.iterateNonEmpty()) totals.addTo(inside.getItem(), inside.getCount());
        Item main = null;
        int most = 0;
        for (Object2IntMap.Entry<Item> entry : totals.object2IntEntrySet()) {
            if (entry.getIntValue() > most || entry.getIntValue() == most && main != null && rank(entry.getKey()) < rank(main)) {
                main = entry.getKey();
                most = entry.getIntValue();
            }
        }
        return main == null ? Integer.MAX_VALUE : rank(main);
    }

    private static int contentCount(ItemStack shulker) {
        ContainerComponent container = shulker.get(DataComponentTypes.CONTAINER);
        if (container == null) return 0;
        int total = 0;
        for (ItemStack inside : container.iterateNonEmpty()) total += inside.getCount();
        return total;
    }

    private static boolean isShulker(ItemStack stack) {
        return stack.getItem() instanceof BlockItem item && item.getBlock() instanceof ShulkerBoxBlock;
    }

    private static String potion(ItemStack stack) {
        PotionContentsComponent contents = stack.get(DataComponentTypes.POTION_CONTENTS);
        if (contents == null) return "";
        return contents.potion().flatMap(entry -> entry.getKey()).map(key -> key.getValue().toString()).orElse("");
    }

    private static String custom(ItemStack stack) {
        return stack.contains(DataComponentTypes.CUSTOM_NAME) ? name(stack) : "￿";
    }

    private static String name(ItemStack stack) {
        return stack.getName().getString().toLowerCase();
    }

    private static int enchantPower(ItemStack stack) {
        ItemEnchantmentsComponent enchants = stack.getEnchantments();
        ItemEnchantmentsComponent stored = stack.get(DataComponentTypes.STORED_ENCHANTMENTS);
        int total = 0;
        for (Object2IntMap.Entry<?> entry : enchants.getEnchantmentEntries()) total += entry.getIntValue();
        if (stored != null) for (Object2IntMap.Entry<?> entry : stored.getEnchantmentEntries()) total += entry.getIntValue();
        return total;
    }

    private static final class Plan {
        private final ItemStack[] slots;
        private final List<Click> clicks = new ArrayList<>();
        private ItemStack cursor = ItemStack.EMPTY;

        Plan(ScreenHandler handler, int size) {
            slots = new ItemStack[size];
            for (int i = 0; i < size; i++) slots[i] = handler.getSlot(i).getStack().copy();
        }

        void click(int index) {
            clicks.add(new Click(index, SlotActionType.PICKUP));
            ItemStack slot = slots[index];
            if (cursor.isEmpty()) {
                cursor = slot;
                slots[index] = ItemStack.EMPTY;
            } else if (slot.isEmpty()) {
                slots[index] = cursor;
                cursor = ItemStack.EMPTY;
            } else if (same(slot, cursor)) {
                int moved = Math.min(slot.getMaxCount() - slot.getCount(), cursor.getCount());
                slot.increment(moved);
                cursor.decrement(moved);
                if (cursor.isEmpty()) cursor = ItemStack.EMPTY;
            } else {
                slots[index] = cursor;
                cursor = slot;
            }
        }

        void merge() {
            for (int i = 0; i < slots.length; i++) {
                for (int j = slots.length - 1; j > i; j--) {
                    ItemStack into = slots[i];
                    ItemStack piece = slots[j];
                    if (into.isEmpty() || into.getCount() >= into.getMaxCount()) break;
                    if (piece.isEmpty() || piece.getCount() >= piece.getMaxCount() || !same(into, piece)) continue;
                    click(j);
                    click(i);
                    if (!cursor.isEmpty()) click(j);
                }
            }
        }

        void arrange(Comparator<ItemStack> comparator) {
            int n = slots.length;
            List<ItemStack> wanted = new ArrayList<>();
            for (ItemStack stack : slots) if (!stack.isEmpty()) wanted.add(stack);
            wanted.sort(comparator);
            int filled = wanted.size();

            int[] dest = new int[n];
            boolean[] taken = new boolean[n];
            boolean[] settled = new boolean[n];
            Arrays.fill(dest, -1);
            for (int p = 0; p < filled; p++) {
                if (!slots[p].isEmpty() && same(slots[p], wanted.get(p))) {
                    dest[p] = p;
                    taken[p] = true;
                    settled[p] = true;
                }
            }
            for (int p = 0; p < filled; p++) {
                if (settled[p]) continue;
                for (int s = 0; s < n; s++) {
                    if (taken[s] || slots[s].isEmpty() || !same(slots[s], wanted.get(p))) continue;
                    dest[s] = p;
                    taken[s] = true;
                    break;
                }
            }

            boolean[] moved = new boolean[n];
            for (int start = 0; start < n; start++) {
                if (dest[start] == -1 || dest[start] == start || moved[start]) continue;
                click(start);
                moved[start] = true;
                int at = dest[start];
                while (true) {
                    boolean empty = slots[at].isEmpty();
                    int next = dest[at];
                    click(at);
                    if (empty) break;
                    if (next == -1) {
                        dropCursor();
                        break;
                    }
                    moved[at] = true;
                    at = next;
                }
            }
        }

        private void dropCursor() {
            for (int i = 0; i < slots.length && !cursor.isEmpty(); i++) {
                if (slots[i].isEmpty()) click(i);
            }
        }
    }
}
