package dev.coyduck.managers;

import dev.coyduck.events.PacketApplyEvent;
import dev.coyduck.events.WorldResetEvent;
import dev.coyduck.utils.ExplosionDamage;
import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import meteordevelopment.meteorclient.events.entity.EntityAddedEvent;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.network.packet.s2c.play.EntitiesDestroyS2CPacket;
import net.minecraft.network.packet.s2c.play.EntitySpawnS2CPacket;
import net.minecraft.network.packet.s2c.play.ExplosionS2CPacket;
import net.minecraft.util.Util;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public final class CrystalTracker {
    private static final CrystalTracker INSTANCE = new CrystalTracker();

    private static final long OWN_LIFETIME = 10_000;
    private static final float MIN_RADIUS = 2.0f;
    private static final long REMOVED_LIFETIME = 2_000;

    private final Long2LongOpenHashMap placing = new Long2LongOpenHashMap();
    private final Int2LongOpenHashMap own = new Int2LongOpenHashMap();
    private final Int2LongOpenHashMap dead = new Int2LongOpenHashMap();
    private final Int2ObjectOpenHashMap<Removed> removed = new Int2ObjectOpenHashMap<>();

    private record Removed(Vec3d pos, long time) {}
    private int highestId;

    private CrystalTracker() {}

    public static CrystalTracker get() {
        return INSTANCE;
    }

    public void placed(BlockPos base) {
        placing.put(base.asLong(), Util.getMeasuringTimeMs() + LatencyManager.get().timeout());
    }

    public boolean isPlacing(BlockPos base) {
        long until = placing.getOrDefault(base.asLong(), Long.MIN_VALUE);
        return until != Long.MIN_VALUE && Util.getMeasuringTimeMs() < until;
    }

    public boolean hasPlacing() {
        long now = Util.getMeasuringTimeMs();
        for (long until : placing.values()) {
            if (now < until) return true;
        }
        return false;
    }

    public boolean isOwn(Entity crystal) {
        return own.containsKey(crystal.getId());
    }

    public void kill(Entity crystal, long duration) {
        dead.put(crystal.getId(), Util.getMeasuringTimeMs() + duration);
    }

    public boolean isDead(Entity entity) {
        long until = dead.getOrDefault(entity.getId(), Long.MIN_VALUE);
        return until != Long.MIN_VALUE && Util.getMeasuringTimeMs() < until;
    }

    public boolean isAlive(Entity entity) {
        return entity.isAlive() && !isDead(entity);
    }

    public void explodeAt(Vec3d center) {
        double range = ExplosionDamage.CRYSTAL_RANGE * ExplosionDamage.CRYSTAL_RANGE;
        long duration = LatencyManager.get().timeout();

        for (Entity entity : mc.world.getEntities()) {
            if (entity instanceof EndCrystalEntity && entity.getEntityPos().squaredDistanceTo(center) <= range) kill(entity, duration);
        }
    }

    public Vec3d removedPosition(int id) {
        Removed entry = removed.get(id);
        return entry == null ? null : entry.pos;
    }

    public int highestId() {
        return highestId;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    private void onApplyPre(PacketApplyEvent.Pre event) {
        switch (event.packet) {
            case EntitySpawnS2CPacket packet -> highestId = Math.max(highestId, packet.getEntityId());
            case EntitiesDestroyS2CPacket packet when mc.world != null -> {
                long now = Util.getMeasuringTimeMs();
                for (int id : packet.getEntityIds()) {
                    if (mc.world.getEntityById(id) instanceof EndCrystalEntity crystal) removed.put(id, new Removed(crystal.getEntityPos(), now));
                }
            }
            default -> {}
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    private void onApplyPost(PacketApplyEvent.Post event) {
        if (mc.world == null) return;

        switch (event.packet) {
            case ExplosionS2CPacket packet when packet.radius() >= MIN_RADIUS && !ExplosionDamage.isWind(packet) -> explode(packet.center(), packet.radius() * 2.0);
            case EntitiesDestroyS2CPacket packet -> {
                for (int id : packet.getEntityIds()) {
                    dead.remove(id);
                    own.remove(id);
                }
            }
            default -> {}
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    private void onEntityAdded(EntityAddedEvent event) {
        if (!(event.entity instanceof EndCrystalEntity crystal)) return;

        long base = crystal.getBlockPos().down().asLong();
        if (placing.remove(base) != placing.defaultReturnValue()) own.put(crystal.getId(), Util.getMeasuringTimeMs());
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        long now = Util.getMeasuringTimeMs();
        placing.values().removeIf(until -> now >= until);
        dead.values().removeIf(until -> now >= until);
        own.values().removeIf(time -> now - time > OWN_LIFETIME);
        removed.values().removeIf(entry -> now - entry.time > REMOVED_LIFETIME);
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        reset();
    }

    @EventHandler
    private void onWorldReset(WorldResetEvent event) {
        reset();
    }

    private void reset() {
        placing.clear();
        own.clear();
        dead.clear();
        removed.clear();
        highestId = 0;
    }

    private void explode(Vec3d center, double radius) {
        double squared = radius * radius;
        long duration = LatencyManager.get().timeout() * 2L;

        for (Entity entity : mc.world.getEntities()) {
            if (entity instanceof EndCrystalEntity && entity.getEntityPos().squaredDistanceTo(center) <= squared) kill(entity, duration);
        }
    }
}
