package dev.coyduck.feature;

public record Rgba(int r, int g, int b, int a, boolean rainbow) {
    public Rgba(int r, int g, int b, int a) {
        this(r, g, b, a, false);
    }

    public int packed() {
        return a << 24 | r << 16 | g << 8 | b;
    }
}
