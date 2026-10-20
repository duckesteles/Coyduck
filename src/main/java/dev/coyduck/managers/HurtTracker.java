package dev.coyduck.managers;

import dev.coyduck.events.PacketApplyEvent;
import dev.coyduck.events.WorldResetEvent;
import dev.coyduck.utils.ExplosionDamage;
import dev.coyduck.utils.Targets;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.utils.world.TickRate;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.packet.s2c.play.EntityDamageS2CPacket;
import net.minecraft.network.packet.s2c.play.ExplosionS2CPacket;
import net.minecraft.registry.tag.DamageTypeTags;
import net.minecraft.util.Util;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public final class HurtTracker {
    private static final HurtTracker INSTANCE = new HurtTracker();

    private static final int IMMUNITY_TICKS = 10;
    private static final long TICK = 50;
    private static final long MARGIN = 50;
    private static final long LIFETIME = 3_000;
    private static final long LATE_EXPLOSION = 100;

    private static final class Hurt {
        final long start;
        final long received;
        final boolean explosion;
        float raw;

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

        Expected(long time, float amount) {
            this.time = time;
            this.amount = amount;
        }
    }

    private record Window(long start, float raw) {}

    private final Int2ObjectOpenHashMap<Hurt> hurts = new Int2ObjectOpenHashMap<>();
    private final Int2ObjectOpenHashMap<Expected> expected = new Int2ObjectOpenHashMap<>();

    private HurtTracker() {}

    public static HurtTracker get() {
        return INSTANCE;
    }

    public void expect(LivingEntity entity, float raw) {
        long now = Util.getMeasuringTimeMs();
        long arrival = now + LatencyManager.get().rtt() / 2;
        int id = entity.getId();

        Expected pending = freshExpected(id, now);
        if (pending != null && arrival >= expectedStart(pending) && arrival < expectedStart(pending) + window()) {
            pending.amount = Math.max(pending.amount, raw);
            return;
        }

        Hurt hurt = hurts.get(id);
        if (hurt != null && arrival >= hurt.start && arrival < hurt.start + window()) {
            if (!Float.isNaN(hurt.raw)) hurt.raw = Math.max(hurt.raw, raw);
            return;
        }

        expected.put(id, new Expected(now, raw));
    }

    public void forgetExpected(LivingEntity entity, long since) {
        Expected pending = expected.get(entity.getId());
        if (pending != null && pending.time >= since) expected.remove(entity.getId());
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

        long end = current.start + window();
        boolean immune = self
            ? arrival < end - TICK - MARGIN - LatencyManager.get().jitter()
            : arrival < end + MARGIN;
        if (!immune) return full;

        if (Float.isNaN(current.raw)) return self ? full : 0;
        if (raw <= current.raw) return 0;
        return ExplosionDamage.mitigate(entity, raw - current.raw);
    }

    private Window window(LivingEntity entity, long arrival) {
        int id = entity.getId();
        long now = Util.getMeasuringTimeMs();
        Window best = null;

        Hurt hurt = hurts.get(id);
        if (hurt != null && hurt.start <= arrival) best = new Window(hurt.start, hurt.raw);

        Expected pending = freshExpected(id, now);
        if (pending != null) {
            long start = expectedStart(pending);
            if (start <= arrival && (best == null || start > best.start)) best = new Window(start, pending.amount);
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

        Expected cause = expected.remove(packet.entityId());
        float amount = cause != null && now - cause.time <= LatencyManager.get().timeout() ? cause.amount : Float.NaN;

        float computed = crystalSource(packet);
        if (!Float.isNaN(computed)) amount = Float.isNaN(amount) ? computed : Math.max(amount, computed);

        boolean explosion = packet.sourceType().isIn(DamageTypeTags.IS_EXPLOSION);
        hurts.put(packet.entityId(), new Hurt(now - LatencyManager.get().rtt() / 2, now, explosion, amount));
    }

    private void onExplosion(ExplosionS2CPacket packet) {
        if (mc.world == null) return;

        long now = Util.getMeasuringTimeMs();
        for (Int2ObjectMap.Entry<Hurt> entry : hurts.int2ObjectEntrySet()) {
            Hurt hurt = entry.getValue();
            if (!hurt.explosion || !Float.isNaN(hurt.raw) || now - hurt.received > LATE_EXPLOSION) continue;
            if (!(mc.world.getEntityById(entry.getIntKey()) instanceof LivingEntity living)) continue;

            float raw = ExplosionDamage.raw(living, position(living), box(living), packet.center(), packet.radius(), mc.world);
            if (raw > 0) hurt.raw = raw;
        }
    }

    private float crystalSource(EntityDamageS2CPacket packet) {
        if (mc.world == null || !(mc.world.getEntityById(packet.entityId()) instanceof LivingEntity living)) return Float.NaN;

        Vec3d crystal = CrystalTracker.get().removedPosition(packet.sourceDirectId());
        if (crystal == null) return Float.NaN;
        return ExplosionDamage.crystalRaw(living, position(living), box(living), crystal, mc.world);
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
    }

    private static long window() {
        float rate = TickRate.INSTANCE.getTickRate();
        if (!(rate > 0)) rate = 20.0f;
        return (long) (IMMUNITY_TICKS * 1000 / Math.min(rate, 20.0f));
    }
}
