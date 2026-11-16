package dev.coyduck.logic;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AckGateTest {
    @Test
    void startsOpen() {
        AckGate gate = new AckGate();
        assertTrue(gate.open());
        assertFalse(gate.expired(Long.MAX_VALUE));
    }

    @Test
    void onlyTheBarrierAckOpens() {
        AckGate gate = new AckGate();
        gate.await(40, 1000, 250);
        assertFalse(gate.open());
        gate.ack(39);
        assertFalse(gate.open());
        gate.ack(40);
        assertTrue(gate.open());
    }

    @Test
    void laterAcksAlsoOpen() {
        AckGate gate = new AckGate();
        gate.await(40, 1000, 250);
        gate.ack(45);
        assertTrue(gate.open());
    }

    @Test
    void expiryDoesNotOpen() {
        AckGate gate = new AckGate();
        gate.await(40, 1000, 250);
        assertFalse(gate.expired(1249));
        assertTrue(gate.expired(1250));
        assertFalse(gate.open());
    }

    @Test
    void awaitingAgainMovesTheBarrier() {
        AckGate gate = new AckGate();
        gate.await(40, 1000, 250);
        gate.await(52, 1250, 250);
        gate.ack(45);
        assertFalse(gate.open());
        assertFalse(gate.expired(1499));
        gate.ack(52);
        assertTrue(gate.open());
    }

    @Test
    void resetOpens() {
        AckGate gate = new AckGate();
        gate.await(40, 1000, 250);
        gate.reset();
        assertTrue(gate.open());
        assertFalse(gate.expired(5000));
    }
}
