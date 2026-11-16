package dev.coyduck.managers;

import dev.coyduck.events.GameLeftEvent;
import dev.coyduck.events.TickEvent;
import dev.coyduck.events.WorldResetEvent;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.Util;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayDeque;
import java.util.List;

import static dev.coyduck.core.Core.mc;

public final class MotionTracker {
    private static final MotionTracker INSTANCE = new MotionTracker();

    private static final int SAMPLES = 4;
    private static final double GRAVITY = 0.08;
    private static final double DRAG = 0.98;
    private static final double SPRINT_JUMP = 0.62;
    private static final double GROUND_FRICTION = 0.546;
    private static final double AIR_FRICTION = 0.91;
    private static final double MAX_GLIDE = 2.5;
    private static final double SELF_LEAD_SPEED = 0.25;
    private static final int MAX_SELF_LEAD = 6;
    private static final double TELEPORT = 4.0 * 4.0;
    private static final double GLIDING_TELEPORT = 10.0 * 10.0;

    private static final int SELF_SAMPLES = 40;

    public record Sample(long time, Vec3d position, Box box) {}

    private final Int2ObjectOpenHashMap<ArrayDeque<Vec3d>> history = new Int2ObjectOpenHashMap<>();
    private final ArrayDeque<Sample> self = new ArrayDeque<>();

    private MotionTracker() {}

    public static MotionTracker get() {
        return INSTANCE;
    }

    public Vec3d position(PlayerEntity player) {
        if (player == mc.player || player.hasVehicle()) return player.getEntityPos();

        Vec3d tracked = player.getTrackedPosition().getPos();
        return tracked.equals(Vec3d.ZERO) ? player.getEntityPos() : tracked;
    }

    public Vec3d velocity(PlayerEntity player) {
        ArrayDeque<Vec3d> samples = history.get(player.getId());
        if (samples == null || samples.size() < 2) return Vec3d.ZERO;
        return samples.peekLast().subtract(samples.peekFirst()).multiply(1.0 / (samples.size() - 1));
    }

    public Box predict(PlayerEntity player, int ticks) {
        Vec3d position = position(player);
        Box box = player.getBoundingBox().offset(position.subtract(player.getEntityPos()));
        if (ticks <= 0) return box;

        Vec3d velocity = velocity(player);
        if (velocity.lengthSquared() < 1.0E-6) return box;
        boolean gliding = player.isGliding();
        if (gliding && velocity.length() > MAX_GLIDE) velocity = velocity.normalize().multiply(MAX_GLIDE);

        for (int i = 0; i < ticks; i++) {
            Vec3d moved = Entity.adjustMovementForCollisions(player, velocity, box, mc.world, List.of());
            box = box.offset(moved);

            if (gliding) continue;
            boolean landed = velocity.y < 0 && moved.y > velocity.y;
            double horizontal = Math.sqrt(velocity.x * velocity.x + velocity.z * velocity.z);
            double scale = horizontal <= SPRINT_JUMP ? 1 : (SPRINT_JUMP + (horizontal - SPRINT_JUMP) * (landed ? GROUND_FRICTION : AIR_FRICTION)) / horizontal;
            velocity = new Vec3d(velocity.x * scale, landed ? 0 : (velocity.y - GRAVITY) * DRAG, velocity.z * scale);
        }

        return box;
    }

    public Vec3d selfLead() {
        Vec3d velocity = mc.player.getVelocity();
        if (velocity.lengthSquared() <= SELF_LEAD_SPEED * SELF_LEAD_SPEED) return Vec3d.ZERO;
        int ticks = MathHelper.clamp(MathHelper.ceil(LatencyManager.get().rtt() / 50.0), 1, MAX_SELF_LEAD);
        return new Vec3d(velocity.x * ticks, mc.player.isOnGround() ? 0 : velocity.y * ticks, velocity.z * ticks);
    }

    public Sample selfAt(long time) {
        Sample best = null;
        for (Sample sample : self) {
            if (sample.time > time) break;
            best = sample;
        }
        return best;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.world == null) return;

        if (mc.player != null) {
            self.addLast(new Sample(Util.getMeasuringTimeMs(), mc.player.getEntityPos(), mc.player.getBoundingBox()));
            while (self.size() > SELF_SAMPLES) self.removeFirst();
        }

        IntSet seen = new IntOpenHashSet();
        for (PlayerEntity player : mc.world.getPlayers()) {
            if (player == mc.player) continue;

            seen.add(player.getId());
            ArrayDeque<Vec3d> samples = history.computeIfAbsent(player.getId(), id -> new ArrayDeque<>());
            Vec3d position = position(player);
            double limit = player.isGliding() ? GLIDING_TELEPORT : TELEPORT;
            if (!samples.isEmpty() && samples.peekLast().squaredDistanceTo(position) > limit) samples.clear();
            samples.addLast(position);
            while (samples.size() > SAMPLES + 1) samples.removeFirst();
        }

        history.keySet().removeIf(id -> !seen.contains(id));
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
        history.clear();
        self.clear();
    }
}
