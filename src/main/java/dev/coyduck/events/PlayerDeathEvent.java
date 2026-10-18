package dev.coyduck.events;

import net.minecraft.entity.player.PlayerEntity;

public final class PlayerDeathEvent {
    private static final PlayerDeathEvent INSTANCE = new PlayerDeathEvent();

    public PlayerEntity player;
    public int pops;

    public static PlayerDeathEvent get(PlayerEntity player, int pops) {
        INSTANCE.player = player;
        INSTANCE.pops = pops;
        return INSTANCE;
    }
}
