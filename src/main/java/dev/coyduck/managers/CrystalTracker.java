package dev.coyduck.managers;

import dev.coyduck.events.PacketApplyEvent;
import dev.coyduck.events.WorldResetEvent;
import dev.coyduck.mixin.ClientWorldAccessor;
import dev.coyduck.utils.ExplosionDamage;
import dev.coyduck.utils.Trace;
import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import meteordevelopment.meteorclient.events.entity.EntityAddedEvent;
import meteordevelopment.meteorclient.events.entity.player.AttackEntityEvent;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.utils.world.TickRate;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.block.ShapeContext;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ExperienceOrbEntity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.network.packet.s2c.play.EntitiesDestroyS2CPacket;
import net.minecraft.network.packet.s2c.play.EntitySpawnS2CPacket;
import net.minecraft.network.packet.s2c.play.ExplosionS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerActionResponseS2CPacket;
import net.minecraft.util.Util;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public final class CrystalTracker {
    private static final CrystalTracker INSTANCE = new CrystalTracker();

    private static final long OWN_LIFETIME = 10_000;
    private static final long BLACKLIST_TIME = 1_500;
    private static final int BLACKLIST_FAILURES = 2;
    private static final float MIN_RADIUS = 2.0f;
    private static final long REMOVED_LIFETIME = 2_000;
    private static final double DROP_KILL_RANGE = 6.0;

    private static final long ACK_GRACE = 50;
    private static final long REJECT_SLACK = 50;

    private final Long2LongOpenHashMap placing = new Long2LongOpenHashMap();
    private final Long2LongOpenHashMap failedAt = new Long2LongOpenHashMap();
    private final Long2LongOpenHashMap failures = new Long2LongOpenHashMap();
    private final Int2LongOpenHashMap own = new Int2LongOpenHashMap();
    private final Int2LongOpenHashMap dead = new Int2LongOpenHashMap();
    private final Int2ObjectOpenHashMap<Removed> removed = new Int2ObjectOpenHashMap<>();

    private record Removed(Vec3d pos, long time) {}

    private record Attack(int id, Vec3d center, long time, long until) {}

    private final Long2IntOpenHashMap placingSequence = new Long2IntOpenHashMap();
    private final Long2LongOpenHashMap acked = new Long2LongOpenHashMap();
    private final List<Attack> attacks = new ArrayList<>();
    private int highestId;

    private CrystalTracker() {}

    public static CrystalTracker get() {
        return INSTANCE;
    }

    public void placed(BlockPos base) {
        placing.put(base.asLong(), Util.getMeasuringTimeMs() + LatencyManager.get().timeout());
        if (mc.world != null) placingSequence.put(base.asLong(), ((ClientWorldAccessor) mc.world).coyduck$getPendingUpdateManager().getSequence());
    }

    public boolean isBlacklisted(BlockPos base) {
        long key = base.asLong();
        return failures.get(key) >= BLACKLIST_FAILURES && Util.getMeasuringTimeMs() - failedAt.get(key) < BLACKLIST_TIME;
    }

    public boolean isPlacing(BlockPos base) {
        long until = placing.getOrDefault(base.asLong(), Long.MIN_VALUE);
        return until != Long.MIN_VALUE && Util.getMeasuringTimeMs() < until;
    }

    public boolean cancelPlacing(Box box) {
        return placing.keySet().removeIf(key -> {
            BlockPos above = BlockPos.fromLong(key).up();
            return box.intersects(new Box(above.getX(), above.getY(), above.getZ(), above.getX() + 1, above.getY() + 2, above.getZ() + 1));
        });
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

    public boolean isRecentOwn(Entity crystal, long age) {
        return own.containsKey(crystal.getId()) && Util.getMeasuringTimeMs() - own.get(crystal.getId()) <= age;
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
        explodeAt(center, ExplosionDamage.CRYSTAL_RANGE);
    }

    public void explodeAt(Vec3d center, double radius) {
        double range = radius * radius;
        double dropRange = DROP_KILL_RANGE * DROP_KILL_RANGE;
        long duration = LatencyManager.get().timeout();

        for (Entity entity : mc.world.getEntities()) {
            double distance = entity.getEntityPos().squaredDistanceTo(center);
            if (entity instanceof EndCrystalEntity) {
                if (distance <= range) kill(entity, duration);
            }
            else if ((entity instanceof ItemEntity || entity instanceof ExperienceOrbEntity) && distance <= dropRange
                && ExplosionDamage.exposure(center, entity.getBoundingBox(), mc.world, ShapeContext.absent()) > 0) kill(entity, duration);
        }
    }

    public boolean placingOverlaps(BlockPos base) {
        long now = Util.getMeasuringTimeMs();
        for (var entry : placing.long2LongEntrySet()) {
            if (now >= entry.getLongValue()) continue;
            BlockPos other = BlockPos.fromLong(entry.getLongKey());
            if (Math.abs(other.getX() - base.getX()) <= 1 && Math.abs(other.getY() - base.getY()) <= 1 && Math.abs(other.getZ() - base.getZ()) <= 1) return true;
        }
        return false;
    }

    public boolean placingNear(Vec3d center, double radius) {
        long now = Util.getMeasuringTimeMs();
        for (var entry : placing.long2LongEntrySet()) {
            if (now >= entry.getLongValue()) continue;
            BlockPos base = BlockPos.fromLong(entry.getLongKey());
            if (new Vec3d(base.getX() + 0.5, base.getY() + 1.0, base.getZ() + 0.5).squaredDistanceTo(center) <= radius * radius) return true;
        }
        return false;
    }

    public Vec3d removedPosition(int id) {
        Removed entry = removed.get(id);
        return entry == null ? null : entry.pos;
    }

    public int highestId() {
        return highestId;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    private void onAttack(AttackEntityEvent event) {
        if (event.isCancelled() || !(event.entity instanceof EndCrystalEntity crystal) || !InteractionManager.get().canHitWithHand()) return;
        if (!RotationManager.get().isLookingAt(crystal.getBoundingBox(), mc.player.getEntityInteractionRange() + 1)) return;
        long now = Util.getMeasuringTimeMs();
        explodeAt(crystal.getEntityPos());
        attacks.add(new Attack(crystal.getId(), crystal.getEntityPos(), now, now + LatencyManager.get().timeout()));
    }

    public void broke(EndCrystalEntity crystal) {
        long now = Util.getMeasuringTimeMs();
        if (!attacks.isEmpty() && attacks.getLast().id == crystal.getId() && now - attacks.getLast().time <= REJECT_SLACK) return;
        explodeAt(crystal.getEntityPos());
        attacks.add(new Attack(crystal.getId(), crystal.getEntityPos(), now, now + LatencyManager.get().timeout()));
    }

    private void checkAttacks(long now) {
        if (attacks.isEmpty() || mc.world == null) return;
        LatencyManager latency = LatencyManager.get();
        long wait = latency.rtt() + 2L * latency.jitter() + REJECT_SLACK;
        attacks.removeIf(attack -> {
            if (now - attack.time < wait) return false;
            if (mc.world.getEntityById(attack.id) instanceof EndCrystalEntity crystal && !crystal.isRemoved()) revive(attack);
            return true;
        });
    }

    private void revive(Attack attack) {
        double range = ExplosionDamage.CRYSTAL_RANGE * ExplosionDamage.CRYSTAL_RANGE;
        int revived = 0;
        var it = dead.int2LongEntrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            if (entry.getLongValue() > attack.until + REJECT_SLACK) continue;
            if (!(mc.world.getEntityById(entry.getIntKey()) instanceof EndCrystalEntity crystal) || crystal.isRemoved()) continue;
            if (crystal.getEntityPos().squaredDistanceTo(attack.center) > range) continue;
            it.remove();
            revived++;
        }
        HurtTracker.get().forgetExpected(attack.time, attack.time + REJECT_SLACK);
        Trace.log("crystal break at {} was not answered, revived {} crystals", attack.center, revived);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    private void onApplyPre(PacketApplyEvent.Pre event) {
        switch (event.packet) {
            case EntitySpawnS2CPacket packet -> {
                int id = packet.getEntityId();
                boolean own = packet.getEntityType() == EntityType.END_CRYSTAL && isPlacing(BlockPos.ofFloored(packet.getX(), packet.getY(), packet.getZ()).down());
                if (id > highestId || own) highestId = id;
            }
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
            case ExplosionS2CPacket packet when packet.radius() >= MIN_RADIUS && !ExplosionDamage.isWind(packet) -> {
                long base = BlockPos.ofFloored(packet.center()).down().asLong();
                placing.remove(base);
                failures.remove(base);
                failedAt.remove(base);
                explode(packet.center(), packet.radius() * 2.0);
            }
            case EntitiesDestroyS2CPacket packet -> {
                for (int id : packet.getEntityIds()) {
                    dead.remove(id);
                    own.remove(id);
                }
            }
            case PlayerActionResponseS2CPacket packet when !placingSequence.isEmpty() && packet.sequence() <= ((ClientWorldAccessor) mc.world).coyduck$getPendingUpdateManager().getSequence() -> {
                long now = Util.getMeasuringTimeMs();
                int sequence = packet.sequence();
                var it = placingSequence.long2IntEntrySet().iterator();
                while (it.hasNext()) {
                    var entry = it.next();
                    if (entry.getIntValue() > sequence) continue;
                    if (isPlacing(BlockPos.fromLong(entry.getLongKey()))) acked.put(entry.getLongKey(), now);
                    it.remove();
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
        failures.remove(base);
        failedAt.remove(base);
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        long now = Util.getMeasuringTimeMs();
        if (!acked.isEmpty()) {
            float rate = TickRate.INSTANCE.getTickRate();
            long tick = (long) (1000 / Math.max(Math.min(rate, 20.0f), 1.0f));
            long grace = ACK_GRACE + tick + LatencyManager.get().jitter();
            var answered = acked.long2LongEntrySet().iterator();
            while (answered.hasNext()) {
                var entry = answered.next();
                long key = entry.getLongKey();
                if (!placing.containsKey(key)) {
                    answered.remove();
                } else if (now - entry.getLongValue() >= grace) {
                    placing.put(key, now);
                    answered.remove();
                }
            }
        }
        checkAttacks(now);
        var expired = placing.long2LongEntrySet().iterator();
        while (expired.hasNext()) {
            var entry = expired.next();
            if (now < entry.getLongValue()) continue;
            long key = entry.getLongKey();
            long count = now - failedAt.get(key) < BLACKLIST_TIME ? failures.get(key) + 1 : 1;
            failures.put(key, count);
            failedAt.put(key, now);
            expired.remove();
        }
        var stale = failedAt.long2LongEntrySet().iterator();
        while (stale.hasNext()) {
            var entry = stale.next();
            if (now - entry.getLongValue() < BLACKLIST_TIME) continue;
            failures.remove(entry.getLongKey());
            stale.remove();
        }
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
        failures.clear();
        failedAt.clear();
        placing.clear();
        own.clear();
        dead.clear();
        removed.clear();
        placingSequence.clear();
        acked.clear();
        attacks.clear();
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
