package dev.coyduck.managers;

import dev.coyduck.mixin.ClientPlayerInteractionManagerAccessor;
import it.unimi.dsi.fastutil.ints.Int2ObjectMaps;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.c2s.play.ClickSlotC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.screen.sync.ItemStackHash;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.function.Predicate;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public final class InventoryManager {
    private static final InventoryManager INSTANCE = new InventoryManager();

    public static final int HOTBAR_END = PlayerInventory.HOTBAR_SIZE;
    public static final int MAIN_END = PlayerInventory.MAIN_SIZE;
    public static final int OFFHAND = PlayerInventory.OFF_HAND_SLOT;

    public enum Swap {
        NONE,
        CLICK,
        HANDS
    }

    public enum SwitchMode {
        Silent,
        InvSwap
    }

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
        return ((ClientPlayerInteractionManagerAccessor) mc.interactionManager).coyduck$getLastSelectedSlot();
    }

    public void select(int slot) {
        mc.player.getInventory().setSelectedSlot(slot);
        ((ClientPlayerInteractionManagerAccessor) mc.interactionManager).coyduck$syncSelectedSlot();
    }

    public int handlerSlot(ScreenHandler handler, int index) {
        PlayerInventory inventory = mc.player.getInventory();
        for (Slot slot : handler.slots) {
            if (slot.inventory == inventory && slot.getIndex() == index) return slot.id;
        }
        return -1;
    }

    public Swap swapWithOffhand(int index) {
        ScreenHandler handler = mc.player.currentScreenHandler;
        int id = handlerSlot(handler, index);

        if (id != -1) {
            mc.interactionManager.clickSlot(handler.syncId, id, OFFHAND, SlotActionType.SWAP, mc.player);
            return Swap.CLICK;
        }

        if (!PlayerInventory.isValidHotbarIndex(index)) return Swap.NONE;

        int selected = selectedSlot();
        if (selected != index) select(index);
        mc.getNetworkHandler().sendPacket(new PlayerActionC2SPacket(PlayerActionC2SPacket.Action.SWAP_ITEM_WITH_OFFHAND, BlockPos.ORIGIN, Direction.DOWN));
        if (selected != index) select(selected);
        return Swap.HANDS;
    }

    public boolean swapWithHotbar(int index, int hotbar) {
        ScreenHandler handler = mc.player.currentScreenHandler;
        int id = handlerSlot(handler, index);
        if (id == -1) return false;

        mc.interactionManager.clickSlot(handler.syncId, id, hotbar, SlotActionType.SWAP, mc.player);
        return true;
    }

    public boolean withItem(int index, SwitchMode mode, Runnable action) {
        int selected = selectedSlot();
        if (index == selected) {
            action.run();
            return true;
        }

        if (mode == SwitchMode.Silent && PlayerInventory.isValidHotbarIndex(index)) {
            select(index);
            action.run();
            select(selected);
            return true;
        }

        if (!swapWithHotbar(index, selected)) return false;
        action.run();
        swapWithHotbar(index, selected);
        return true;
    }

    public void resync() {
        ScreenHandler handler = mc.player.currentScreenHandler;
        mc.getNetworkHandler().sendPacket(new ClickSlotC2SPacket(handler.syncId, -1, (short) -1, (byte) 0, SlotActionType.CLONE, Int2ObjectMaps.emptyMap(), ItemStackHash.EMPTY));
    }
}
