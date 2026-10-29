package dev.coyduck.utils;

import meteordevelopment.meteorclient.renderer.Renderer3D;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.utils.render.color.Color;
import net.minecraft.util.Util;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;

/**
 * Coyduck's shared render look, after the logo's isometric cubes: the top face lit, the sides shading darker toward
 * the bottom, crisp edges. Motion runs on real time, so it looks the same at any frame rate.
 */
public final class DuckRender {
    public static final long HOLD = 300;
    public static final long FADE = 400;
    public static final long LIFETIME = HOLD + FADE;
    private static final double SLIDE = 18;
    private static final double GLOW = 0.03;
    private static final long POP = 180;
    private static final double POP_FROM = 0.6;

    private static final Color TOP = new Color();
    private static final Color UPPER = new Color();
    private static final Color LOWER = new Color();
    private static final Color EDGE = new Color();

    private DuckRender() {}

    public static void box(Renderer3D renderer, Box box, Color side, Color line, ShapeMode mode, double alpha) {
        if (alpha <= 0.01) return;

        if (mode != ShapeMode.Lines) {
            shade(TOP, side, 0.45, 2.2, alpha);
            shade(UPPER, side, 0, 1.5, alpha);
            shade(LOWER, side, -0.5, 0.35, alpha);

            renderer.quadHorizontal(box.minX, box.maxY, box.minZ, box.maxX, box.maxZ, TOP);
            renderer.quadHorizontal(box.minX, box.minY, box.minZ, box.maxX, box.maxZ, LOWER);
            renderer.gradientQuadVertical(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.minZ, UPPER, LOWER);
            renderer.gradientQuadVertical(box.minX, box.minY, box.maxZ, box.maxX, box.maxY, box.maxZ, UPPER, LOWER);
            renderer.gradientQuadVertical(box.minX, box.minY, box.minZ, box.minX, box.maxY, box.maxZ, UPPER, LOWER);
            renderer.gradientQuadVertical(box.maxX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ, UPPER, LOWER);
        }
        if (mode != ShapeMode.Sides) {
            shade(EDGE, line, 0, 1, alpha);
            renderer.boxLines(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ, EDGE, 0);
            // A faint second outline just outside reads as a glow around the edges.
            shade(EDGE, line, 0.2, 0.35, alpha);
            renderer.boxLines(box.minX - GLOW, box.minY - GLOW, box.minZ - GLOW, box.maxX + GLOW, box.maxY + GLOW, box.maxZ + GLOW, EDGE, 0);
        }
    }

    /** Full while fresh, then fades out. */
    public static double alpha(long age) {
        if (age < HOLD) return 1;
        return Math.max(0, 1 - (age - HOLD) / (double) FADE);
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

    /** One box that slides from spot to spot and fades out once the module stops refreshing it. */
    public static final class Slide {
        private Box shown;
        private long lastFrame;
        private long appearedAt;

        public void reset() {
            shown = null;
        }

        /** Returns false once the box has faded out completely. */
        public boolean render(Renderer3D renderer, BlockPos target, long setAt, Color side, Color line, ShapeMode mode) {
            long now = System.nanoTime();
            double dt = lastFrame == 0 ? 0 : Math.min((now - lastFrame) / 1.0E9, 0.1);
            lastFrame = now;

            if (target == null) {
                shown = null;
                return false;
            }

            long age = Util.getMeasuringTimeMs() - setAt;
            if (age > LIFETIME) {
                shown = null;
                return false;
            }

            Box goal = new Box(target);
            if (shown == null || shown.getCenter().squaredDistanceTo(goal.getCenter()) > 64) {
                shown = goal;
                appearedAt = Util.getMeasuringTimeMs();
            }
            double k = 1 - Math.exp(-SLIDE * dt);
            shown = new Box(
                shown.minX + (goal.minX - shown.minX) * k, shown.minY + (goal.minY - shown.minY) * k, shown.minZ + (goal.minZ - shown.minZ) * k,
                shown.maxX + (goal.maxX - shown.maxX) * k, shown.maxY + (goal.maxY - shown.maxY) * k, shown.maxZ + (goal.maxZ - shown.maxZ) * k
            );

            box(renderer, pop(shown, Util.getMeasuringTimeMs() - appearedAt), side, line, mode, DuckRender.alpha(age));
            return true;
        }

        /** A new box springs open from a smaller size with a slight overshoot. */
        private static Box pop(Box box, long age) {
            if (age >= POP) return box;
            double x = age / (double) POP - 1;
            double eased = 1 + 2.70158 * x * x * x + 1.70158 * x * x;
            double scale = POP_FROM + (1 - POP_FROM) * eased;
            return box.contract(box.getLengthX() * (1 - scale) / 2, box.getLengthY() * (1 - scale) / 2, box.getLengthZ() * (1 - scale) / 2);
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
