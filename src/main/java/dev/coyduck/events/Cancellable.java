package dev.coyduck.events;

import meteordevelopment.orbit.ICancellable;

public abstract class Cancellable implements ICancellable {
    private boolean cancelled;

    @Override
    public void setCancelled(boolean cancelled) {
        this.cancelled = cancelled;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }
}
