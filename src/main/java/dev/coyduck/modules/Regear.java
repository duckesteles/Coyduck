package dev.coyduck.modules;

import dev.coyduck.Coyduck;
import dev.coyduck.utils.Trace;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringListSetting;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.client.gui.screen.ingame.ShulkerBoxScreen;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;

public class Regear extends Module {
    private static final int TOTEM_SLOT = 8;
    private static final int SLOTS = 36;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<String> kit = sgGeneral.add(new StringSetting.Builder()
        .name("kit")
        .description("Kit to fill. Save one with .kit save <name>.")
        .defaultValue("pvp")
        .build()
    );

    private final Setting<Integer> perTick = sgGeneral.add(new IntSetting.Builder()
        .name("moves-per-tick")
        .description("Stacks moved each tick.")
        .defaultValue(2)
        .range(1, 10)
        .sliderRange(1, 6)
        .build()
    );

    private final Setting<Boolean> still = sgGeneral.add(new BoolSetting.Builder()
        .name("only-standing-still")
        .description("Waits while you move.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> close = sgGeneral.add(new BoolSetting.Builder()
        .name("close-when-done")
        .description("Closes the container when done.")
        .defaultValue(false)
        .build()
    );

    private final Setting<List<String>> kits = sgGeneral.add(new StringListSetting.Builder()
        .name("saved-kits")
        .description("Kits saved with .kit save.")
        .defaultValue(List.of())
        .visible(() -> false)
        .build()
    );

    public Regear() {
        super(Coyduck.CATEGORY, "regear", "Refills your kit from a container.");
    }

    public boolean save(String name) {
        if (mc.player == null) return false;
        StringBuilder entry = new StringBuilder(name).append('=');
        for (int i = 0; i < SLOTS; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (i > 0) entry.append(',');
            entry.append(stack.isEmpty() ? "" : Registries.ITEM.getId(stack.getItem()).toString());
        }
        List<String> list = new ArrayList<>(kits.get());
        list.removeIf(line -> line.startsWith(name + "="));
        list.add(entry.toString());
        kits.set(list);
        return true;
    }

    public boolean delete(String name) {
        List<String> list = new ArrayList<>(kits.get());
        boolean removed = list.removeIf(line -> line.startsWith(name + "="));
        if (removed) kits.set(list);
        return removed;
    }

    public List<String> names() {
        List<String> names = new ArrayList<>();
        for (String line : kits.get()) names.add(line.substring(0, Math.max(0, line.indexOf('='))));
        return names;
    }

    private Item[] layout() {
        for (String line : kits.get()) {
            if (!line.startsWith(kit.get() + "=")) continue;
            String[] parts = line.substring(line.indexOf('=') + 1).split(",", -1);
            Item[] items = new Item[SLOTS];
            for (int i = 0; i < Math.min(SLOTS, parts.length); i++) {
                if (parts[i].isEmpty()) continue;
                Identifier id = Identifier.tryParse(parts[i]);
                items[i] = id == null ? null : Registries.ITEM.get(id);
            }
            items[TOTEM_SLOT] = Items.TOTEM_OF_UNDYING;
            return items;
        }
        return null;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.interactionManager == null) return;
        if (!(mc.currentScreen instanceof GenericContainerScreen) && !(mc.currentScreen instanceof ShulkerBoxScreen)) return;
        if (still.get() && mc.player.getVelocity().horizontalLengthSquared() > 1.0E-4) return;
        if (!mc.player.currentScreenHandler.getCursorStack().isEmpty()) return;
        Item[] layout = layout();
        if (layout == null) return;

        ScreenHandler handler = mc.player.currentScreenHandler;
        int container = handler.slots.size() - SLOTS;
        if (container <= 0) return;

        int moves = 0;
        for (int i = 0; i < SLOTS && moves < perTick.get(); i++) {
            Item want = layout[i];
            if (want == null) continue;
            int target = i < 9 ? container + 27 + i : container + i - 9;
            ItemStack have = handler.getSlot(target).getStack();
            if (!have.isEmpty() && (!have.isOf(want) || have.getCount() >= have.getMaxCount())) continue;

            int source = find(handler, container, want);
            if (source == -1) continue;
            click(handler, source);
            click(handler, target);
            if (!handler.getCursorStack().isEmpty()) click(handler, source);
            moves++;
            Trace.log("regear moved {} into slot {}", Registries.ITEM.getId(want), i + 1);
        }

        if (moves == 0 && close.get()) mc.player.closeHandledScreen();
    }

    private static int find(ScreenHandler handler, int container, Item item) {
        int best = -1;
        int count = 0;
        for (int i = 0; i < container; i++) {
            ItemStack stack = handler.getSlot(i).getStack();
            if (stack.isOf(item) && stack.getCount() > count) {
                best = i;
                count = stack.getCount();
            }
        }
        return best;
    }

    private void click(ScreenHandler handler, int slot) {
        mc.interactionManager.clickSlot(handler.syncId, slot, 0, SlotActionType.PICKUP, mc.player);
    }
}
