package dev.coyduck.managers;

import dev.coyduck.events.GameJoinedEvent;
import dev.coyduck.events.PacketEvent;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.packet.s2c.play.WorldTimeUpdateS2CPacket;
import net.minecraft.util.math.MathHelper;

import java.util.Arrays;

import static dev.coyduck.core.Core.mc;

public final class TickRate {
    private static final TickRate INSTANCE = new TickRate();
    private static final long GRACE_MS = 4000;
    private static final float FULL = 20.0f;

    private final float[] samples = new float[20];
    private int next;
    private long lastUpdate = -1;
    private long joinedAt;

    private TickRate() {}

    public static TickRate get() {
        return INSTANCE;
    }

    public float tickRate() {
        if (mc.world == null || mc.player == null) return 0;
        if (System.currentTimeMillis() - joinedAt < GRACE_MS) return FULL;
        float sum = 0;
        int count = 0;
        for (float sample : samples) {
            if (sample <= 0) continue;
            sum += sample;
            count++;
        }
        return count == 0 ? FULL : sum / count;
    }

    @EventHandler
    private void onReceive(PacketEvent.Receive event) {
        if (!(event.packet instanceof WorldTimeUpdateS2CPacket)) return;
        long now = System.currentTimeMillis();
        samples[next] = MathHelper.clamp(FULL / ((now - lastUpdate) / 1000.0f), 0.0f, FULL);
        next = (next + 1) % samples.length;
        lastUpdate = now;
    }

    @EventHandler
    private void onGameJoined(GameJoinedEvent event) {
        Arrays.fill(samples, 0);
        next = 0;
        joinedAt = lastUpdate = System.currentTimeMillis();
    }
}
