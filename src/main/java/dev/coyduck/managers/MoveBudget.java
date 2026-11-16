package dev.coyduck.managers;

import dev.coyduck.events.PacketEvent;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.util.Util;

public final class MoveBudget {
    private static final MoveBudget INSTANCE = new MoveBudget();
    private static final long WINDOW = 500;
    private static final int SAFE = 13;

    private final long[] sent = new long[32];
    private int head;
    private int size;

    private MoveBudget() {}

    public static MoveBudget get() {
        return INSTANCE;
    }

    public boolean spare() {
        trim(Util.getMeasuringTimeMs());
        return size < SAFE;
    }

    @EventHandler
    private void onSent(PacketEvent.Sent event) {
        if (!(event.packet instanceof PlayerMoveC2SPacket packet) || !packet.changesPosition()) return;
        long now = Util.getMeasuringTimeMs();
        trim(now);
        sent[(head + size) % sent.length] = now;
        if (size < sent.length) size++;
        else head = (head + 1) % sent.length;
    }

    private void trim(long now) {
        while (size > 0 && now - sent[head] > WINDOW) {
            head = (head + 1) % sent.length;
            size--;
        }
    }
}
