package dev.coyduck.managers;

import dev.coyduck.core.Core;
import dev.coyduck.events.GameLeftEvent;
import dev.coyduck.events.PacketEvent;
import dev.coyduck.events.TickEvent;
import dev.coyduck.events.WorldResetEvent;
import dev.coyduck.logic.YawRate;
import dev.coyduck.mixin.ClientPlayerEntityAccessor;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.Entity;
import net.minecraft.network.packet.c2s.play.PlayerInteractEntityC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractItemC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.util.PlayerInput;
import net.minecraft.util.Util;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec2f;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import static dev.coyduck.core.Core.mc;

public final class RotationManager {
    private static final RotationManager INSTANCE = new RotationManager();

    private static final int KEEP_TICKS = 5;

    private record Request(int priority, float yaw, float pitch, float speed, boolean moveFix) {}

    private record Deferred(Vec3d target, int priority, Runnable action, int expires) {}

    private static final int DEFER_TICKS = 3;
    private static final float DEFER_TOLERANCE = 1.0f;
    private static final float DEFER_SPEED = 180.0f;
    private static final long RETURN_MS = 50;

    private final ArrayList<Deferred> deferred = new ArrayList<>();
    private int ticks;

    private Request pending;
    private Request current;
    private int keep;

    private boolean active;
    private boolean moveFix;
    private float yaw;
    private float pitch;

    private boolean known;
    private float serverYaw;
    private float serverPitch;
    private final YawRate yawRate = new YawRate();
    private boolean held;
    private float heldYaw;
    private float heldPitch;
    private int heldTick;

    private RotationManager() {}

    public static RotationManager get() {
        return INSTANCE;
    }

    public void request(int priority, Vec3d target, float speed, boolean moveFix) {
        Vec3d eye = mc.player.getEyePos();
        request(priority, yawTo(eye, target), pitchTo(eye, target), speed, moveFix);
    }

    private static float yawTo(Vec3d eye, Vec3d target) {
        return (float) Math.toDegrees(Math.atan2(target.z - eye.z, target.x - eye.x)) - 90.0f;
    }

    private static float pitchTo(Vec3d eye, Vec3d target) {
        double dx = target.x - eye.x;
        double dz = target.z - eye.z;
        return MathHelper.clamp((float) -Math.toDegrees(Math.atan2(target.y - eye.y, Math.sqrt(dx * dx + dz * dz))), -90.0f, 90.0f);
    }

    public void request(int priority, float yaw, float pitch, float speed, boolean moveFix) {
        if (pending == null || priority >= pending.priority) pending = new Request(priority, yaw, pitch, speed, moveFix);
    }

    public boolean lookNow(Vec3d target, int priority) {
        if (mc.player == null || mc.getNetworkHandler() == null || Core.host().holdsMovement()) return false;
        if (Core.host().freezesRotation()) return true;
        if (current != null && current.priority > priority || pending != null && pending.priority > priority) return false;
        Vec3d eye = mc.player.getEyePos();
        float wantYaw = yawTo(eye, target);
        float wantPitch = pitchTo(eye, target);
        float base = known ? serverYaw : mc.player.getYaw();
        float basePitch = known ? serverPitch : mc.player.getPitch();
        float gcd = gcd();
        float deltaYaw = Math.round(MathHelper.wrapDegrees(wantYaw - base) / gcd) * gcd;
        float deltaPitch = Math.round((wantPitch - basePitch) / gcd) * gcd;
        if (deltaYaw == 0 && deltaPitch == 0) return true;
        float lookYaw = base + deltaYaw;
        float lookPitch = MathHelper.clamp(basePitch + deltaPitch, -90.0f, 90.0f);
        long now = Util.getMeasuringTimeMs();
        float back = active ? yaw : mc.player.getYaw();
        if (!yawRate.allows(lookYaw, back, now, now + RETURN_MS, LatencyManager.get().jitter())) return false;
        mc.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.LookAndOnGround(lookYaw, lookPitch, mc.player.isOnGround(), mc.player.horizontalCollision));
        held = true;
        heldTick = ticks;
        heldYaw = lookYaw;
        heldPitch = lookPitch;
        return true;
    }

    public boolean afterRotation(Vec3d target, int priority, Runnable action) {
        if (mc.player == null || mc.getNetworkHandler() == null || Core.host().holdsMovement()) return false;
        if (Core.host().freezesRotation()) {
            action.run();
            return true;
        }
        if (current != null && current.priority > priority || pending != null && pending.priority > priority) return false;
        request(priority, target, DEFER_SPEED, false);
        deferred.add(new Deferred(target, priority, action, ticks + DEFER_TICKS));
        return true;
    }

    public void afterMovement() {
        held = false;
        if (deferred.isEmpty()) return;
        float yawNow = serverYaw();
        float pitchNow = serverPitch();
        Vec3d eye = mc.player.getEyePos();
        Iterator<Deferred> iterator = deferred.iterator();
        List<Runnable> ready = new ArrayList<>();
        while (iterator.hasNext()) {
            Deferred entry = iterator.next();
            float yaw = yawTo(eye, entry.target);
            float pitch = pitchTo(eye, entry.target);
            if (Math.abs(MathHelper.wrapDegrees(yaw - yawNow)) <= DEFER_TOLERANCE && Math.abs(pitch - pitchNow) <= DEFER_TOLERANCE) {
                ready.add(entry.action);
                iterator.remove();
            } else if (ticks >= entry.expires) {
                iterator.remove();
            } else {
                request(entry.priority, yaw, pitch, DEFER_SPEED, false);
            }
        }
        for (Runnable action : ready) action.run();
    }

    public boolean isLookingAt(Box box, double range) {
        Vec3d eye = mc.player.getEyePos();
        if (box.contains(eye) || Core.host().freezesRotation()) return true;

        float yaw = known ? serverYaw : mc.player.getYaw();
        float pitch = known ? serverPitch : mc.player.getPitch();
        Vec3d end = eye.add(Vec3d.fromPolar(pitch, yaw).multiply(range));
        return box.raycast(eye, end).isPresent();
    }

    public float serverYaw() {
        return known ? serverYaw : mc.player.getYaw();
    }

    public float serverPitch() {
        return known ? serverPitch : mc.player.getPitch();
    }

    public float packetYaw(float original) {
        return held ? heldYaw : active ? yaw : original;
    }

    public float packetPitch(float original) {
        return held ? heldPitch : active ? pitch : original;
    }

    public float physicsYaw(Entity entity, float original) {
        return active && moveFix && entity == mc.player ? yaw : original;
    }

    public PlayerInput fixInput(PlayerInput input, Vec2f[] movement) {
        if (!active || !moveFix || mc.player == null) return input;

        Vec2f vector = movement[0];
        if (vector.lengthSquared() < 1.0E-7f) return input;

        double camera = Math.toRadians(mc.player.getYaw());
        double server = Math.toRadians(yaw);

        double wantX = vector.x * Math.cos(camera) - vector.y * Math.sin(camera);
        double wantZ = vector.y * Math.cos(camera) + vector.x * Math.sin(camera);

        int bestStrafe = 0;
        int bestForward = 0;
        double bestDot = -Double.MAX_VALUE;

        for (int strafe = -1; strafe <= 1; strafe++) {
            for (int forward = -1; forward <= 1; forward++) {
                if (strafe == 0 && forward == 0) continue;

                double length = Math.sqrt(strafe * strafe + forward * forward);
                double x = (strafe * Math.cos(server) - forward * Math.sin(server)) / length;
                double z = (forward * Math.cos(server) + strafe * Math.sin(server)) / length;
                double dot = x * wantX + z * wantZ;

                if (dot > bestDot) {
                    bestDot = dot;
                    bestStrafe = strafe;
                    bestForward = forward;
                }
            }
        }

        movement[0] = new Vec2f(bestStrafe, bestForward).normalize();
        return new PlayerInput(bestForward > 0, bestForward < 0, bestStrafe > 0, bestStrafe < 0, input.jump(), input.sneak(), input.sprint());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    private void onTick(TickEvent.Pre event) {
        ticks++;
        if (held && ticks - heldTick > 1) held = false;
        if (mc.player == null) {
            active = false;
            return;
        }

        if (!known) {
            serverYaw = mc.player.getYaw();
            serverPitch = mc.player.getPitch();
            known = true;
        }
        ((ClientPlayerEntityAccessor) mc.player).coyduck$setLastYawClient(serverYaw);
        ((ClientPlayerEntityAccessor) mc.player).coyduck$setLastPitchClient(serverPitch);

        if (pending != null) {
            current = pending;
            keep = KEEP_TICKS;
            pending = null;
        } else if (current != null && --keep <= 0) {
            current = null;
        }

        if (current == null) {
            active = false;
            return;
        }

        float deltaYaw = MathHelper.wrapDegrees(current.yaw - serverYaw);
        float deltaPitch = MathHelper.clamp(current.pitch, -90.0f, 90.0f) - serverPitch;

        if (current.speed < 180.0f) {
            deltaYaw = MathHelper.clamp(deltaYaw, -current.speed, current.speed);
            deltaPitch = MathHelper.clamp(deltaPitch, -current.speed, current.speed);
        }

        float gcd = gcd();
        deltaYaw = Math.round(deltaYaw / gcd) * gcd;
        deltaPitch = Math.round(deltaPitch / gcd) * gcd;

        yaw = serverYaw + deltaYaw;
        pitch = MathHelper.clamp(serverPitch + deltaPitch, -90.0f, 90.0f);
        moveFix = current.moveFix;
        active = true;
    }

    @EventHandler
    private void onSent(PacketEvent.Sent event) {
        if (event.packet instanceof PlayerMoveC2SPacket packet && packet.changesLook()) {
            serverYaw = packet.getYaw(serverYaw);
            yawRate.feed(serverYaw, Util.getMeasuringTimeMs(), LatencyManager.get().jitter());
            serverPitch = packet.getPitch(serverPitch);
            known = true;
        } else if (known && event.packet instanceof PlayerInteractEntityC2SPacket packet && FightTracker.isAttack(packet)) {
            yawRate.feed(serverYaw, Util.getMeasuringTimeMs(), LatencyManager.get().jitter());
        } else if (event.packet instanceof PlayerInteractItemC2SPacket packet) {
            serverYaw = packet.getYaw();
            yawRate.feed(serverYaw, Util.getMeasuringTimeMs(), LatencyManager.get().jitter());
            serverPitch = packet.getPitch();
            known = true;
            if (mc.player != null) {
                ((ClientPlayerEntityAccessor) mc.player).coyduck$setLastYawClient(packet.getYaw());
                ((ClientPlayerEntityAccessor) mc.player).coyduck$setLastPitchClient(packet.getPitch());
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    private void onSend(PacketEvent.Send event) {
        if (!(event.packet instanceof PlayerInteractItemC2SPacket packet) || !(held || active) || mc.player == null || !known) return;
        if (packet.getYaw() == serverYaw && packet.getPitch() == serverPitch || !mc.player.getStackInHand(packet.getHand()).contains(DataComponentTypes.CONSUMABLE)) return;
        event.cancel();
        mc.getNetworkHandler().sendPacket(new PlayerInteractItemC2SPacket(packet.getHand(), packet.getSequence(), serverYaw, serverPitch));
    }

    @EventHandler
    private void onWorldReset(WorldResetEvent event) {
        reset();
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        reset();
    }

    private void reset() {
        deferred.clear();
        pending = null;
        current = null;
        active = false;
        known = false;
        held = false;
    }

    private static float gcd() {
        double sensitivity = mc.options.getMouseSensitivity().getValue() * 0.6 + 0.2;
        return (float) (sensitivity * sensitivity * sensitivity * 8.0 * 0.15);
    }
}
