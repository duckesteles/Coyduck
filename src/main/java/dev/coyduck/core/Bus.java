package dev.coyduck.core;

import meteordevelopment.orbit.ICancellable;
import meteordevelopment.orbit.IEventBus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class Bus {
    private static final Logger LOG = LoggerFactory.getLogger("Coyduck");
    private static final int MAX_REPORTS = 64;
    private static final Set<String> reported = ConcurrentHashMap.newKeySet();

    private static IEventBus bus;

    private Bus() {}

    static void use(IEventBus target) {
        bus = target;
    }

    static void subscribe(Object listener) {
        bus.subscribe(listener);
    }

    public static <T> T post(T event) {
        if (bus == null) return event;
        try {
            bus.post(event);
        } catch (RuntimeException e) {
            report(event, e);
        }
        return event;
    }

    public static <T extends ICancellable> T post(T event) {
        if (bus == null) return event;
        try {
            bus.post(event);
        } catch (RuntimeException e) {
            report(event, e);
        }
        return event;
    }

    private static void report(Object event, RuntimeException e) {
        StackTraceElement[] trace = e.getStackTrace();
        String site = event.getClass().getName() + ' ' + e.getClass().getName() + ' ' + (trace.length > 0 ? trace[0] : "");
        if (reported.size() < MAX_REPORTS && reported.add(site)) LOG.error("Listener failed on {}", event.getClass().getSimpleName(), e);
    }
}
