package dev.coyduck.utils;

import meteordevelopment.meteorclient.renderer.Renderer3D;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import dev.coyduck.modules.CustomFov;
import meteordevelopment.meteorclient.utils.render.color.Color;
import net.minecraft.util.Util;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * Coyduck's shared render look, after the logo's isometric cubes: the top face lit, the sides shading darker toward
 * the bottom, crisp edges. Motion runs on real time, so it looks the same at any frame rate.
 */
public final class DuckRender {
    public static final long HOLD = 300;
    public static final long FADE = 500;
    public static final long LIFETIME = HOLD + FADE;
    private static final long FADE_IN = 120;
    private static final long SWITCH_HOLD = 150;
    private static final double SLIDE = 12;
    // Edge thickness on screen. Vanilla GL lines are a hairline at any distance, which reads as cheap.
    private static final double EDGE_PIXELS = 2.2;

    private static final Color TOP = new Color();
    private static final Color UPPER = new Color();
    private static final Color LOWER = new Color();
    private static final Color EDGE = new Color();

    private DuckRender() {}

    public static void box(Renderer3D renderer, Box box, Color side, Color line, ShapeMode mode, double alpha) {
        if (alpha <= 0.01) return;

        if (mode != ShapeMode.Lines) {
            shade(TOP, side, 0.35, 1.15, alpha);
            shade(UPPER, side, 0, 1, alpha);
            shade(LOWER, side, -0.45, 0.7, alpha);

            renderer.quadHorizontal(box.minX, box.maxY, box.minZ, box.maxX, box.maxZ, TOP);
            renderer.quadHorizontal(box.minX, box.minY, box.minZ, box.maxX, box.maxZ, LOWER);
            renderer.gradientQuadVertical(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.minZ, UPPER, LOWER);
            renderer.gradientQuadVertical(box.minX, box.minY, box.maxZ, box.maxX, box.maxY, box.maxZ, UPPER, LOWER);
            renderer.gradientQuadVertical(box.minX, box.minY, box.minZ, box.minX, box.maxY, box.maxZ, UPPER, LOWER);
            renderer.gradientQuadVertical(box.maxX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ, UPPER, LOWER);
        }
        if (mode != ShapeMode.Sides) {
            shade(EDGE, line, 0, 1, alpha);
            edges(renderer, box, EDGE);
        }
    }

    /** The twelve edges as thin strips turned toward the camera, the same width on screen near and far. */
    private static void edges(Renderer3D renderer, Box box, Color color) {
        Vec3d camera = mc.gameRenderer.getCamera().getCameraPos();
        double fov = Math.toRadians(CustomFov.world(mc.options.getFov().getValue()));
        double perUnit = EDGE_PIXELS * Math.tan(fov / 2) / Math.max(mc.getWindow().getFramebufferHeight(), 1);

        double[] xs = {box.minX, box.maxX};
        double[] ys = {box.minY, box.maxY};
        double[] zs = {box.minZ, box.maxZ};
        for (double y : ys) for (double z : zs) edge(renderer, camera, perUnit, new Vec3d(box.minX, y, z), new Vec3d(box.maxX, y, z), color);
        for (double x : xs) for (double z : zs) edge(renderer, camera, perUnit, new Vec3d(x, box.minY, z), new Vec3d(x, box.maxY, z), color);
        for (double x : xs) for (double y : ys) edge(renderer, camera, perUnit, new Vec3d(x, y, box.minZ), new Vec3d(x, y, box.maxZ), color);
    }

    private static void edge(Renderer3D renderer, Vec3d camera, double perUnit, Vec3d a, Vec3d b, Color color) {
        Vec3d dir = b.subtract(a).normalize();
        Vec3d toA = a.subtract(camera);
        Vec3d toB = b.subtract(camera);
        Vec3d sideA = dir.crossProduct(toA);
        Vec3d sideB = dir.crossProduct(toB);
        if (sideA.lengthSquared() < 1.0E-8 || sideB.lengthSquared() < 1.0E-8) return;

        double halfA = perUnit * toA.length();
        double halfB = perUnit * toB.length();
        sideA = sideA.normalize().multiply(halfA);
        sideB = sideB.normalize().multiply(halfB);
        // Run each strip a half width past its corners so neighbouring edges meet without a notch.
        a = a.subtract(dir.multiply(halfA));
        b = b.add(dir.multiply(halfB));

        renderer.quad(
            a.x - sideA.x, a.y - sideA.y, a.z - sideA.z,
            a.x + sideA.x, a.y + sideA.y, a.z + sideA.z,
            b.x + sideB.x, b.y + sideB.y, b.z + sideB.z,
            b.x - sideB.x, b.y - sideB.y, b.z - sideB.z,
            color
        );
    }

    /** Full while fresh, then eases out. */
    public static double alpha(long age) {
        if (age < HOLD) return 1;
        return 1 - ease((age - HOLD) / (double) FADE);
    }

    /** Smoothstep: starts and ends gently, so fades and motion have no hard edges. */
    private static double ease(double t) {
        t = MathHelper.clamp(t, 0, 1);
        return t * t * (3 - 2 * t);
    }

    public static Color lerp(Color into, Color from, Color to, double t) {
        t = MathHelper.clamp(t, 0, 1);
        into.r = (int) Math.round(from.r + (to.r - from.r) * t);
        into.g = (int) Math.round(from.g + (to.g - from.g) * t);
        into.b = (int) Math.round(from.b + (to.b - from.b) * t);
        into.a = (int) Math.round(from.a + (to.a - from.a) * t);
        return into;
    }

    /** light > 0 mixes toward white, light < 0 toward black; alphaScale changes the face's opacity. */
    private static void shade(Color into, Color base, double light, double alphaScale, double alpha) {
        double r = base.r;
        double g = base.g;
        double b = base.b;
        if (light > 0) {
            r += (255 - r) * light;
            g += (255 - g) * light;
            b += (255 - b) * light;
        } else if (light < 0) {
            r *= 1 + light;
            g *= 1 + light;
            b *= 1 + light;
        }
        into.r = (int) r;
        into.g = (int) g;
        into.b = (int) b;
        into.a = (int) MathHelper.clamp(base.a * alphaScale * alpha, 0, 255);
    }

    /** One box that glides from spot to spot, fades in when it appears and fades out once nothing refreshes it. */
    public static final class Slide {
        private BlockPos target;
        private long refreshedAt;
        private Box shown;
        private long appearedAt;
        private long lastFrame;

        /** Points the box at a spot. A spot that flickers against the current one within a tick or two is ignored, so the box stays calm. */
        public void show(BlockPos pos) {
            long now = Util.getMeasuringTimeMs();
            if (target == null || target.equals(pos) || now - refreshedAt > SWITCH_HOLD) target = pos.toImmutable();
            if (target.equals(pos)) refreshedAt = now;
        }

        public void reset() {
            target = null;
            shown = null;
        }

        public void render(Renderer3D renderer, Color side, Color line, ShapeMode mode) {
            long frame = System.nanoTime();
            double dt = lastFrame == 0 ? 0 : Math.min((frame - lastFrame) / 1.0E9, 0.1);
            lastFrame = frame;

            long now = Util.getMeasuringTimeMs();
            if (target == null || now - refreshedAt > LIFETIME) {
                reset();
                return;
            }

            Box goal = new Box(target);
            if (shown == null || shown.getCenter().squaredDistanceTo(goal.getCenter()) > 64) {
                shown = goal;
                appearedAt = now;
            }
            double k = 1 - Math.exp(-SLIDE * dt);
            shown = new Box(
                shown.minX + (goal.minX - shown.minX) * k, shown.minY + (goal.minY - shown.minY) * k, shown.minZ + (goal.minZ - shown.minZ) * k,
                shown.maxX + (goal.maxX - shown.maxX) * k, shown.maxY + (goal.maxY - shown.maxY) * k, shown.maxZ + (goal.maxZ - shown.maxZ) * k
            );

            double fadeIn = ease((now - appearedAt) / (double) FADE_IN);
            box(renderer, shown, side, line, mode, fadeIn * DuckRender.alpha(now - refreshedAt));
        }
    }

    /** Smooths a value that changes once per tick, such as mining progress, across frames. */
    public static final class Smooth {
        private double value;
        private long lastFrame;

        public double toward(double target, double speed) {
            long now = System.nanoTime();
            double dt = lastFrame == 0 ? 1 : Math.min((now - lastFrame) / 1.0E9, 0.1);
            lastFrame = now;
            if (target < value) value = target;
            else value += (target - value) * (1 - Math.exp(-speed * dt));
            return value;
        }
    }
}
