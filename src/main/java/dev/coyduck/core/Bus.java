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
    private static final int STRIKES = 10;
    private static final long STRIKE_WINDOW_MS = 5_000;
    private static final Set<String> reported = ConcurrentHashMap.newKeySet();
    private static final Map<IListener, String> owners = new ConcurrentHashMap<>();
    private static final Map<IListener, Strikes> strikes = new ConcurrentHashMap<>();
    private static final Field EXECUTOR = executorField();

    private static IEventBus bus;
    private static Map<Class<?>, List<IListener>> listeners;
    private static Host host = Host.NONE;

    private static final class Strikes {
        long since;
        int count;
    }

    private Bus() {}

    static void use(IEventBus target, Host environment) {
        bus = target;
        host = environment;
        listeners = target == null ? null : listenerMap(target);
        strikes.clear();
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
        for (IListener listener : targets) call(listener, event);
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
        for (IListener listener : targets) {
            call(listener, event);
            if (event.isCancelled()) break;
        }
        return event;
    }

    static String owner(IListener listener) {
        return owners.computeIfAbsent(listener, Bus::resolveOwner);
    }

    private static void call(IListener listener, Object event) {
        long start = System.nanoTime();
        try {
            listener.call(event);
        } catch (RuntimeException e) {
            fail(listener, event, e);
        }
        long spent = System.nanoTime() - start;
        if (spent > SLOW_NS) slow(listener, event, spent);
    }

    private static void guarded(Object event) {
        try {
            if (event instanceof ICancellable cancellable) bus.post(cancellable);
            else bus.post(event);
        } catch (RuntimeException e) {
            report(event, e);
        }
    }

    private static void fail(IListener listener, Object event, RuntimeException e) {
        report(event, e);
        Strikes record = strikes.computeIfAbsent(listener, key -> new Strikes());
        int count;
        synchronized (record) {
            long now = System.currentTimeMillis();
            if (now - record.since > STRIKE_WINDOW_MS) {
                record.since = now;
                record.count = 0;
            }
            count = ++record.count;
        }
        if (count != STRIKES) return;
        String owner = owner(listener);
        if (host.quarantine(owner, e)) LOG.error("Quarantined {} after {} failures in {}s", owner, STRIKES, STRIKE_WINDOW_MS / 1000);
        else LOG.error("{} keeps failing on {}, it stays subscribed", owner, event.getClass().getSimpleName());
    }

    private static void slow(IListener listener, Object event, long spent) {
        String owner = owner(listener);
        if (reported.add("slow " + owner + ' ' + event.getClass().getName())) {
            LOG.warn("{} took {} ms on {}", owner, spent / 1_000_000, event.getClass().getSimpleName());
        }
    }

    private static void report(Object event, RuntimeException e) {
        StackTraceElement[] trace = e.getStackTrace();
        String site = event.getClass().getName() + ' ' + e.getClass().getName() + ' ' + (trace.length > 0 ? trace[0] : "");
        if (reported.add(site)) LOG.error("Listener failed on {}", event.getClass().getSimpleName(), e);
    }

    private static String resolveOwner(IListener listener) {
        if (EXECUTOR != null && listener instanceof LambdaListener) {
            try {
                String name = EXECUTOR.get(listener).getClass().getName();
                int lambda = name.indexOf("$$Lambda");
                return lambda > 0 ? name.substring(0, lambda) : name;
            } catch (IllegalAccessException | RuntimeException ignored) {
            }
        }
        return listener.getClass().getName();
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
