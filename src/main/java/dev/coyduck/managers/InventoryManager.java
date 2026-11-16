package dev.coyduck.managers;

import dev.coyduck.core.Core;
import dev.coyduck.events.GameLeftEvent;
import dev.coyduck.events.PacketApplyEvent;
import dev.coyduck.events.PacketEvent;
import dev.coyduck.events.TickEvent;
import dev.coyduck.events.WorldResetEvent;
import dev.coyduck.mixin.ClientPlayerEntityAccessor;
import dev.coyduck.mixin.ClientPlayerInteractionManagerAccessor;
import dev.coyduck.utils.PacketBatch;
import dev.coyduck.utils.Trace;
import it.unimi.dsi.fastutil.ints.Int2ObjectMaps;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.ClickSlotC2SPacket;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractEntityC2SPacket;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.network.packet.s2c.play.UpdateSelectedSlotS2CPacket;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.screen.sync.ItemStackHash;
import net.minecraft.util.Hand;
import net.minecraft.util.Util;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.function.Predicate;

import static dev.coyduck.core.Core.mc;

public final class InventoryManager {
    private static final InventoryManager INSTANCE = new InventoryManager();

    private static final long SHORT_CLICK_WINDOW = 200;
    private static final long LONG_CLICK_WINDOW = 1_000;
    private static final int SHORT_CLICK_LIMIT = 4;
    private static final int LONG_CLICK_LIMIT = 15;
    private static final int CLICK_RESERVE = 1;
    private static final double STILL = 0.05;

    public static final int HOTBAR_END = PlayerInventory.HOTBAR_SIZE;
    public static final int MAIN_END = PlayerInventory.MAIN_SIZE;
    public static final int OFFHAND = PlayerInventory.OFF_HAND_SLOT;
    public static final int CHEST = PlayerInventory.MAIN_SIZE + EquipmentSlot.CHEST.getEntitySlotId();

    private static final int BARRIER_DISTANCE = 64;
    private static final long SELECT_MARGIN = 50;

    private final long[] heldAt = new long[HOTBAR_END];
    private int burst;
    private int burstMask;
    private boolean burstAttacked;
    private int sentSlot = -1;
    private final Map<Item, Integer> homes = new HashMap<>();
    private long stagedAt;

    public enum Swap {
        NONE,
        CLICK,
        HANDS
    }

    public enum SwitchMode {
        Silent,
        InvSwap
    }

    private final long[] clicks = new long[LONG_CLICK_LIMIT];
    private int clickHead;

    private InventoryManager() {}

    public static InventoryManager get() {
        return INSTANCE;
    }

    public ItemStack stack(int index) {
        return mc.player.getInventory().getStack(index);
    }

    public int find(Predicate<ItemStack> predicate, int from, int to) {
        for (int i = from; i < to; i++) {
            if (predicate.test(stack(i))) return i;
        }
        return -1;
    }

    public int count(Predicate<ItemStack> predicate) {
        int count = 0;
        for (int i = 0; i < MAIN_END; i++) {
            ItemStack stack = stack(i);
            if (predicate.test(stack)) count += stack.getCount();
        }
        ItemStack offhand = stack(OFFHAND);
        if (predicate.test(offhand)) count += offhand.getCount();
        return count;
    }

    public int selectedSlot() {
        return mc.player.getInventory().getSelectedSlot();
    }

    public int serverSlot() {
        return sentSlot != -1 ? sentSlot : ((ClientPlayerInteractionManagerAccessor) mc.interactionManager).coyduck$getLastSelectedSlot();
    }

    private long lastSelectChangeAt;

    public boolean isEating() {
        return mc.player.isUsingItem() && mc.player.getActiveHand() == Hand.MAIN_HAND;
    }

    public boolean isOffhandEating() {
        return mc.player != null && mc.player.isUsingItem() && mc.player.getActiveHand() == Hand.OFF_HAND;
    }

    public boolean wantsToEat() {
        if (isEating() || mc.options.useKey.isPressed()) return true;
        ItemStack main = mc.player.getMainHandStack();
        return main.contains(DataComponentTypes.FOOD) || main.contains(DataComponentTypes.CONSUMABLE);
    }

    public boolean select(int slot) {
        if (slot != selectedSlot() && isEating()) return false;
        selectNow(slot);
        return true;
    }

    public void noteSelectChange(int from, int to) {
        if (burst > 0) burstMask |= bit(from) | bit(to);
        else stamp(bit(from) | bit(to));
    }

    private void stamp(int mask) {
        long now = Util.getMeasuringTimeMs();
        for (int i = 0; i < HOTBAR_END; i++) {
            if ((mask & (1 << i)) != 0) heldAt[i] = now;
        }
    }

    public int heldMask() {
        LatencyManager latency = LatencyManager.get();
        long window = latency.rtt() + 2L * latency.jitter() + SELECT_MARGIN;
        long now = Util.getMeasuringTimeMs();

        int mask = 0;
        for (int i = 0; i < HOTBAR_END; i++) {
            if (heldAt[i] != 0 && now - heldAt[i] < window) mask |= 1 << i;
        }
        return mask;
    }

    private static int bit(int slot) {
        return PlayerInventory.isValidHotbarIndex(slot) ? 1 << slot : 0;
    }

    private void selectNow(int slot) {
        mc.player.getInventory().setSelectedSlot(slot);
        ((ClientPlayerInteractionManagerAccessor) mc.interactionManager).coyduck$syncSelectedSlot();
        if (serverSlot() != slot) mc.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(slot));
    }

    public int handlerSlot(ScreenHandler handler, int index) {
        PlayerInventory inventory = mc.player.getInventory();
        for (Slot slot : handler.slots) {
            if (slot.inventory == inventory && slot.getIndex() == index) return slot.id;
        }
        return -1;
    }

    public Swap swapWithOffhand(int index) {
        return swapWithOffhand(index, false);
    }

    public Swap swapWithOffhand(int index, boolean preferHands) {
        ScreenHandler handler = mc.player.currentScreenHandler;
        int id = handlerSlot(handler, index);
        boolean hands = preferHands && PlayerInventory.isValidHotbarIndex(index) && !mc.player.isUsingItem() && !(mc.currentScreen instanceof HandledScreen<?>);

        if (id != -1 && !hands) {
            unsprinted(() -> mc.interactionManager.clickSlot(handler.syncId, id, OFFHAND, SlotActionType.SWAP, mc.player));
            return Swap.CLICK;
        }

        if (!PlayerInventory.isValidHotbarIndex(index) || mc.player.isUsingItem()) return Swap.NONE;

        int selected = selectedSlot();
        selectNow(index);
        mc.getNetworkHandler().sendPacket(new PlayerActionC2SPacket(PlayerActionC2SPacket.Action.SWAP_ITEM_WITH_OFFHAND, BlockPos.ORIGIN, Direction.DOWN));
        if (selected != index) selectNow(selected);

        PlayerInventory inventory = mc.player.getInventory();
        ItemStack held = inventory.getStack(index);
        inventory.setStack(index, inventory.getStack(OFFHAND));
        inventory.setStack(OFFHAND, held);
        return Swap.HANDS;
    }

    public int spareHotbar(int avoid) {
        int reserved = Core.host().totemSlot();
        for (int i = 0; i < HOTBAR_END; i++) {
            if (i != avoid && i != reserved && stack(i).isEmpty()) return i;
        }
        for (int i = 0; i < HOTBAR_END; i++) {
            if (i != avoid && i != reserved && !stack(i).contains(DataComponentTypes.DEATH_PROTECTION)) return i;
        }
        return -1;
    }

    public boolean swapWithHotbar(int index, int hotbar) {
        ScreenHandler handler = mc.player.currentScreenHandler;
        int id = handlerSlot(handler, index);
        if (id == -1) return false;

        unsprinted(() -> mc.interactionManager.clickSlot(handler.syncId, id, hotbar, SlotActionType.SWAP, mc.player));
        if (hotbar == serverSlot()) noteSelectChange(hotbar, hotbar);
        return true;
    }

    public boolean move(int from, int to) {
        ScreenHandler handler = mc.player.currentScreenHandler;
        int source = handlerSlot(handler, from);
        int target = handlerSlot(handler, to);
        if (source == -1 || target == -1) return false;
        unsprinted(() -> {
            mc.interactionManager.clickSlot(handler.syncId, source, 0, SlotActionType.PICKUP, mc.player);
            mc.interactionManager.clickSlot(handler.syncId, target, 0, SlotActionType.PICKUP, mc.player);
            if (!handler.getCursorStack().isEmpty()) mc.interactionManager.clickSlot(handler.syncId, source, 0, SlotActionType.PICKUP, mc.player);
        });
        return true;
    }

    private void unsprinted(Runnable clicks) {
        if (!((ClientPlayerEntityAccessor) mc.player).coyduck$lastSprinting()) {
            clicks.run();
            return;
        }
        PacketBatch.run(() -> {
            mc.getNetworkHandler().sendPacket(new ClientCommandC2SPacket(mc.player, ClientCommandC2SPacket.Mode.STOP_SPRINTING));
            clicks.run();
            mc.getNetworkHandler().sendPacket(new ClientCommandC2SPacket(mc.player, ClientCommandC2SPacket.Mode.START_SPRINTING));
        });
    }

    public boolean withItem(int index, SwitchMode mode, Runnable action) {
        if (index != selectedSlot() && isEating()) return false;
        return withItemWhileEating(index, mode, action);
    }

    public boolean withItemWhileEating(int index, SwitchMode mode, Runnable action) {
        boolean[] done = {false};
        PacketBatch.run(() -> done[0] = withItemNow(index, mode, action));
        return done[0];
    }

    private boolean withItemNow(int index, SwitchMode mode, Runnable action) {
        if (PlayerInventory.isValidHotbarIndex(index)) remember(stack(index).getItem(), index);
        int before = serverSlot();
        burst++;
        try {
            return switchedNow(index, mode, action);
        } finally {
            if (--burst == 0) {
                int after = serverSlot();
                if (burstAttacked) stamp(burstMask | bit(before) | bit(after));
                else if (after != before) stamp(bit(before) | bit(after));
                burstMask = 0;
                burstAttacked = false;
                refill();
            }
        }
    }

    private int stage(int index, int selected) {
        if (index == OFFHAND || MiningManager.get().isHolding()) return -1;
        if (Util.getMeasuringTimeMs() - stagedAt < LatencyManager.get().timeout()) return -1;

        Item item = stack(index).getItem();
        Integer home = homes.get(item);
        int reserved = item == Items.TOTEM_OF_UNDYING ? -1 : Core.host().totemSlot();
        int target = home != null && home != selected && home != reserved && stack(home).isEmpty() ? home : -1;
        for (int i = 0; target == -1 && i < HOTBAR_END; i++) {
            if (i != selected && i != reserved && stack(i).isEmpty()) target = i;
        }
        if (target == -1 || !swapWithHotbar(index, target)) return -1;

        remember(item, target);
        stagedAt = Util.getMeasuringTimeMs();
        return target;
    }

    private void remember(Item item, int slot) {
        if (item == Items.AIR || item != Items.TOTEM_OF_UNDYING && slot == Core.host().totemSlot()) return;
        homes.values().removeIf(home -> home == slot);
        homes.put(item, slot);
    }

    private void refill() {
        if (homes.isEmpty() || !spareClick() || mc.player == null || mc.interactionManager == null || mc.currentScreen instanceof HandledScreen<?> || isEating() || isOffhandEating()) return;
        if (!mc.player.currentScreenHandler.getCursorStack().isEmpty()) return;

        Iterator<Map.Entry<Item, Integer>> it = homes.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Item, Integer> home = it.next();
            Item item = home.getKey();
            int slot = home.getValue();
            ItemStack held = stack(slot);
            if (!held.isEmpty()) {
                if (!held.isOf(item)) it.remove();
                continue;
            }

            int from = find(stack -> stack.isOf(item), HOTBAR_END, MAIN_END);
            if (from != -1) swapWithHotbar(from, slot);
        }
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        refill();
    }

    private boolean switchedNow(int index, SwitchMode mode, Runnable action) {
        int selected = selectedSlot();
        if (index == selected) {
            action.run();
            return true;
        }

        boolean keepMine = MiningManager.get().guardsHeldTool() && standingStill() && spareClicks(2);
        if (mode == SwitchMode.Silent && !isEating() && !keepMine) {
            int hotbar = PlayerInventory.isValidHotbarIndex(index) ? index : stage(index, selected);
            if (hotbar != -1) {
                selectNow(hotbar);
                action.run();
                selectNow(selected);
                return true;
            }
            int spare = selected == Core.host().totemSlot() ? spareHotbar(selected) : -1;
            if (spare != -1 && swapWithHotbar(index, spare)) {
                selectNow(spare);
                action.run();
                selectNow(selected);
                swapWithHotbar(index, spare);
                return true;
            }
        }

        if (!swapWithHotbar(index, selected)) return false;
        if (keepMine) Trace.log("mine kept by swap {}", stack(selected).getItem());
        action.run();
        swapWithHotbar(index, selected);
        return true;
    }

    private boolean standingStill() {
        if (mc.player.isUsingItem()) return false;
        double dx = mc.player.getX() - mc.player.lastX;
        double dz = mc.player.getZ() - mc.player.lastZ;
        return dx * dx + dz * dz < STILL * STILL;
    }

    public boolean spareClick() {
        return spareClicks(1);
    }

    public boolean spareClicks(int count) {
        long now = Util.getMeasuringTimeMs();
        int recent = 0;
        int second = 0;
        for (long at : clicks) {
            if (now - at < SHORT_CLICK_WINDOW) recent++;
            if (now - at < LONG_CLICK_WINDOW) second++;
        }
        return recent + count <= SHORT_CLICK_LIMIT - CLICK_RESERVE && second + count <= LONG_CLICK_LIMIT - CLICK_RESERVE;
    }

    @EventHandler
    private void onSent(PacketEvent.Sent event) {
        if (event.packet instanceof ClickSlotC2SPacket) clicks[clickHead++ % clicks.length] = Util.getMeasuringTimeMs();
        if (event.packet instanceof PlayerInteractEntityC2SPacket && burst > 0) burstAttacked = true;
        if (!(event.packet instanceof UpdateSelectedSlotC2SPacket packet)) return;

        int slot = packet.getSelectedSlot();
        int previous = sentSlot != -1 ? sentSlot : slot;
        sentSlot = slot;
        if (slot != previous) {
            lastSelectChangeAt = Util.getMeasuringTimeMs();
            noteSelectChange(previous, slot);
        }
    }

    public long sinceSelectChange() {
        return Util.getMeasuringTimeMs() - lastSelectChangeAt;
    }

    @EventHandler
    private void onApply(PacketApplyEvent.Post event) {
        if (event.packet instanceof UpdateSelectedSlotS2CPacket packet) sentSlot = packet.slot();
    }

    @EventHandler
    private void onWorldReset(WorldResetEvent event) {
        sentSlot = -1;
        homes.clear();
        Arrays.fill(heldAt, 0);
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        sentSlot = -1;
        homes.clear();
        Arrays.fill(heldAt, 0);
    }

    public int barrier() {
        BlockPos far = mc.player.getBlockPos().up(BARRIER_DISTANCE);
        int[] sent = {0};
        ((ClientPlayerInteractionManagerAccessor) mc.interactionManager).coyduck$sendSequencedPacket(mc.world, sequence -> {
            sent[0] = sequence;
            return new PlayerActionC2SPacket(PlayerActionC2SPacket.Action.ABORT_DESTROY_BLOCK, far, Direction.DOWN, sequence);
        });
        return sent[0];
    }

    public void resync() {
        ScreenHandler handler = mc.player.currentScreenHandler;
        unsprinted(() -> mc.getNetworkHandler().sendPacket(new ClickSlotC2SPacket(handler.syncId, -1, (short) -1, (byte) 0, SlotActionType.CLONE, Int2ObjectMaps.emptyMap(), ItemStackHash.EMPTY)));
    }
}
