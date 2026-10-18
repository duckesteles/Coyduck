package dev.coyduck.managers;

import dev.coyduck.events.PacketApplyEvent;
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

    private record Hurt(long start, float amount) {}

    private record Expected(float amount, long time) {}

    private final Int2ObjectOpenHashMap<Hurt> hurts = new Int2ObjectOpenHashMap<>();
    private final Int2ObjectOpenHashMap<Expected> expected = new Int2ObjectOpenHashMap<>();

    private HurtTracker() {}

    public static HurtTracker get() {
        return INSTANCE;
    }

    public void expect(LivingEntity entity, float damage) {
        expected.put(entity.getId(), new Expected(damage, Util.getMeasuringTimeMs()));
    }

    public float effective(LivingEntity entity, float damage, long arrival) {
        Hurt hurt = hurts.get(entity.getId());
        if (hurt == null || arrival >= hurt.start + window() + MARGIN) return damage;
        return Float.isNaN(hurt.amount) ? 0 : Math.max(0, damage - hurt.amount);
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
        hurts.clear();
        expected.clear();
    }

    private static long window() {
        float rate = Math.max(TickRate.INSTANCE.getTickRate(), 1.0f);
        return (long) (IMMUNITY_TICKS * 1000 / rate);
    }
}
