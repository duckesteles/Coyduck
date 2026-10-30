package dev.coyduck.managers;

import net.minecraft.util.PlayerInput;
import net.minecraft.util.Util;
import net.minecraft.util.math.Vec2f;
import net.minecraft.util.math.Vec3d;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * Lets a module walk the player in a world direction without turning the camera: each tick it presses whichever of
 * the eight key combinations moves closest to that direction. It lapses on its own once the module stops asking.
 */
public final class MoveInput {
    private static final long FRESH = 120;

    private static Vec3d direction;
    private static boolean sneak;
    private static long at;

    private MoveInput() {}

    /** Walks toward a horizontal world direction this tick; a zero vector stands still. */
    public static void walk(Vec3d worldDirection, boolean sneaking) {
        direction = worldDirection;
        sneak = sneaking;
        at = Util.getMeasuringTimeMs();
    }

    public static void release() {
        direction = null;
    }

    /** The keys to press instead of the keyboard's, or null to leave the player's own input alone. */
    public static PlayerInput input() {
        if (direction == null || mc.player == null || Util.getMeasuringTimeMs() - at > FRESH) return null;
        if (direction.horizontalLengthSquared() < 1.0E-6) return new PlayerInput(false, false, false, false, false, sneak, false);

        double camera = Math.toRadians(mc.player.getYaw());
        double length = Math.sqrt(direction.horizontalLengthSquared());
        double wantX = direction.x / length;
        double wantZ = direction.z / length;

        int bestStrafe = 0;
        int bestForward = 0;
        double bestDot = -Double.MAX_VALUE;
        for (int strafe = -1; strafe <= 1; strafe++) {
            for (int forward = -1; forward <= 1; forward++) {
                if (strafe == 0 && forward == 0) continue;
                double norm = Math.sqrt(strafe * strafe + forward * forward);
                double x = (strafe * Math.cos(camera) - forward * Math.sin(camera)) / norm;
                double z = (forward * Math.cos(camera) + strafe * Math.sin(camera)) / norm;
                double dot = x * wantX + z * wantZ;
                if (dot > bestDot) {
                    bestDot = dot;
                    bestStrafe = strafe;
                    bestForward = forward;
                }
            }
        }
        return new PlayerInput(bestForward > 0, bestForward < 0, bestStrafe > 0, bestStrafe < 0, false, sneak, false);
    }

    public static Vec2f vector(PlayerInput input) {
        float x = (input.left() ? 1 : 0) - (input.right() ? 1 : 0);
        float y = (input.forward() ? 1 : 0) - (input.backward() ? 1 : 0);
        return new Vec2f(x, y).normalize();
    }
}
