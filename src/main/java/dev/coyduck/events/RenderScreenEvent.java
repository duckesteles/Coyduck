package dev.coyduck.events;

import dev.coyduck.render.Overlay;

public final class RenderScreenEvent {
    private static final RenderScreenEvent INSTANCE = new RenderScreenEvent();

    public Overlay overlay;
    public float tickDelta;

    private RenderScreenEvent() {}

    public static RenderScreenEvent get(Overlay overlay, float tickDelta) {
        INSTANCE.overlay = overlay;
        INSTANCE.tickDelta = tickDelta;
        return INSTANCE;
    }
}
