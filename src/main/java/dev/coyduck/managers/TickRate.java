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
    private long lastUpdate;
    private long joinedAt;

    TickRate() {}

    public static TickRate get() {
        return INSTANCE;
    }

    public float tickRate() {
        if (mc.world == null || mc.player == null) return 0;
        return rate(System.currentTimeMillis());
    }

    public float scale() {
        return Math.min(tickRate() / FULL, 1.0f);
    }

    public float tickLength() {
        float rate = tickRate();
        return 1000.0f / (rate > 0 ? Math.min(rate, FULL) : FULL);
    }

    float rate(long now) {
        if (now - joinedAt < GRACE_MS) return FULL;
        float sum = 0;
        int count = 0;
        for (float sample : samples) {
            if (sample <= 0) continue;
            sum += sample;
            count++;
        }
        return count == 0 ? FULL : sum / count;
    }

    void sample(long now) {
        samples[next] = MathHelper.clamp(FULL / ((now - lastUpdate) / 1000.0f), 0.0f, FULL);
        next = (next + 1) % samples.length;
        lastUpdate = now;
    }

    void join(long now) {
        Arrays.fill(samples, 0);
        next = 0;
        joinedAt = lastUpdate = now;
    }

    @EventHandler
    private void onReceive(PacketEvent.Receive event) {
        if (event.packet instanceof WorldTimeUpdateS2CPacket) sample(System.currentTimeMillis());
    }

    @EventHandler
    private void onGameJoined(GameJoinedEvent event) {
        join(System.currentTimeMillis());
    }
}
