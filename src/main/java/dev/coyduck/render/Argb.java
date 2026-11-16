package dev.coyduck.render;

public final class Argb {
    private Argb() {}

    public static int of(int r, int g, int b, int a) {
        return clamp(a) << 24 | clamp(r) << 16 | clamp(g) << 8 | clamp(b);
    }

    public static int r(int argb) {
        return argb >> 16 & 0xFF;
    }

    public static int g(int argb) {
        return argb >> 8 & 0xFF;
    }

    public static int b(int argb) {
        return argb & 0xFF;
    }

    public static int a(int argb) {
        return argb >>> 24;
    }

    public static int alpha(int argb, int a) {
        return clamp(a) << 24 | argb & 0xFFFFFF;
    }

    public static int fade(int argb, double alpha) {
        return alpha(argb, (int) Math.round(a(argb) * Math.clamp(alpha, 0, 1)));
    }

    public static int lerp(int from, int to, double t) {
        t = Math.clamp(t, 0, 1);
        return of(
            (int) Math.round(r(from) + (r(to) - r(from)) * t),
            (int) Math.round(g(from) + (g(to) - g(from)) * t),
            (int) Math.round(b(from) + (b(to) - b(from)) * t),
            (int) Math.round(a(from) + (a(to) - a(from)) * t)
        );
    }

    public static int shade(int base, double light, double alphaScale, double alpha) {
        double r = r(base);
        double g = g(base);
        double b = b(base);
        if (light > 0) {
            r += (255 - r) * light;
            g += (255 - g) * light;
            b += (255 - b) * light;
        } else if (light < 0) {
            r *= 1 + light;
            g *= 1 + light;
            b *= 1 + light;
        }
        return of((int) r, (int) g, (int) b, (int) Math.clamp(a(base) * alphaScale * alpha, 0, 255));
    }

    private static int clamp(int channel) {
        return Math.clamp(channel, 0, 255);
    }
}
