package dev.coyduck.core;

import meteordevelopment.orbit.ICancellable;
import meteordevelopment.orbit.IEventBus;

public final class Bus {
    private static IEventBus bus;

    private Bus() {}

    static void use(IEventBus target) {
        bus = target;
    }

    static void subscribe(Object listener) {
        bus.subscribe(listener);
    }

    public static <T> T post(T event) {
        if (bus != null) bus.post(event);
        return event;
    }

    public static <T extends ICancellable> T post(T event) {
        if (bus != null) bus.post(event);
        return event;
    }
}
