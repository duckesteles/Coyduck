package dev.coyduck.core;

import meteordevelopment.orbit.EventBus;
import meteordevelopment.orbit.ICancellable;
import meteordevelopment.orbit.IEventBus;
import meteordevelopment.orbit.listeners.IListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class Bus {
    private static final Logger LOG = LoggerFactory.getLogger("Coyduck");
    private static final Set<String> reported = ConcurrentHashMap.newKeySet();

    private static IEventBus bus;
    private static Map<Class<?>, List<IListener>> listeners;

    private Bus() {}

    static void use(IEventBus target) {
        bus = target;
        listeners = target == null ? null : listenerMap(target);
    }

    static void subscribe(Object listener) {
        bus.subscribe(listener);
    }

    public static <T> T post(T event) {
        Map<Class<?>, List<IListener>> map = listeners;
        if (map == null) return guarded(event);
        List<IListener> targets = map.get(event.getClass());
        if (targets == null) return event;
        for (IListener listener : targets) call(listener, event);
        return event;
    }

    public static <T extends ICancellable> T post(T event) {
        Map<Class<?>, List<IListener>> map = listeners;
        if (map == null) return guarded(event);
        List<IListener> targets = map.get(event.getClass());
        if (targets == null) return event;
        event.setCancelled(false);
        for (IListener listener : targets) {
            call(listener, event);
            if (event.isCancelled()) break;
        }
        return event;
    }

    private static void call(IListener listener, Object event) {
        try {
            listener.call(event);
        } catch (RuntimeException e) {
            report(event, e);
        }
    }

    private static <T> T guarded(T event) {
        if (bus == null) return event;
        try {
            bus.post(event);
        } catch (RuntimeException e) {
            report(event, e);
        }
        return event;
    }

    @SuppressWarnings("unchecked")
    private static Map<Class<?>, List<IListener>> listenerMap(IEventBus target) {
        if (!(target instanceof EventBus)) return null;
        try {
            Field field = EventBus.class.getDeclaredField("listenerMap");
            field.setAccessible(true);
            return (Map<Class<?>, List<IListener>>) field.get(target);
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOG.warn("Event bus isolation unavailable, falling back to whole-post guarding", e);
            return null;
        }
    }

    private static void report(Object event, RuntimeException e) {
        StackTraceElement[] trace = e.getStackTrace();
        String site = event.getClass().getName() + ' ' + e.getClass().getName() + ' ' + (trace.length > 0 ? trace[0] : "");
        if (reported.add(site)) LOG.error("Listener failed on {}", event.getClass().getSimpleName(), e);
    }
}
