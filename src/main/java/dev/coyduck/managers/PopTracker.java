package dev.coyduck.managers;

import dev.coyduck.core.Bus;
import dev.coyduck.events.DeathEvent;
import dev.coyduck.events.GameLeftEvent;
import dev.coyduck.events.PacketApplyEvent;
import dev.coyduck.events.TickEvent;
import dev.coyduck.events.TotemPopEvent;
import dev.coyduck.events.WorldResetEvent;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.entity.EntityStatuses;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.packet.s2c.play.EntityStatusS2CPacket;
import net.minecraft.util.Util;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static dev.coyduck.core.Core.mc;

public final class PopTracker {
    private static final PopTracker INSTANCE = new PopTracker();

    private final Object2IntOpenHashMap<UUID> pops = new Object2IntOpenHashMap<>();
    private final Object2LongOpenHashMap<UUID> lastPop = new Object2LongOpenHashMap<>();
    private final Set<UUID> died = new HashSet<>();

    private PopTracker() {}

    public static PopTracker get() {
        return INSTANCE;
    }

    public int pops(PlayerEntity player) {
        return pops.getInt(player.getUuid());
    }

    public long sinceLastPop(PlayerEntity player) {
        long at = lastPop.getLong(player.getUuid());
        return at == 0 ? Long.MAX_VALUE : Util.getMeasuringTimeMs() - at;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    private void onPacket(PacketApplyEvent.Pre event) {
        if (!(event.packet instanceof EntityStatusS2CPacket packet) || mc.world == null) return;
        if (!(packet.getEntity(mc.world) instanceof PlayerEntity player)) return;

        if (packet.getStatus() == EntityStatuses.USE_TOTEM_OF_UNDYING) {
            int count = pops.addTo(player.getUuid(), 1) + 1;
            lastPop.put(player.getUuid(), Util.getMeasuringTimeMs());
            Bus.post(TotemPopEvent.get(player, count));
        } else if (packet.getStatus() == EntityStatuses.PLAY_DEATH_SOUND_OR_ADD_PROJECTILE_HIT_PARTICLES) {
            died.add(player.getUuid());
            Bus.post(DeathEvent.get(player, pops(player)));
        }
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (died.isEmpty()) return;
        for (UUID uuid : died) {
            pops.removeInt(uuid);
            lastPop.removeLong(uuid);
        }
        died.clear();
    }

    @EventHandler
    private void onWorldReset(WorldResetEvent event) {
        clear();
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        clear();
    }

    private void clear() {
        pops.clear();
        lastPop.clear();
        died.clear();
    }
}
