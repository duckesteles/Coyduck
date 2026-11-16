package dev.coyduck.core;

import net.minecraft.block.BlockState;
import net.minecraft.entity.player.PlayerEntity;

public interface Host {
    Host NONE = new Host() {};

    default boolean isFriend(PlayerEntity player) {
        return false;
    }

    default boolean anyModuleActive() {
        return false;
    }

    default boolean holdsMovement() {
        return false;
    }

    default boolean freezesRotation() {
        return false;
    }

    default boolean spoofsGround() {
        return false;
    }

    default boolean keepsBlock(BlockState state) {
        return false;
    }

    default boolean canAffordPop() {
        return false;
    }

    default int totemSlot() {
        return 8;
    }

    default PlayerEntity preferredTarget() {
        return null;
    }

    default double keepHealth() {
        return 0;
    }

    default int tint(int argb) {
        return argb;
    }

    default int playerColor(PlayerEntity player, int fallback) {
        return fallback;
    }

    default void timer(double multiplier) {}

    default void swapChest() {}
}
