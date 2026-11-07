package dev.coyduck.utils;

import dev.coyduck.modules.Shaders;
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

public final class DuckRender {
    public static final long HOLD = 250;
    public static final long FADE = 300;
    public static final long LIFETIME = HOLD + FADE;
    private static final long FADE_IN = 50;
    private static final long SWITCH_HOLD = 120;
    private static final double SLIDE = 22;

    private static final Color TOP = new Color();
    private static final Color UPPER = new Color();
    private static final Color LOWER = new Color();
    private static final Color EDGE = new Color();

    private DuckRender() {}

    public static void box(Renderer3D renderer, Box box, Color side, Color line, ShapeMode mode, double alpha) {
        exact(renderer, box, Shaders.tint(side), Shaders.tint(line), mode, alpha);
    }

    public static void exact(Renderer3D renderer, Box box, Color side, Color line, ShapeMode mode, double alpha) {
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

    private static final Color LABEL = new Color();

    public static void label(Vec3d at, String text, Color color, double alpha) {
        if (at == null || alpha <= 0.01) return;
        Vector3d pos = new Vector3d(at.x, at.y, at.z);
        if (!NametagUtils.to2D(pos, 1.0)) return;
        LABEL.set(color);
        LABEL.a = (int) MathHelper.clamp(color.a * alpha, 0, 255);
        TextRenderer renderer = TextRenderer.get();
        NametagUtils.begin(pos);
        renderer.beginBig();
        renderer.render(text, -renderer.getWidth(text) / 2, -renderer.getHeight() / 2, LABEL, true);
        renderer.end();
        NametagUtils.end();
    }

    public static double alpha(long age) {
        if (age < HOLD) return 1;
        return 1 - ease((age - HOLD) / (double) FADE);
    }

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

    public static final class Slide {
        private BlockPos target;
        private long refreshedAt;
        private Box shown;
        private long appearedAt;
        private long lastFrame;
        private double shownAlpha;

        public Vec3d center() {
            return shown == null ? null : shown.getCenter();
        }

        public double alpha() {
            return shown == null ? 0 : shownAlpha;
        }

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
            shownAlpha = fadeIn * DuckRender.alpha(now - refreshedAt);
            box(renderer, shown, side, line, mode, shownAlpha);
        }
    }

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
