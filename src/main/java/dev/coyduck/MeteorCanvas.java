package dev.coyduck;

import dev.coyduck.core.Bus;
import dev.coyduck.events.RenderWorldEvent;
import dev.coyduck.render.Argb;
import dev.coyduck.render.Canvas;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.renderer.Renderer3D;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.orbit.EventHandler;

public final class MeteorCanvas implements Canvas {
    public static final Object BRIDGE = new Bridge();

    private final Color first = new Color();
    private final Color second = new Color();
    private Renderer3D renderer;

    private MeteorCanvas() {}

    private static final class Bridge {
        private final MeteorCanvas canvas = new MeteorCanvas();
        private final MeteorCanvas depth = new MeteorCanvas();

        @EventHandler
        private void onRender(Render3DEvent event) {
            canvas.renderer = event.renderer;
            depth.renderer = event.depthRenderer;
            Bus.post(RenderWorldEvent.get(canvas, depth, event.tickDelta));
            canvas.renderer = null;
            depth.renderer = null;
        }
    }

    @Override
    public void quad(double x1, double y1, double z1, double x2, double y2, double z2, double x3, double y3, double z3, double x4, double y4, double z4, int argb) {
        renderer.quad(x1, y1, z1, x2, y2, z2, x3, y3, z3, x4, y4, z4, color(first, argb));
    }

    @Override
    public void quadHorizontal(double x1, double y, double z1, double x2, double z2, int argb) {
        renderer.quadHorizontal(x1, y, z1, x2, z2, color(first, argb));
    }

    @Override
    public void gradientQuadVertical(double x1, double y1, double z1, double x2, double y2, double z2, int top, int bottom) {
        renderer.gradientQuadVertical(x1, y1, z1, x2, y2, z2, color(first, top), color(second, bottom));
    }

    @Override
    public void boxLines(double x1, double y1, double z1, double x2, double y2, double z2, int argb) {
        renderer.boxLines(x1, y1, z1, x2, y2, z2, color(first, argb), 0);
    }

    @Override
    public void line(double x1, double y1, double z1, double x2, double y2, double z2, int argb) {
        renderer.line(x1, y1, z1, x2, y2, z2, color(first, argb));
    }

    @Override
    public void line(double x1, double y1, double z1, double x2, double y2, double z2, int from, int to) {
        renderer.line(x1, y1, z1, x2, y2, z2, color(first, from), color(second, to));
    }

    private static Color color(Color into, int argb) {
        into.r = Argb.r(argb);
        into.g = Argb.g(argb);
        into.b = Argb.b(argb);
        into.a = Argb.a(argb);
        return into;
    }
}
