package dev.coyduck.utils;

import dev.coyduck.modules.CustomFov;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import meteordevelopment.meteorclient.renderer.Renderer3D;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.utils.render.color.Color;
import net.minecraft.util.Util;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * Coyduck's shared render look, after the logo's isometric cubes: a lit top, each pair of sides its own shade growing
 * darker toward the bottom, crisp edges with a faint glow. Motion runs on real time, so it looks the same at any frame
 * rate.
 */
public final class DuckRender {
    public static final long HOLD = 300;
    public static final long FADE = 500;
    public static final long LIFETIME = HOLD + FADE;
    private static final long FADE_IN = 160;
    private static final long SWITCH_HOLD = 150;
    // How fast the sliding box follows, as a critically damped spring: it eases in and settles without overshoot.
    private static final double SPRING = 15;
    private static final double SPRING_STEP = 1 / 240.0;
    // A box grows in from slightly smaller and sinks back a little as it fades.
    private static final double APPEAR_SCALE = 0.9;
    private static final double FADE_SCALE = 0.94;
    private static final double GLOW_PIXELS = 5;
    private static final double GLOW_STRENGTH = 0.3;

    private static final Color TOP = new Color();
    private static final Color BOTTOM = new Color();
    private static final Color FRONT_UPPER = new Color();
    private static final Color FRONT_LOWER = new Color();
    private static final Color SIDE_UPPER = new Color();
    private static final Color SIDE_LOWER = new Color();
    private static final Color EDGE = new Color();
    private static final Color GLOW = new Color();
    private static final Color CLEAR = new Color();

    private DuckRender() {}

    public static void box(Renderer3D renderer, Box box, Color side, Color line, ShapeMode mode, double alpha) {
        if (alpha <= 0.01) return;

        if (mode != ShapeMode.Lines) {
            shade(TOP, side, 0.3, 1.15, alpha);
            shade(BOTTOM, side, -0.5, 0.75, alpha);
            shade(FRONT_UPPER, side, 0.05, 1, alpha);
            shade(FRONT_LOWER, side, -0.3, 0.8, alpha);
            shade(SIDE_UPPER, side, -0.12, 0.95, alpha);
            shade(SIDE_LOWER, side, -0.45, 0.75, alpha);

            renderer.quadHorizontal(box.minX, box.maxY, box.minZ, box.maxX, box.maxZ, TOP);
            renderer.quadHorizontal(box.minX, box.minY, box.minZ, box.maxX, box.maxZ, BOTTOM);
            renderer.gradientQuadVertical(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.minZ, FRONT_UPPER, FRONT_LOWER);
            renderer.gradientQuadVertical(box.minX, box.minY, box.maxZ, box.maxX, box.maxY, box.maxZ, FRONT_UPPER, FRONT_LOWER);
            renderer.gradientQuadVertical(box.minX, box.minY, box.minZ, box.minX, box.maxY, box.maxZ, SIDE_UPPER, SIDE_LOWER);
            renderer.gradientQuadVertical(box.maxX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ, SIDE_UPPER, SIDE_LOWER);
        }
        if (mode != ShapeMode.Sides) {
            shade(EDGE, line, 0, 1, alpha);
            renderer.boxLines(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ, EDGE, 0);
            glow(renderer, box, line, alpha);
        }
    }

    /** A soft band along each edge that fades out sideways, the same width on screen near and far. */
    private static void glow(Renderer3D renderer, Box box, Color line, double alpha) {
        shade(GLOW, line, 0.15, GLOW_STRENGTH, alpha);
        if (GLOW.a < 2) return;
        CLEAR.set(GLOW);
        CLEAR.a = 0;

        Vec3d camera = mc.gameRenderer.getCamera().getCameraPos();
        double fov = Math.toRadians(CustomFov.world(mc.options.getFov().getValue()));
        double perUnit = 2 * GLOW_PIXELS * Math.tan(fov / 2) / Math.max(mc.getWindow().getFramebufferHeight(), 1);

        double[] xs = {box.minX, box.maxX};
        double[] ys = {box.minY, box.maxY};
        double[] zs = {box.minZ, box.maxZ};
        for (double y : ys) for (double z : zs) band(renderer, camera, perUnit, new Vec3d(box.minX, y, z), new Vec3d(box.maxX, y, z));
        for (double x : xs) for (double z : zs) band(renderer, camera, perUnit, new Vec3d(x, box.minY, z), new Vec3d(x, box.maxY, z));
        for (double x : xs) for (double y : ys) band(renderer, camera, perUnit, new Vec3d(x, y, box.minZ), new Vec3d(x, y, box.maxZ));
    }

    private static void band(Renderer3D renderer, Vec3d camera, double perUnit, Vec3d a, Vec3d b) {
        Vec3d dir = b.subtract(a);
        Vec3d toA = a.subtract(camera);
        Vec3d toB = b.subtract(camera);
        Vec3d sideA = dir.crossProduct(toA);
        Vec3d sideB = dir.crossProduct(toB);
        if (sideA.lengthSquared() < 1.0E-8 || sideB.lengthSquared() < 1.0E-8) return;

        sideA = sideA.normalize().multiply(perUnit * toA.length());
        sideB = sideB.normalize().multiply(perUnit * toB.length());
        for (int sign = -1; sign <= 1; sign += 2) {
            renderer.quad(
                a.x, a.y, a.z,
                b.x, b.y, b.z,
                b.x + sideB.x * sign, b.y + sideB.y * sign, b.z + sideB.z * sign,
                a.x + sideA.x * sign, a.y + sideA.y * sign, a.z + sideA.z * sign,
                GLOW, CLEAR, CLEAR, GLOW
            );
        }
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

    /** The box at its animated size: growing in while it appears, sinking a little while it fades. */
    private static Box animate(Box box, double appear, double fade) {
        double scale = (APPEAR_SCALE + (1 - APPEAR_SCALE) * appear) * (FADE_SCALE + (1 - FADE_SCALE) * fade);
        if (scale >= 0.999) return box;
        return box.contract(box.getLengthX() * (1 - scale) / 2, box.getLengthY() * (1 - scale) / 2, box.getLengthZ() * (1 - scale) / 2);
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
        private Vec3d position;
        private Vec3d velocity = Vec3d.ZERO;
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
            position = null;
            velocity = Vec3d.ZERO;
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

            Vec3d goal = Vec3d.of(target);
            if (position == null || position.squaredDistanceTo(goal) > 64) {
                position = goal;
                velocity = Vec3d.ZERO;
                appearedAt = now;
            }
            for (double left = dt; left > 1.0E-6; left -= SPRING_STEP) {
                double step = Math.min(left, SPRING_STEP);
                Vec3d accel = goal.subtract(position).multiply(SPRING * SPRING).subtract(velocity.multiply(2 * SPRING));
                velocity = velocity.add(accel.multiply(step));
                position = position.add(velocity.multiply(step));
            }

            double appear = ease((now - appearedAt) / (double) FADE_IN);
            double fade = DuckRender.alpha(now - refreshedAt);
            Box shown = new Box(position.x, position.y, position.z, position.x + 1, position.y + 1, position.z + 1);
            box(renderer, animate(shown, appear, fade), side, line, mode, appear * fade);
        }
    }

    /** Boxes that fade in where blocks were placed and fade out on their own; placing on one again keeps it lit. */
    public static final class Fading {
        private final Long2ObjectOpenHashMap<long[]> entries = new Long2ObjectOpenHashMap<>();

        public void show(BlockPos pos) {
            long now = Util.getMeasuringTimeMs();
            long[] entry = entries.get(pos.asLong());
            if (entry == null || now - entry[1] > LIFETIME) {
                entries.put(pos.asLong(), new long[] {now, now});
                return;
            }
            // Lit again while fading: rise back from the current brightness instead of flashing to full.
            double current = alpha(now - entry[1]);
            if (current < 1) entry[0] = now - (long) (FADE_IN * current);
            entry[1] = now;
        }

        public void clear() {
            entries.clear();
        }

        public void render(Renderer3D renderer, Color side, Color line, ShapeMode mode) {
            long now = Util.getMeasuringTimeMs();
            entries.long2ObjectEntrySet().removeIf(entry -> now - entry.getValue()[1] > LIFETIME);
            for (Long2ObjectMap.Entry<long[]> entry : entries.long2ObjectEntrySet()) {
                double appear = ease((now - entry.getValue()[0]) / (double) FADE_IN);
                double fade = alpha(now - entry.getValue()[1]);
                box(renderer, animate(new Box(BlockPos.fromLong(entry.getLongKey())), appear, fade), side, line, mode, appear * fade);
            }
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
