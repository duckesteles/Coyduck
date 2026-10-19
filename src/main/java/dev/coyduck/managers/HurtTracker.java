package dev.coyduck.managers;

import dev.coyduck.events.PacketApplyEvent;
import dev.coyduck.events.WorldResetEvent;
import dev.coyduck.utils.ExplosionDamage;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.utils.world.TickRate;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.entity.LivingEntity;
import net.minecraft.network.packet.s2c.play.EntityDamageS2CPacket;
import net.minecraft.network.packet.s2c.play.ExplosionS2CPacket;
import net.minecraft.util.Util;
import net.minecraft.util.math.Vec3d;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public final class HurtTracker {
    private static final HurtTracker INSTANCE = new HurtTracker();

    private static final int IMMUNITY_TICKS = 10;
    private static final long MARGIN = 50;
    private static final long LIFETIME = 3_000;

    private static final long LATE_EXPLOSION = 100;

    private static final class Hurt {
        final long start;
        final long received;
        float raw;

        Hurt(long start, long received, float raw) {
            this.start = start;
            this.received = received;
            this.raw = raw;
        }
    }

    private record Expected(float amount, long time) {}

    private final Int2ObjectOpenHashMap<Hurt> hurts = new Int2ObjectOpenHashMap<>();
    private final Int2ObjectOpenHashMap<Expected> expected = new Int2ObjectOpenHashMap<>();

    private HurtTracker() {}

    public static HurtTracker get() {
        return INSTANCE;
    }

    public void expect(LivingEntity entity, float raw) {
        long now = Util.getMeasuringTimeMs();
        Hurt hurt = hurts.get(entity.getId());
        long arrival = now + LatencyManager.get().rtt() / 2;

        if (hurt != null && arrival < hurt.start + window()) {
            if (!Float.isNaN(hurt.raw) && raw > hurt.raw) hurt.raw = raw;
            return;
        }

        Expected pending = expected.get(entity.getId());
        if (pending != null && now - pending.time <= LatencyManager.get().timeout() && arrival < pending.time + LatencyManager.get().rtt() / 2 + window()) {
            if (raw > pending.amount) expected.put(entity.getId(), new Expected(raw, pending.time));
            return;
        }

        expected.put(entity.getId(), new Expected(raw, now));
    }

    public float effective(LivingEntity entity, float raw, long arrival) {
        return damage(entity, raw, arrival, false);
    }

    public float effectiveOnSelf(float raw, long arrival) {
        return damage(mc.player, raw, arrival, true);
    }

    public long releaseTime(LivingEntity entity) {
        long end = windowStart(entity);
        if (end == Long.MIN_VALUE) return 0;
        return end + window() + MARGIN - LatencyManager.get().rtt() / 2;
    }

    private float damage(LivingEntity entity, float raw, long arrival, boolean unknownIsFull) {
        long start = windowStart(entity);
        if (start == Long.MIN_VALUE || arrival >= start + window() + MARGIN) return ExplosionDamage.mitigate(entity, raw);

        float last = lastRaw(entity);
        if (Float.isNaN(last)) return unknownIsFull ? ExplosionDamage.mitigate(entity, raw) : 0;
        if (raw <= last) return 0;
        return ExplosionDamage.mitigate(entity, raw - last);
    }

    private long windowStart(LivingEntity entity) {
        long now = Util.getMeasuringTimeMs();
        Hurt hurt = hurts.get(entity.getId());
        if (hurt != null && now < hurt.start + window() + MARGIN) return hurt.start;

        Expected pending = expected.get(entity.getId());
        if (pending != null && now - pending.time <= LatencyManager.get().timeout()) return pending.time + LatencyManager.get().rtt() / 2;
        return Long.MIN_VALUE;
    }

    private float lastRaw(LivingEntity entity) {
        long now = Util.getMeasuringTimeMs();
        Hurt hurt = hurts.get(entity.getId());
        if (hurt != null && now < hurt.start + window() + MARGIN) return hurt.raw;

        Expected pending = expected.get(entity.getId());
        return pending == null ? Float.NaN : pending.amount;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    private void onApply(PacketApplyEvent.Post event) {
        if (!(event.packet instanceof EntityDamageS2CPacket packet)) return;

        long now = Util.getMeasuringTimeMs();
        LatencyManager latency = LatencyManager.get();

        Expected cause = expected.remove(packet.entityId());
        float amount = cause != null && now - cause.time <= latency.timeout() ? cause.amount : Float.NaN;

        float computed = crystalSource(packet);
        if (!Float.isNaN(computed)) amount = computed;

        hurts.put(packet.entityId(), new Hurt(now - latency.rtt() / 2, now, amount));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    private void onExplosion(PacketApplyEvent.Post event) {
        if (!(event.packet instanceof ExplosionS2CPacket packet) || mc.world == null) return;

        long now = Util.getMeasuringTimeMs();
        for (Int2ObjectMap.Entry<Hurt> entry : hurts.int2ObjectEntrySet()) {
            Hurt hurt = entry.getValue();
            if (!Float.isNaN(hurt.raw) || now - hurt.received > LATE_EXPLOSION) continue;
            if (!(mc.world.getEntityById(entry.getIntKey()) instanceof LivingEntity living)) continue;

            hurt.raw = ExplosionDamage.raw(living, living.getEntityPos(), living.getBoundingBox(), packet.center(), packet.radius(), mc.world);
        }
    }

    private float crystalSource(EntityDamageS2CPacket packet) {
        if (mc.world == null || !(mc.world.getEntityById(packet.entityId()) instanceof LivingEntity living)) return Float.NaN;

        Vec3d crystal = CrystalTracker.get().removedPosition(packet.sourceDirectId());
        if (crystal == null) return Float.NaN;
        return ExplosionDamage.crystalRaw(living, living.getEntityPos(), living.getBoundingBox(), crystal, mc.world);
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
    }

    private static long window() {
        float rate = Math.max(TickRate.INSTANCE.getTickRate(), 1.0f);
        return (long) (IMMUNITY_TICKS * 1000 / rate);
    }
}
