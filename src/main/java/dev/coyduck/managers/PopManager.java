package dev.coyduck.managers;

import dev.coyduck.events.PacketApplyEvent;
import dev.coyduck.events.PlayerDeathEvent;
import dev.coyduck.events.TotemPopEvent;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.entity.EntityStatuses;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.packet.s2c.play.EntityStatusS2CPacket;

import java.util.UUID;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public final class PopManager {
    private static final PopManager INSTANCE = new PopManager();

    private final Object2IntMap<UUID> pops = new Object2IntOpenHashMap<>();

    private PopManager() {}

    public static PopManager get() {
        return INSTANCE;
    }

    public int pops(PlayerEntity player) {
        return pops.getInt(player.getUuid());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    private void onApply(PacketApplyEvent.Post event) {
        if (!(event.packet instanceof EntityStatusS2CPacket packet) || mc.world == null) return;

        byte status = packet.getStatus();
        if (status != EntityStatuses.USE_TOTEM_OF_UNDYING && status != EntityStatuses.PLAY_DEATH_SOUND_OR_ADD_PROJECTILE_HIT_PARTICLES) return;
        if (!(packet.getEntity(mc.world) instanceof PlayerEntity player)) return;

        if (status == EntityStatuses.USE_TOTEM_OF_UNDYING) {
            int count = pops.mergeInt(player.getUuid(), 1, Integer::sum);
            MeteorClient.EVENT_BUS.post(TotemPopEvent.get(player, count));
        } else {
            int count = pops.removeInt(player.getUuid());
            MeteorClient.EVENT_BUS.post(PlayerDeathEvent.get(player, count));
        }
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        pops.clear();
    }
}
