package dev.coyduck.modules;

import dev.coyduck.Coyduck;
import dev.coyduck.utils.PacketBatch;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.ItemListSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

public class ChestSorter extends Module {
    private static final int PLAYER_SLOTS = 36;
    private static final int HOTBAR = 9;
    private static final int INVENTORY_START = 9;
    private static final int INVENTORY_END = 36;

    public enum Order {
        Type,
        Name,
        Count
    }

    private record Click(int slot, SlotActionType action) {}

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Order> order = sgGeneral.add(new EnumSetting.Builder<Order>()
        .name("order")
        .description("How sorted items are ordered.")
        .defaultValue(Order.Type)
        .build()
    );

    private final Setting<Boolean> merge = sgGeneral.add(new BoolSetting.Builder()
        .name("merge")
        .description("Merges partial stacks before sorting.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> dumpHotbar = sgGeneral.add(new BoolSetting.Builder()
        .name("dump-hotbar")
        .description("Dump also empties your hotbar.")
        .defaultValue(false)
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
        .description("Take grabs only search matches while searching.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> inventoryButton = sgGeneral.add(new BoolSetting.Builder()
        .name("inventory-button")
        .description("Sort button in your own inventory.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> speed = sgGeneral.add(new IntSetting.Builder()
        .name("clicks-per-tick")
        .description("0 does everything at once.")
        .defaultValue(0)
        .range(0, 40)
        .sliderRange(0, 20)
        .build()
    );

    private final ArrayDeque<Click> queue = new ArrayDeque<>();
    private int queueSync = -1;

    public ChestSorter() {
        super(Coyduck.CATEGORY, "chest-sorter", "Sort, take and dump buttons for chests.");
    }

    public static ChestSorter active() {
        ChestSorter module = Modules.get() == null ? null : Modules.get().get(ChestSorter.class);
        return module != null && module.isActive() ? module : null;
    }

    public boolean inventoryButton() {
        return inventoryButton.get();
    }

    @Override
    public void onDeactivate() {
        queue.clear();
    }

    public void sortContainer(ScreenHandler handler) {
        sort(handler, 0, containerSize(handler));
    }

    public void sortInventory(ScreenHandler handler) {
        if (handler instanceof PlayerScreenHandler) sort(handler, INVENTORY_START, INVENTORY_END);
        else sort(handler, containerSize(handler), containerSize(handler) + PLAYER_SLOTS - HOTBAR);
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
        int end = start + PLAYER_SLOTS - (dumpHotbar.get() ? 0 : HOTBAR);
        List<Item> kept = keep.get();
        List<Click> clicks = new ArrayList<>();
        for (int i = start; i < end; i++) {
            ItemStack stack = handler.getSlot(i).getStack();
            if (stack.isEmpty() || kept.contains(stack.getItem())) continue;
            clicks.add(new Click(i, SlotActionType.QUICK_MOVE));
        }
        run(handler, clicks);
    }

    private void sort(ScreenHandler handler, int from, int to) {
        if (to - from < 2) return;
        if (!handler.getCursorStack().isEmpty()) {
            warning("Put down the item on your cursor first.");
            return;
        }
        Plan plan = new Plan(handler, from, to);
        if (merge.get()) plan.merge();
        plan.arrange(comparator());
        run(handler, plan.clicks);
    }

    private Comparator<ItemStack> comparator() {
        Comparator<ItemStack> type = Comparator.comparingInt(stack -> Registries.ITEM.getRawId(stack.getItem()));
        Comparator<ItemStack> name = Comparator.comparing(stack -> stack.getName().getString());
        Comparator<ItemStack> count = Comparator.comparingInt(ItemStack::getCount).reversed();
        Comparator<ItemStack> damage = Comparator.comparingInt(ItemStack::getDamage);
        return switch (order.get()) {
            case Type -> type.thenComparing(name).thenComparing(count).thenComparing(damage);
            case Name -> name.thenComparing(type).thenComparing(count).thenComparing(damage);
            case Count -> count.thenComparing(type).thenComparing(name).thenComparing(damage);
        };
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
        return handler instanceof PlayerScreenHandler ? 0 : Math.max(0, handler.slots.size() - PLAYER_SLOTS);
    }

    private static boolean same(ItemStack a, ItemStack b) {
        return ItemStack.areItemsAndComponentsEqual(a, b);
    }

    private static final class Plan {
        private final int from;
        private final ItemStack[] slots;
        private final List<Click> clicks = new ArrayList<>();
        private ItemStack cursor = ItemStack.EMPTY;

        Plan(ScreenHandler handler, int from, int to) {
            this.from = from;
            slots = new ItemStack[to - from];
            for (int i = 0; i < slots.length; i++) slots[i] = handler.getSlot(from + i).getStack().copy();
        }

        void click(int index) {
            clicks.add(new Click(from + index, SlotActionType.PICKUP));
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
