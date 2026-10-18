package dev.coyduck.managers;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayDeque;
import java.util.List;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public final class MotionTracker {
    private static final MotionTracker INSTANCE = new MotionTracker();

    private static final int SAMPLES = 4;
    private static final double GRAVITY = 0.08;
    private static final double DRAG = 0.98;

    private final Int2ObjectOpenHashMap<ArrayDeque<Vec3d>> history = new Int2ObjectOpenHashMap<>();

    private MotionTracker() {}

    public static MotionTracker get() {
        return INSTANCE;
    }

    public Vec3d position(PlayerEntity player) {
        return player == mc.player ? player.getEntityPos() : player.getTrackedPosition().getPos();
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

        for (int i = 0; i < ticks; i++) {
            Vec3d moved = Entity.adjustMovementForCollisions(player, velocity, box, mc.world, List.of());
            box = box.offset(moved);

            boolean landed = velocity.y < 0 && moved.y > velocity.y;
            velocity = new Vec3d(velocity.x, landed ? 0 : (velocity.y - GRAVITY) * DRAG, velocity.z);
        }

        return box;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.world == null) return;

        IntSet seen = new IntOpenHashSet();
        for (PlayerEntity player : mc.world.getPlayers()) {
            if (player == mc.player) continue;

            seen.add(player.getId());
            ArrayDeque<Vec3d> samples = history.computeIfAbsent(player.getId(), id -> new ArrayDeque<>());
            samples.addLast(player.getTrackedPosition().getPos());
            while (samples.size() > SAMPLES + 1) samples.removeFirst();
        }

        history.keySet().removeIf(id -> !seen.contains(id));
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        history.clear();
    }
}
