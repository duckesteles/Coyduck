package dev.coyduck.managers;

import dev.coyduck.events.PacketApplyEvent;
import dev.coyduck.events.WorldResetEvent;
import dev.coyduck.utils.ExplosionDamage;
import dev.coyduck.utils.Targets;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.utils.world.TickRate;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageType;
import net.minecraft.entity.damage.DamageTypes;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.EntityDamageS2CPacket;
import net.minecraft.network.packet.s2c.play.ExplosionS2CPacket;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.tag.DamageTypeTags;
import net.minecraft.util.Util;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public final class HurtTracker {
    private static final HurtTracker INSTANCE = new HurtTracker();

    private static final int IMMUNITY_TICKS = 10;
    private static final long SELF_WINDOW = 500;
    private static final float PEARL_DAMAGE = 5.0f;
    private static final long MARGIN = 50;
    private static final long LIFETIME = 3_000;
    private static final long LATE_EXPLOSION = 100;

    private static final class Hurt {
        final long start;
        final long received;
        final boolean explosion;
        float raw;
        float speculative = Float.NaN;

        Hurt(long start, long received, boolean explosion, float raw) {
            this.start = start;
            this.received = received;
            this.explosion = explosion;
            this.raw = raw;
        }
    }

    private static final class Expected {
        final long time;
        float amount;
        float speculative = Float.NaN;

        Expected(long time, float amount) {
            this.time = time;
            this.amount = amount;
        }
    }

    private record Window(long start, float raw, boolean explosion) {}

    private static final int RECEIVED_LIMIT = 256;

    private final Int2ObjectOpenHashMap<Hurt> hurts = new Int2ObjectOpenHashMap<>();
    private final Int2ObjectOpenHashMap<Expected> expected = new Int2ObjectOpenHashMap<>();
    private final Map<Packet<?>, Long> received = Collections.synchronizedMap(new IdentityHashMap<>());

    private HurtTracker() {}

    public static HurtTracker get() {
        return INSTANCE;
    }

    public void expect(LivingEntity entity, float raw) {
        long now = Util.getMeasuringTimeMs();
        long arrival = now + LatencyManager.get().rtt() / 2;
        int id = entity.getId();

        Expected pending = freshExpected(id, now);
        if (pending != null && arrival >= expectedStart(pending) && arrival < expectedStart(pending) + window() + MARGIN) {
            pending.amount = max(pending.amount, raw);
            return;
        }

        Hurt hurt = hurts.get(id);
        if (hurt != null && arrival >= hurt.start && arrival < hurt.start + window() + MARGIN) {
            if (!Float.isNaN(hurt.raw)) hurt.raw = Math.max(hurt.raw, raw);
            return;
        }

        expected.put(id, new Expected(now, raw));
    }

    public void expectSpeculative(LivingEntity entity, float raw) {
        long now = Util.getMeasuringTimeMs();
        long arrival = now + LatencyManager.get().rtt() / 2;
        int id = entity.getId();

        Expected pending = freshExpected(id, now);
        if (pending != null && arrival >= expectedStart(pending) && arrival < expectedStart(pending) + window() + MARGIN) {
            pending.speculative = max(pending.speculative, raw);
            return;
        }

        Hurt hurt = hurts.get(id);
        if (hurt != null && arrival >= hurt.start && arrival < hurt.start + window() + MARGIN) {
            hurt.speculative = max(hurt.speculative, raw);
            return;
        }

        Expected created = new Expected(now, Float.NaN);
        created.speculative = raw;
        expected.put(id, created);
    }

    public void confirmSpeculative(LivingEntity entity) {
        int id = entity.getId();

        Expected pending = expected.get(id);
        if (pending != null && !Float.isNaN(pending.speculative)) {
            pending.amount = max(pending.amount, pending.speculative);
            pending.speculative = Float.NaN;
        }

        Hurt hurt = hurts.get(id);
        if (hurt != null && !Float.isNaN(hurt.speculative)) {
            if (!Float.isNaN(hurt.raw)) hurt.raw = Math.max(hurt.raw, hurt.speculative);
            hurt.speculative = Float.NaN;
        }
    }

    public void forgetSpeculative(LivingEntity entity) {
        int id = entity.getId();

        Expected pending = expected.get(id);
        if (pending != null) {
            pending.speculative = Float.NaN;
            if (Float.isNaN(pending.amount)) expected.remove(id);
        }

        Hurt hurt = hurts.get(id);
        if (hurt != null) hurt.speculative = Float.NaN;
    }

    private static float max(float a, float b) {
        if (Float.isNaN(a)) return b;
        if (Float.isNaN(b)) return a;
        return Math.max(a, b);
    }

    public float effective(LivingEntity entity, float raw, long arrival) {
        return damage(entity, raw, arrival, false);
    }

    public float effectiveOnSelf(float raw, long arrival) {
        return damage(mc.player, raw, arrival, true);
    }

    public long releaseTime(LivingEntity entity) {
        Window latest = window(entity, Long.MAX_VALUE);
        if (latest == null) return 0;
        return latest.start + window() + MARGIN - LatencyManager.get().rtt() / 2;
    }

    private float damage(LivingEntity entity, float raw, long arrival, boolean self) {
        float full = ExplosionDamage.mitigate(entity, raw);

        Window current = window(entity, arrival);
        if (current == null) return full;

        boolean immune = self
            ? arrival < current.start + SELF_WINDOW - tick() - MARGIN - LatencyManager.get().jitter()
            : arrival < current.start + window() + MARGIN;
        if (!immune) return full;

        if (Float.isNaN(current.raw)) return self || !current.explosion ? full : 0;
        if (raw <= current.raw) return 0;
        return ExplosionDamage.mitigate(entity, raw - current.raw);
    }

    private Window window(LivingEntity entity, long arrival) {
        int id = entity.getId();
        long now = Util.getMeasuringTimeMs();
        Window best = null;

        Hurt hurt = hurts.get(id);
        if (hurt != null && hurt.start <= arrival) best = new Window(hurt.start, Float.isNaN(hurt.raw) ? Float.NaN : max(hurt.raw, hurt.speculative), hurt.explosion);

        Expected pending = freshExpected(id, now);
        if (pending != null) {
            long start = expectedStart(pending);
            if (start <= arrival && (best == null || start > best.start)) best = new Window(start, max(pending.amount, pending.speculative), true);
        }

        return best;
    }

    private Expected freshExpected(int id, long now) {
        Expected pending = expected.get(id);
        return pending != null && now - pending.time <= LatencyManager.get().timeout() ? pending : null;
    }

    private static long expectedStart(Expected pending) {
        return pending.time + LatencyManager.get().rtt() / 2;
    }

    @EventHandler
    private void onReceive(PacketEvent.Receive event) {
        if (!(event.packet instanceof EntityDamageS2CPacket) && !(event.packet instanceof ExplosionS2CPacket)) return;

        synchronized (received) {
            if (received.size() >= RECEIVED_LIMIT) received.clear();
            received.put(event.packet, Util.getMeasuringTimeMs());
        }
    }

    private long receivedAt(Packet<?> packet, long now) {
        Long time = received.remove(packet);
        return time != null && time <= now ? time : now;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    private void onApply(PacketApplyEvent.Post event) {
        switch (event.packet) {
            case EntityDamageS2CPacket packet -> onDamage(packet);
            case ExplosionS2CPacket packet -> onExplosion(packet);
            default -> {}
        }
    }

    private void onDamage(EntityDamageS2CPacket packet) {
        long now = Util.getMeasuringTimeMs();
        long time = receivedAt(packet, now);

        Expected cause = expected.remove(packet.entityId());
        boolean fresh = cause != null && now - cause.time <= LatencyManager.get().timeout();
        float amount = fresh ? cause.amount : Float.NaN;

        amount = max(amount, crystalSource(packet, time));
        if (Float.isNaN(amount)) amount = fixedAmount(packet);

        boolean explosion = packet.sourceType().isIn(DamageTypeTags.IS_EXPLOSION);
        Hurt hurt = new Hurt(time - LatencyManager.get().rtt() / 2, time, explosion, amount);
        if (fresh) hurt.speculative = cause.speculative;
        hurts.put(packet.entityId(), hurt);
    }

    private float fixedAmount(EntityDamageS2CPacket packet) {
        RegistryEntry<DamageType> type = packet.sourceType();
        if (type.matchesKey(DamageTypes.ENDER_PEARL)) return PEARL_DAMAGE;
        if (mc.player != null && packet.entityId() == mc.player.getId()) return Float.NaN;

        if (type.matchesKey(DamageTypes.LAVA)) return 4.0f;
        if (type.matchesKey(DamageTypes.IN_FIRE) || type.matchesKey(DamageTypes.CAMPFIRE) || type.matchesKey(DamageTypes.DROWN)) return 2.0f;
        if (type.matchesKey(DamageTypes.ON_FIRE) || type.matchesKey(DamageTypes.HOT_FLOOR) || type.matchesKey(DamageTypes.CACTUS)
            || type.matchesKey(DamageTypes.SWEET_BERRY_BUSH) || type.matchesKey(DamageTypes.STARVE) || type.matchesKey(DamageTypes.IN_WALL)) return 1.0f;
        return Float.NaN;
    }

    private void onExplosion(ExplosionS2CPacket packet) {
        long now = receivedAt(packet, Util.getMeasuringTimeMs());
        if (mc.world == null || ExplosionDamage.isWind(packet)) return;

        long happened = now - LatencyManager.get().rtt() / 2;

        for (Int2ObjectMap.Entry<Hurt> entry : hurts.int2ObjectEntrySet()) {
            Hurt hurt = entry.getValue();
            if (mc.player != null && entry.getIntKey() == mc.player.getId()) continue;
            if (happened < hurt.start || happened >= hurt.start + window() + MARGIN) continue;
            if (!(mc.world.getEntityById(entry.getIntKey()) instanceof LivingEntity living)) continue;

            float raw = ExplosionDamage.raw(living, position(living), box(living), packet.center(), packet.radius(), mc.world);
            if (raw <= 0) continue;

            if (!Float.isNaN(hurt.raw)) hurt.raw = Math.max(hurt.raw, raw);
            else if (hurt.explosion && now - hurt.received <= LATE_EXPLOSION) hurt.raw = raw;
        }
    }

    private float crystalSource(EntityDamageS2CPacket packet, long time) {
        if (mc.world == null || !(mc.world.getEntityById(packet.entityId()) instanceof LivingEntity living)) return Float.NaN;

        Vec3d crystal = CrystalTracker.get().removedPosition(packet.sourceDirectId());
        if (crystal == null) return Float.NaN;

        float raw = ExplosionDamage.rawUnshielded(living, position(living), box(living), crystal, ExplosionDamage.CRYSTAL_POWER, mc.world);
        if (living != mc.player) return raw;

        MotionTracker.Sample past = MotionTracker.get().selfAt(time - LatencyManager.get().rtt());
        if (past == null) return raw;
        return Math.min(raw, ExplosionDamage.rawUnshielded(living, past.position(), past.box(), crystal, ExplosionDamage.CRYSTAL_POWER, mc.world));
    }

    private static Vec3d position(LivingEntity entity) {
        return entity instanceof PlayerEntity player ? MotionTracker.get().position(player) : entity.getEntityPos();
    }

    private static Box box(LivingEntity entity) {
        return entity instanceof PlayerEntity player ? Targets.box(player) : entity.getBoundingBox();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        long now = Util.getMeasuringTimeMs();
        hurts.values().removeIf(hurt -> now - hurt.start > LIFETIME);
        expected.values().removeIf(cause -> now - cause.time > LIFETIME);
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
        hurts.clear();
        expected.clear();
        received.clear();
    }

    private static long tick() {
        float rate = TickRate.INSTANCE.getTickRate();
        if (!(rate > 0)) rate = 20.0f;
        return (long) (1000 / Math.min(rate, 20.0f));
    }

    private static long window() {
        float rate = TickRate.INSTANCE.getTickRate();
        if (!(rate > 0)) rate = 20.0f;
        return (long) (IMMUNITY_TICKS * 1000 / Math.min(rate, 20.0f));
    }
}
