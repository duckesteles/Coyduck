package dev.coyduck.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ArgbTest {
    @Test
    void channelsRoundTrip() {
        int argb = Argb.of(12, 34, 56, 78);
        assertEquals(12, Argb.r(argb));
        assertEquals(34, Argb.g(argb));
        assertEquals(56, Argb.b(argb));
        assertEquals(78, Argb.a(argb));
    }

    @Test
    void opaqueAlphaStaysPositiveChannel() {
        assertEquals(255, Argb.a(0xFF000000));
    }

    @Test
    void channelsClamp() {
        assertEquals(Argb.of(255, 0, 255, 0), Argb.of(300, -5, 999, -1));
        assertEquals(255, Argb.a(Argb.alpha(0x123456, 1000)));
    }

    @Test
    void alphaKeepsColor() {
        assertEquals(0x40123456, Argb.alpha(0xFF123456, 0x40));
    }

    @Test
    void lerpHitsBothEndsAndClamps() {
        int from = Argb.of(0, 100, 200, 0);
        int to = Argb.of(200, 100, 0, 255);
        assertEquals(from, Argb.lerp(from, to, 0));
        assertEquals(to, Argb.lerp(from, to, 1));
        assertEquals(to, Argb.lerp(from, to, 7));
        assertEquals(Argb.of(100, 100, 100, 128), Argb.lerp(from, to, 0.5));
    }

    @Test
    void shadeLightensDarkensAndFades() {
        int base = Argb.of(100, 100, 100, 200);
        assertEquals(Argb.of(177, 177, 177, 200), Argb.shade(base, 0.5, 1, 1));
        assertEquals(Argb.of(50, 50, 50, 100), Argb.shade(base, -0.5, 1, 0.5));
        assertEquals(255, Argb.a(Argb.shade(base, 0, 2, 1)));
    }
}
