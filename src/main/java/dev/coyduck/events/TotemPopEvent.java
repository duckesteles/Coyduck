package dev.coyduck.events;

import net.minecraft.entity.player.PlayerEntity;

public final class TotemPopEvent {
    private static final TotemPopEvent INSTANCE = new TotemPopEvent();

    public PlayerEntity player;
    public int pops;

    public static TotemPopEvent get(PlayerEntity player, int pops) {
        INSTANCE.player = player;
        INSTANCE.pops = pops;
        return INSTANCE;
    }
}
