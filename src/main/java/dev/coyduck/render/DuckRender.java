package dev.coyduck.render;

import dev.coyduck.core.Core;
import net.minecraft.util.Util;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

public final class DuckRender {
    public static final long HOLD = 250;
    public static final long FADE = 300;
    public static final long LIFETIME = HOLD + FADE;
    private static final long FADE_IN = 50;
    private static final long SWITCH_HOLD = 120;
    private static final double SLIDE = 22;
    private static final int PROGRESS_START = 0xE63C3C;
    private static final int PROGRESS_END = 0x50DC5A;

    private DuckRender() {}

    public static void box(Canvas canvas, Box box, int side, int line, Shape mode, double alpha) {
        exact(canvas, box, Core.host().tint(side), Core.host().tint(line), mode, alpha);
    }

    public static void exact(Canvas canvas, Box box, int side, int line, Shape mode, double alpha) {
        if (alpha <= 0.01) return;

        if (mode.sides()) {
            int top = Argb.shade(side, 0.35, 1.15, alpha);
            int upper = Argb.shade(side, 0, 1, alpha);
            int lower = Argb.shade(side, -0.45, 0.7, alpha);

            canvas.quadHorizontal(box.minX, box.maxY, box.minZ, box.maxX, box.maxZ, top);
            canvas.quadHorizontal(box.minX, box.minY, box.minZ, box.maxX, box.maxZ, lower);
            canvas.gradientQuadVertical(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.minZ, upper, lower);
            canvas.gradientQuadVertical(box.minX, box.minY, box.maxZ, box.maxX, box.maxY, box.maxZ, upper, lower);
            canvas.gradientQuadVertical(box.minX, box.minY, box.minZ, box.minX, box.maxY, box.maxZ, upper, lower);
            canvas.gradientQuadVertical(box.maxX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ, upper, lower);
        }
        if (mode.lines()) canvas.boxLines(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ, Argb.shade(line, 0, 1, alpha));
    }

    public static void label(Overlay overlay, Vec3d at, String text, int argb, double alpha) {
        if (at == null || alpha <= 0.01 || !overlay.project(at.x, at.y, at.z, 1.0)) return;
        double width = overlay.textWidth(text, false);
        double height = overlay.textHeight(false);
        overlay.text(text, -width / 2, -height / 2, Argb.alpha(argb, (int) (Argb.a(argb) * alpha)), true);
        overlay.unproject();
    }

    public static int progress(double t, int alphaOf) {
        int alpha = Argb.a(alphaOf);
        return Argb.lerp(Argb.alpha(PROGRESS_START, alpha), Argb.alpha(PROGRESS_END, alpha), t);
    }

    public static double alpha(long age) {
        if (age < HOLD) return 1;
        return 1 - ease((age - HOLD) / (double) FADE);
    }

    private static double ease(double t) {
        t = MathHelper.clamp(t, 0, 1);
        return t * t * (3 - 2 * t);
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

        public void render(Canvas canvas, int side, int line, Shape mode) {
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
            box(canvas, shown, side, line, mode, shownAlpha);
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
