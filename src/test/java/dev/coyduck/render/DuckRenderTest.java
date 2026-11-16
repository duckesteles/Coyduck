package dev.coyduck.render;

import net.minecraft.util.math.Box;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DuckRenderTest {
    static final class Recorder implements Canvas {
        final List<String> calls = new ArrayList<>();
        final List<Integer> colors = new ArrayList<>();

        @Override
        public void quad(double x1, double y1, double z1, double x2, double y2, double z2, double x3, double y3, double z3, double x4, double y4, double z4, int argb) {
            calls.add("quad");
            colors.add(argb);
        }

        @Override
        public void quadHorizontal(double x1, double y, double z1, double x2, double z2, int argb) {
            calls.add("horizontal");
            colors.add(argb);
        }

        @Override
        public void gradientQuadVertical(double x1, double y1, double z1, double x2, double y2, double z2, int top, int bottom) {
            calls.add("vertical");
            colors.add(top);
        }

        @Override
        public void boxLines(double x1, double y1, double z1, double x2, double y2, double z2, int argb) {
            calls.add("lines");
            colors.add(argb);
        }

        @Override
        public void line(double x1, double y1, double z1, double x2, double y2, double z2, int argb) {
            calls.add("line");
        }

        @Override
        public void line(double x1, double y1, double z1, double x2, double y2, double z2, int from, int to) {
            calls.add("line");
        }
    }

    private static final Box BOX = new Box(0, 0, 0, 1, 1, 1);
    private static final int SIDE = Argb.of(10, 20, 30, 100);
    private static final int LINE = Argb.of(40, 50, 60, 200);

    @Test
    void shapesPickTheirParts() {
        Recorder both = new Recorder();
        DuckRender.exact(both, BOX, SIDE, LINE, Shape.Both, 1);
        assertEquals(List.of("horizontal", "horizontal", "vertical", "vertical", "vertical", "vertical", "lines"), both.calls);

        Recorder lines = new Recorder();
        DuckRender.exact(lines, BOX, SIDE, LINE, Shape.Lines, 1);
        assertEquals(List.of("lines"), lines.calls);
        assertEquals(LINE, lines.colors.getFirst());

        Recorder sides = new Recorder();
        DuckRender.exact(sides, BOX, SIDE, LINE, Shape.Sides, 1);
        assertTrue(sides.calls.stream().noneMatch("lines"::equals));
    }

    @Test
    void invisibleBoxesDrawNothing() {
        Recorder recorder = new Recorder();
        DuckRender.exact(recorder, BOX, SIDE, LINE, Shape.Both, 0.005);
        assertTrue(recorder.calls.isEmpty());
    }

    @Test
    void alphaFadesEveryPart() {
        Recorder recorder = new Recorder();
        DuckRender.exact(recorder, BOX, SIDE, LINE, Shape.Both, 0.5);
        assertEquals(100, Argb.a(recorder.colors.getLast()));
        assertEquals(50, Argb.a(recorder.colors.get(2)));
    }

    @Test
    void untintedBoxMatchesExact() {
        Recorder tinted = new Recorder();
        Recorder exact = new Recorder();
        DuckRender.box(tinted, BOX, SIDE, LINE, Shape.Both, 1);
        DuckRender.exact(exact, BOX, SIDE, LINE, Shape.Both, 1);
        assertEquals(exact.colors, tinted.colors);
    }

    @Test
    void progressRunsRedToGreenKeepingAlpha() {
        int start = DuckRender.progress(0, Argb.alpha(0, 90));
        int end = DuckRender.progress(1, Argb.alpha(0, 90));
        assertTrue(Argb.r(start) > Argb.g(start));
        assertTrue(Argb.g(end) > Argb.r(end));
        assertEquals(90, Argb.a(start));
        assertEquals(90, Argb.a(end));
    }

    @Test
    void fadeHoldsThenReachesZero() {
        assertEquals(1, DuckRender.alpha(0));
        assertEquals(1, DuckRender.alpha(DuckRender.HOLD - 1));
        assertEquals(0, DuckRender.alpha(DuckRender.LIFETIME), 1.0E-9);
    }
}
