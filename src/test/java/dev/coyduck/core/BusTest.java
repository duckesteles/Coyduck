package dev.coyduck.core;

import dev.coyduck.events.Cancellable;
import meteordevelopment.orbit.EventBus;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import meteordevelopment.orbit.ICancellable;
import meteordevelopment.orbit.IEventBus;
import meteordevelopment.orbit.listeners.IListener;
import meteordevelopment.orbit.listeners.LambdaListener;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BusTest {
    static final class Ping {
        int seen;
    }

    static final class Stop extends Cancellable {
        int seen;
    }

    static final class Quiet extends Cancellable {}

    static final class Listener {
        @EventHandler(priority = EventPriority.HIGH)
        private void first(Ping ping) {
            ping.seen++;
        }

        @EventHandler(priority = EventPriority.LOW)
        private void broken(Ping ping) {
            throw new IllegalStateException("boom");
        }

        @EventHandler(priority = EventPriority.LOWEST)
        private void last(Ping ping) {
            ping.seen++;
        }

        @EventHandler(priority = EventPriority.HIGHEST)
        private void brokenFirst(Stop stop) {
            throw new IllegalStateException("boom");
        }

        @EventHandler(priority = EventPriority.HIGH)
        private void cancel(Stop stop) {
            stop.seen++;
            stop.cancel();
        }

        @EventHandler(priority = EventPriority.LOW)
        private void late(Stop stop) {
            stop.seen++;
        }
    }

    static final class Late {
        @EventHandler
        private void hear(Ping ping) {
            ping.seen += 10;
        }
    }

    static final class Wrapped implements IEventBus {
        private final IEventBus inner;

        Wrapped(IEventBus inner) {
            this.inner = inner;
        }

        @Override
        public void registerLambdaFactory(String packagePrefix, LambdaListener.Factory factory) {
            inner.registerLambdaFactory(packagePrefix, factory);
        }

        @Override
        public boolean isListening(Class<?> eventKlass) {
            return inner.isListening(eventKlass);
        }

        @Override
        public <T> T post(T event) {
            return inner.post(event);
        }

        @Override
        public <T extends ICancellable> T post(T event) {
            return inner.post(event);
        }

        @Override
        public void subscribe(Object object) {
            inner.subscribe(object);
        }

        @Override
        public void subscribe(Class<?> klass) {
            inner.subscribe(klass);
        }

        @Override
        public void subscribe(IListener listener) {
            inner.subscribe(listener);
        }

        @Override
        public void unsubscribe(Object object) {
            inner.unsubscribe(object);
        }

        @Override
        public void unsubscribe(Class<?> klass) {
            inner.unsubscribe(klass);
        }

        @Override
        public void unsubscribe(IListener listener) {
            inner.unsubscribe(listener);
        }
    }

    private static IEventBus orbit() {
        IEventBus bus = new EventBus();
        bus.registerLambdaFactory("dev.coyduck", (lookup, owner) -> (MethodHandles.Lookup) lookup.invoke(null, owner, MethodHandles.lookup()));
        return bus;
    }

    static final class Watch implements Host {
        final List<String> quarantined = new ArrayList<>();

        @Override
        public boolean quarantine(String owner, Throwable cause) {
            quarantined.add(owner);
            return true;
        }
    }

    private static void install(IEventBus bus) {
        install(bus, Host.NONE);
    }

    private static void install(IEventBus bus, Host host) {
        Bus.use(bus, host);
        Bus.subscribe(new Listener());
    }

    @AfterEach
    void uninstall() {
        Bus.use(null, Host.NONE);
    }

    @Test
    void failingListenerDoesNotSilenceLaterOnes() {
        install(orbit());
        Ping ping = assertDoesNotThrow(() -> Bus.post(new Ping()));
        assertEquals(2, ping.seen);
    }

    @Test
    void cancelStopsDispatchEvenAfterAFailure() {
        install(orbit());
        Stop stop = Bus.post(new Stop());
        assertTrue(stop.isCancelled());
        assertEquals(1, stop.seen);
    }

    @Test
    void cancellationResetsBetweenPosts() {
        install(orbit());
        Stop stop = new Stop();
        Bus.post(stop);
        Bus.post(stop);
        assertTrue(stop.isCancelled());
        assertEquals(2, stop.seen);
    }

    @Test
    void lateSubscribersAreHeard() {
        install(orbit());
        Bus.subscribe(new Late());
        assertEquals(12, Bus.post(new Ping()).seen);
    }

    @Test
    void unheardEventsKeepTheirFlag() {
        install(orbit());
        Quiet quiet = new Quiet();
        quiet.cancel();
        assertTrue(Bus.post(quiet).isCancelled());
    }

    @Test
    void repeatedFailuresQuarantineTheOwnerOnce() {
        Watch watch = new Watch();
        install(orbit(), watch);
        for (int i = 0; i < 9; i++) Bus.post(new Ping());
        assertTrue(watch.quarantined.isEmpty());
        for (int i = 0; i < 30; i++) Bus.post(new Ping());
        assertEquals(List.of(Listener.class.getName()), watch.quarantined);
    }

    @Test
    void ownerIsTheListeningClass() {
        IEventBus bus = orbit();
        install(bus);
        assertEquals(Listener.class.getName(), Bus.owner(listeners(bus).get(Ping.class).get(0)));
    }

    @SuppressWarnings("unchecked")
    private static Map<Class<?>, List<IListener>> listeners(IEventBus bus) {
        try {
            Field map = EventBus.class.getDeclaredField("listenerMap");
            map.setAccessible(true);
            return (Map<Class<?>, List<IListener>>) map.get(bus);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void postWithoutBusIsANoOp() {
        Stop stop = new Stop();
        assertSame(stop, Bus.post(stop));
        assertFalse(stop.isCancelled());
        assertEquals(0, stop.seen);
    }

    @Test
    void foreignBusFallsBackToGuardedPosting() {
        install(new Wrapped(orbit()));
        Stop stop = assertDoesNotThrow(() -> Bus.post(new Stop()));
        assertFalse(stop.isCancelled());
        assertEquals(0, stop.seen);
        assertDoesNotThrow(() -> Bus.post(new Ping()));
    }
}
