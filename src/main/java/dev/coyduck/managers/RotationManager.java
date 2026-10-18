package dev.coyduck.managers;

import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.entity.Entity;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.util.PlayerInput;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec2f;
import net.minecraft.util.math.Vec3d;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public final class RotationManager {
    private static final RotationManager INSTANCE = new RotationManager();

    private static final int KEEP_TICKS = 5;

    private record Request(int priority, float yaw, float pitch, float speed, boolean moveFix) {}

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

    private RotationManager() {}

    public static RotationManager get() {
        return INSTANCE;
    }

    public void request(int priority, Vec3d target, float speed, boolean moveFix) {
        Vec3d eye = mc.player.getEyePos();
        double dx = target.x - eye.x;
        double dy = target.y - eye.y;
        double dz = target.z - eye.z;

        float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90.0f;
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        request(priority, yaw, pitch, speed, moveFix);
    }

    public void request(int priority, float yaw, float pitch, float speed, boolean moveFix) {
        if (pending == null || priority >= pending.priority) pending = new Request(priority, yaw, pitch, speed, moveFix);
    }

    public boolean isLookingAt(Box box, double range) {
        Vec3d eye = mc.player.getEyePos();
        if (box.contains(eye)) return true;

        float yaw = known ? serverYaw : mc.player.getYaw();
        float pitch = known ? serverPitch : mc.player.getPitch();
        Vec3d end = eye.add(Vec3d.fromPolar(pitch, yaw).multiply(range));
        return box.raycast(eye, end).isPresent();
    }

    public float packetYaw(float original) {
        return active ? yaw : original;
    }

    public float packetPitch(float original) {
        return active ? pitch : original;
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
        if (mc.player == null) {
            active = false;
            return;
        }

        if (!known) {
            serverYaw = mc.player.getYaw();
            serverPitch = mc.player.getPitch();
            known = true;
        }

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
    private void onSend(PacketEvent.Send event) {
        if (event.packet instanceof PlayerMoveC2SPacket packet && packet.changesLook()) {
            serverYaw = packet.getYaw(serverYaw);
            serverPitch = packet.getPitch(serverPitch);
            known = true;
        }
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        pending = null;
        current = null;
        active = false;
        known = false;
    }

    private static float gcd() {
        double sensitivity = mc.options.getMouseSensitivity().getValue() * 0.6 + 0.2;
        return (float) (sensitivity * sensitivity * sensitivity * 8.0 * 0.15);
    }
}
