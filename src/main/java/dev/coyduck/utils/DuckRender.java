package dev.coyduck.utils;

import meteordevelopment.meteorclient.renderer.Renderer3D;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.renderer.text.TextRenderer;
import meteordevelopment.meteorclient.utils.render.NametagUtils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import net.minecraft.util.Util;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.joml.Vector3d;

/**
 * Coyduck's shared render look, after the logo's isometric cubes: the top face lit, the sides shading darker toward
 * the bottom, crisp edges. Motion runs on real time, so it looks the same at any frame rate.
 */
public final class DuckRender {
    public static final long HOLD = 300;
    public static final long FADE = 400;
    public static final long LIFETIME = HOLD + FADE;
    private static final double SLIDE = 18;

    private static final Color TOP = new Color();
    private static final Color UPPER = new Color();
    private static final Color LOWER = new Color();
    private static final Color EDGE = new Color();
    private static final Color TEXT = new Color();
    private static final double TEXT_SCALE = 1.0;

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
            renderer.boxLines(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ, EDGE, 0);
        }
    }

    /** Text centred over a point in the world, drawn from a Render2DEvent so it stays readable through walls. */
    public static void label(Vec3d at, String text, Color color, double alpha) {
        if (at == null || alpha <= 0.01) return;

        Vector3d pos = new Vector3d(at.x, at.y, at.z);
        if (!NametagUtils.to2D(pos, TEXT_SCALE)) return;

        TEXT.set(color);
        TEXT.a = (int) MathHelper.clamp(color.a * alpha, 0, 255);
        TextRenderer text2d = TextRenderer.get();
        NametagUtils.begin(pos);
        text2d.beginBig();
        text2d.render(text, -text2d.getWidth(text) / 2, -text2d.getHeight() / 2, TEXT, true);
        text2d.end();
        NametagUtils.end();
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
        private double shownAlpha;

        public void reset() {
            shown = null;
        }

        /** Where the box is drawn right now, for a label that follows it; null when nothing is shown. */
        public Vec3d center() {
            return shown == null ? null : shown.getCenter();
        }

        public double alpha() {
            return shown == null ? 0 : shownAlpha;
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
            if (shown == null || shown.getCenter().squaredDistanceTo(goal.getCenter()) > 64) shown = goal;
            double k = 1 - Math.exp(-SLIDE * dt);
            shown = new Box(
                shown.minX + (goal.minX - shown.minX) * k, shown.minY + (goal.minY - shown.minY) * k, shown.minZ + (goal.minZ - shown.minZ) * k,
                shown.maxX + (goal.maxX - shown.maxX) * k, shown.maxY + (goal.maxY - shown.maxY) * k, shown.maxZ + (goal.maxZ - shown.maxZ) * k
            );

            shownAlpha = DuckRender.alpha(age);
            box(renderer, shown, side, line, mode, shownAlpha);
            return true;
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
