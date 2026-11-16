package dev.coyduck;

import dev.coyduck.core.Bus;
import dev.coyduck.events.RenderScreenEvent;
import dev.coyduck.render.Argb;
import dev.coyduck.render.Overlay;
import meteordevelopment.meteorclient.events.render.Render2DEvent;
import meteordevelopment.meteorclient.renderer.Renderer2D;
import meteordevelopment.meteorclient.renderer.text.TextRenderer;
import meteordevelopment.meteorclient.systems.config.Config;
import meteordevelopment.meteorclient.utils.render.NametagUtils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.DrawContext;
import org.joml.Vector3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class MeteorOverlay implements Overlay {
    public static final MeteorOverlay BRIDGE = new MeteorOverlay();

    private enum Batch { NONE, RECTS, TEXT }

    private static final Logger LOG = LoggerFactory.getLogger("Coyduck");

    private final Color color = new Color();
    private final Vector3d pos = new Vector3d();
    private DrawContext context;
    private Batch batch = Batch.NONE;
    private int depth;
    private boolean reported;

    private MeteorOverlay() {}

    @EventHandler
    private void onRender(Render2DEvent event) {
        context = event.drawContext;
        MeteorGlow.renderAll();
        Bus.post(RenderScreenEvent.get(this, event.tickDelta));
        try {
            flush();
            while (depth > 0) unproject();
        } catch (RuntimeException e) {
            batch = Batch.NONE;
            depth = 0;
            if (!reported) LOG.error("Overlay cleanup failed", e);
            reported = true;
        }
        context = null;
    }

    @Override
    public DrawContext context() {
        flush();
        return context;
    }

    @Override
    public void flush() {
        batch(Batch.NONE);
    }

    @Override
    public boolean project(double x, double y, double z, double scale) {
        flush();
        while (depth > 0) unproject();
        pos.set(x, y, z);
        if (!NametagUtils.to2D(pos, scale)) return false;
        NametagUtils.begin(pos, context);
        depth++;
        return true;
    }

    @Override
    public void unproject() {
        flush();
        if (depth == 0) return;
        depth--;
        NametagUtils.end(context);
    }

    @Override
    public double textWidth(String text, boolean shadow) {
        batch(Batch.TEXT);
        return TextRenderer.get().getWidth(text, shadow);
    }

    @Override
    public double textHeight(boolean shadow) {
        batch(Batch.TEXT);
        return TextRenderer.get().getHeight(shadow);
    }

    @Override
    public double text(String text, double x, double y, int argb, boolean shadow) {
        batch(Batch.TEXT);
        return TextRenderer.get().render(text, x, y, color(argb), shadow);
    }

    @Override
    public void rect(double x, double y, double width, double height, int argb) {
        batch(Batch.RECTS);
        Renderer2D.COLOR.quad(x, y, width, height, color(argb));
    }

    @Override
    public boolean customFont() {
        return Config.get().customFont.get();
    }

    private void batch(Batch next) {
        if (batch == next) return;
        switch (batch) {
            case RECTS -> Renderer2D.COLOR.render();
            case TEXT -> TextRenderer.get().end();
            case NONE -> {}
        }
        batch = Batch.NONE;
        switch (next) {
            case RECTS -> Renderer2D.COLOR.begin();
            case TEXT -> TextRenderer.get().beginBig();
            case NONE -> {}
        }
        batch = next;
    }

    private Color color(int argb) {
        color.r = Argb.r(argb);
        color.g = Argb.g(argb);
        color.b = Argb.b(argb);
        color.a = Argb.a(argb);
        return color;
    }
}
