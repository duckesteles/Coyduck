package dev.coyduck.logic;

public final class AckGate {
    private int sequence;
    private long until;
    private boolean waiting;

    public void await(int barrier, long now, long timeout) {
        sequence = barrier;
        until = now + timeout;
        waiting = true;
    }

    public void ack(int acked) {
        if (waiting && acked >= sequence) waiting = false;
    }

    public boolean open() {
        return !waiting;
    }

    public boolean expired(long now) {
        return waiting && now >= until;
    }

    public void reset() {
        waiting = false;
    }
}
