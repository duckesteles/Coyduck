package dev.coyduck.core;

import dev.coyduck.events.Cancellable;
import meteordevelopment.orbit.EventBus;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import meteordevelopment.orbit.IEventBus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.invoke.MethodHandles;

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

    @BeforeEach
    void install() {
        IEventBus bus = new EventBus();
        bus.registerLambdaFactory("dev.coyduck", (lookup, owner) -> (MethodHandles.Lookup) lookup.invoke(null, owner, MethodHandles.lookup()));
        Bus.use(bus);
        Bus.subscribe(new Listener());
    }

    @AfterEach
    void uninstall() {
        Bus.use(null);
    }

    @Test
    void failingListenerDoesNotEscape() {
        assertDoesNotThrow(() -> Bus.post(new Ping()));
    }

    @Test
    void failingListenerDoesNotSilenceLaterOnes() {
        Ping ping = Bus.post(new Ping());
        assertEquals(2, ping.seen);
    }

    @Test
    void cancelStillWorksAfterAFailure() {
        Stop stop = Bus.post(new Stop());
        assertTrue(stop.isCancelled());
        assertEquals(1, stop.seen);
    }

    @Test
    void cancellationStopsDispatch() {
        Stop stop = Bus.post(new Stop());
        assertTrue(stop.isCancelled());
        assertEquals(1, stop.seen);
    }

    @Test
    void cancellationResetsBetweenPosts() {
        Stop stop = new Stop();
        Bus.post(stop);
        Bus.post(stop);
        assertTrue(stop.isCancelled());
        assertEquals(2, stop.seen);
    }

    @Test
    void postWithoutBusIsANoOp() {
        Bus.use(null);
        Stop stop = new Stop();
        assertSame(stop, Bus.post(stop));
        assertFalse(stop.isCancelled());
        assertEquals(0, stop.seen);
    }
}
