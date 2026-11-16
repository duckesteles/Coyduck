package dev.coyduck.events;

import dev.coyduck.render.Canvas;

public final class RenderWorldEvent {
    private static final RenderWorldEvent INSTANCE = new RenderWorldEvent();

    public Canvas canvas;
    public Canvas depth;
    public float tickDelta;

    private RenderWorldEvent() {}

    public static RenderWorldEvent get(Canvas canvas, Canvas depth, float tickDelta) {
        INSTANCE.canvas = canvas;
        INSTANCE.depth = depth;
        INSTANCE.tickDelta = tickDelta;
        return INSTANCE;
    }
}
