package dev.coyduck.events;

import net.minecraft.entity.player.PlayerEntity;

public final class DeathEvent {
    private static final DeathEvent INSTANCE = new DeathEvent();

    public PlayerEntity player;
    public int pops;

    private DeathEvent() {}

    public static DeathEvent get(PlayerEntity player, int pops) {
        INSTANCE.player = player;
        INSTANCE.pops = pops;
        return INSTANCE;
    }
}
