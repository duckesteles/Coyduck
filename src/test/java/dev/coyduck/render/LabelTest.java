package dev.coyduck.render;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LabelTest {
    static final class Recorder implements Overlay {
        final List<String> calls = new ArrayList<>();
        boolean visible = true;
        int depth;
        double textX;
        double textY;
        int textColor;

        @Override
        public DrawContext context() {
            calls.add("context");
            return null;
        }

        @Override
        public void flush() {
            calls.add("flush");
        }

        @Override
        public boolean project(double x, double y, double z, double scale) {
            calls.add("project");
            if (visible) depth++;
            return visible;
        }

        @Override
        public void unproject() {
            calls.add("unproject");
            depth--;
        }

        @Override
        public double textWidth(String text, boolean shadow) {
            return text.length() * 6 + (shadow ? 2 : 0);
        }

        @Override
        public double textHeight(boolean shadow) {
            return shadow ? 11 : 9;
        }

        @Override
        public double text(String text, double x, double y, int argb, boolean shadow) {
            calls.add("text");
            textX = x;
            textY = y;
            textColor = argb;
            return x + textWidth(text, shadow);
        }

        @Override
        public void rect(double x, double y, double width, double height, int argb) {
            calls.add("rect");
        }

        @Override
        public boolean customFont() {
            return false;
        }
    }

    @Test
    void labelIsCenteredAndFaded() {
        Recorder overlay = new Recorder();
        DuckRender.label(overlay, Vec3d.ZERO, "12.5", 0xFFFFFFFF, 0.5);
        assertEquals(List.of("project", "text", "unproject"), overlay.calls);
        assertEquals(-12, overlay.textX);
        assertEquals(-4.5, overlay.textY);
        assertEquals(0x7FFFFFFF, overlay.textColor);
        assertEquals(0, overlay.depth);
    }

    @Test
    void hiddenLabelsLeaveNoProjection() {
        Recorder offscreen = new Recorder();
        offscreen.visible = false;
        DuckRender.label(offscreen, Vec3d.ZERO, "9.0", 0xFFFFFFFF, 1);
        assertEquals(List.of("project"), offscreen.calls);
        assertEquals(0, offscreen.depth);

        Recorder faded = new Recorder();
        DuckRender.label(faded, Vec3d.ZERO, "9.0", 0xFFFFFFFF, 0);
        DuckRender.label(faded, null, "9.0", 0xFFFFFFFF, 1);
        assertTrue(faded.calls.isEmpty());
    }

    @Test
    void healthRampsBadWarnGood() {
        assertEquals(Palette.BAD, Palette.health(0));
        assertEquals(Palette.WARN, Palette.health(0.5));
        assertEquals(Palette.GOOD, Palette.health(1));
        assertEquals(Palette.GOOD, Palette.health(3));
    }
}
