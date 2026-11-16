package dev.coyduck.core;

import meteordevelopment.orbit.EventBus;
import meteordevelopment.orbit.ICancellable;
import meteordevelopment.orbit.IEventBus;
import meteordevelopment.orbit.listeners.IListener;
import meteordevelopment.orbit.listeners.LambdaListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class Bus {
    private static final Logger LOG = LoggerFactory.getLogger("Coyduck");
    private static final long SLOW_NS = 20_000_000L;
    private static final Set<String> reported = ConcurrentHashMap.newKeySet();
    private static final Map<IListener, Class<?>> owners = new ConcurrentHashMap<>();
    private static final Field EXECUTOR = executorField();

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
        if (map == null) {
            if (bus != null) guarded(event);
            return event;
        }
        List<IListener> targets = map.get(event.getClass());
        if (targets == null) return event;
        long mark = System.nanoTime();
        for (IListener listener : targets) mark = call(listener, event, mark);
        return event;
    }

    public static <T extends ICancellable> T post(T event) {
        Map<Class<?>, List<IListener>> map = listeners;
        if (map == null) {
            if (bus != null) guarded(event);
            return event;
        }
        List<IListener> targets = map.get(event.getClass());
        if (targets == null) return event;
        event.setCancelled(false);
        long mark = System.nanoTime();
        for (IListener listener : targets) {
            mark = call(listener, event, mark);
            if (event.isCancelled()) break;
        }
        return event;
    }

    static Class<?> owner(IListener listener) {
        return owners.computeIfAbsent(listener, Bus::resolveOwner);
    }

    private static long call(IListener listener, Object event, long start) {
        try {
            listener.call(event);
        } catch (RuntimeException | LinkageError | StackOverflowError | AssertionError e) {
            report(event, e);
        }
        long end = System.nanoTime();
        if (end - start > SLOW_NS) slow(listener, event, end - start);
        return end;
    }

    private static void guarded(Object event) {
        try {
            if (event instanceof ICancellable cancellable) bus.post(cancellable);
            else bus.post(event);
        } catch (RuntimeException | LinkageError | StackOverflowError | AssertionError e) {
            report(event, e);
        }
    }

    private static void slow(IListener listener, Object event, long spent) {
        Class<?> owner = owner(listener);
        if (reported.add("slow " + owner.getName() + ' ' + event.getClass().getName())) {
            LOG.warn("{} took {} ms on {}", owner.getName(), spent / 1_000_000, event.getClass().getSimpleName());
        }
    }

    private static void report(Object event, Throwable e) {
        StackTraceElement[] trace = e.getStackTrace();
        String site = event.getClass().getName() + ' ' + e.getClass().getName() + ' ' + (trace.length > 0 ? trace[0] : "");
        if (reported.add(site)) LOG.error("Listener failed on {}", event.getClass().getSimpleName(), e);
    }

    private static Class<?> resolveOwner(IListener listener) {
        if (EXECUTOR != null && listener instanceof LambdaListener) {
            try {
                return EXECUTOR.get(listener).getClass().getNestHost();
            } catch (IllegalAccessException | RuntimeException ignored) {
            }
        }
        return listener.getClass();
    }

    private static Field executorField() {
        try {
            Field field = LambdaListener.class.getDeclaredField("executor");
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
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
}
