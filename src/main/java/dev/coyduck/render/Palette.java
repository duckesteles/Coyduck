package dev.coyduck.render;

public final class Palette {
    public static final int BACKGROUND = 0xA60F0F14;
    public static final int TEXT = 0xFFEDEDF3;
    public static final int SUBTLE = 0xFF9696A2;
    public static final int MUTED = 0xFF6E6E7A;
    public static final int TRACK = 0x30FFFFFF;
    public static final int GOOD = 0xFF7CCE9A;
    public static final int WARN = 0xFFF4C660;
    public static final int BAD = 0xFFE26C7A;

    private Palette() {}

    public static int health(double fraction) {
        return fraction > 0.5 ? Argb.lerp(WARN, GOOD, (fraction - 0.5) * 2) : Argb.lerp(BAD, WARN, fraction * 2);
    }
}
