package dev.coyduck.managers;

import dev.coyduck.events.PacketApplyEvent;
import dev.coyduck.events.WorldResetEvent;
import dev.coyduck.utils.ExplosionDamage;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.utils.world.TickRate;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.entity.LivingEntity;
import net.minecraft.network.packet.s2c.play.EntityDamageS2CPacket;
import net.minecraft.util.Util;

public final class HurtTracker {
    private static final HurtTracker INSTANCE = new HurtTracker();

    private static final int IMMUNITY_TICKS = 10;
    private static final long MARGIN = 50;
    private static final long LIFETIME = 3_000;

    private static final class Hurt {
        final long start;
        float raw;

        Hurt(long start, float raw) {
            this.start = start;
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

        expected.put(entity.getId(), new Expected(raw, now));
    }

    public float effective(LivingEntity entity, float raw, long arrival) {
        Hurt hurt = hurts.get(entity.getId());
        if (hurt == null || arrival >= hurt.start + window() + MARGIN) return ExplosionDamage.mitigate(entity, raw);
        if (Float.isNaN(hurt.raw) || raw <= hurt.raw) return 0;
        return ExplosionDamage.mitigate(entity, raw - hurt.raw);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    private void onApply(PacketApplyEvent.Post event) {
        if (!(event.packet instanceof EntityDamageS2CPacket packet)) return;

        long now = Util.getMeasuringTimeMs();
        LatencyManager latency = LatencyManager.get();

        Expected cause = expected.remove(packet.entityId());
        float amount = cause != null && now - cause.time <= latency.timeout() ? cause.amount : Float.NaN;
        hurts.put(packet.entityId(), new Hurt(now - latency.rtt() / 2, amount));
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
