package dev.coyduck.core;

public final class Clock {
    private static long ticks;

    private Clock() {}

    public static long ticks() {
        return ticks;
    }

    public static void advance() {
        ticks++;
    }
}
