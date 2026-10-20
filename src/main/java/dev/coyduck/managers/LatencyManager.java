package dev.coyduck.managers;

import dev.coyduck.events.PacketApplyEvent;
import dev.coyduck.events.WorldResetEvent;
import dev.coyduck.mixin.ClientWorldAccessor;
import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractItemC2SPacket;
import net.minecraft.network.packet.c2s.query.QueryPingC2SPacket;
import net.minecraft.network.packet.s2c.play.PlayerActionResponseS2CPacket;
import net.minecraft.network.packet.s2c.query.PingResultS2CPacket;
import net.minecraft.util.Util;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public final class LatencyManager {
    private static final LatencyManager INSTANCE = new LatencyManager();

    private static final int PROBE_INTERVAL = 20;
    private static final long SAMPLE_LIFETIME = 10_000;
    private static final long SEQUENCE_LIFETIME = 5_000;
    private static final int TICK_WAIT = 25;
    private static final int FALLBACK = 150;

    private final Estimator network = new Estimator();
    private final Estimator action = new Estimator();
    private final Int2LongOpenHashMap sequences = new Int2LongOpenHashMap();
    private int probeTimer;

    private LatencyManager() {}

    public static LatencyManager get() {
        return INSTANCE;
    }

    public int ping() {
        long now = Util.getMeasuringTimeMs();
        if (network.fresh(now)) return (int) network.mean();
        int tab = tabLatency();
        if (tab > 0) return tab;
        if (action.fresh(now)) return (int) Math.max(action.mean() - TICK_WAIT, 0);
        return FALLBACK;
    }

    public int rtt() {
        long now = Util.getMeasuringTimeMs();
        if (action.fresh(now)) return (int) action.mean();
        if (network.fresh(now)) return (int) network.mean() + TICK_WAIT;
        int tab = tabLatency();
        if (tab > 0) return tab + TICK_WAIT;
        return FALLBACK;
    }

    public int jitter() {
        long now = Util.getMeasuringTimeMs();
        return (int) (action.fresh(now) ? action.deviation() : network.deviation());
    }

    public int timeout() {
        long now = Util.getMeasuringTimeMs();
        double deviation = action.fresh(now) ? action.deviation() : network.deviation();
        double timeout = rtt() + Math.max(4 * deviation, 50) + 50;
        return (int) Math.clamp(timeout, 150, 3000);
    }

    @EventHandler
    private void onSent(PacketEvent.Sent event) {
        int sequence = sequenceOf(event.packet);
        if (sequence <= 0) return;

        synchronized (sequences) {
            sequences.put(sequence, Util.getMeasuringTimeMs());
        }
    }

    @EventHandler
    private void onReceive(PacketEvent.Receive event) {
        if (event.packet instanceof PingResultS2CPacket packet) {
            network.sample(Util.getMeasuringTimeMs() - packet.startTime());
        }
    }

    @EventHandler
    private void onApply(PacketApplyEvent.Pre event) {
        if (!(event.packet instanceof PlayerActionResponseS2CPacket packet) || mc.world == null) return;

        long now = Util.getMeasuringTimeMs();
        int acked = packet.sequence();
        if (acked > ((ClientWorldAccessor) mc.world).coyduck$getPendingUpdateManager().getSequence()) return;

        synchronized (sequences) {
            if (sequences.containsKey(acked)) action.sample(now - sequences.get(acked));
            sequences.int2LongEntrySet().removeIf(entry -> entry.getIntKey() <= acked);
        }
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.getNetworkHandler() == null) return;

        if (++probeTimer >= PROBE_INTERVAL) {
            probeTimer = 0;
            mc.getNetworkHandler().sendPacket(new QueryPingC2SPacket(Util.getMeasuringTimeMs()));
        }

        long now = Util.getMeasuringTimeMs();
        synchronized (sequences) {
            sequences.int2LongEntrySet().removeIf(entry -> now - entry.getLongValue() > SEQUENCE_LIFETIME);
        }
    }

    @EventHandler
    private void onWorldReset(WorldResetEvent event) {
        synchronized (sequences) {
            sequences.clear();
        }
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        network.reset();
        action.reset();
        probeTimer = 0;
        synchronized (sequences) {
            sequences.clear();
        }
    }

    private static int sequenceOf(Packet<?> packet) {
        return switch (packet) {
            case PlayerInteractBlockC2SPacket p -> p.getSequence();
            case PlayerInteractItemC2SPacket p -> p.getSequence();
            case PlayerActionC2SPacket p -> p.getSequence();
            default -> 0;
        };
    }

    private static int tabLatency() {
        if (mc.player == null || mc.getNetworkHandler() == null) return 0;
        PlayerListEntry entry = mc.getNetworkHandler().getPlayerListEntry(mc.player.getUuid());
        return entry == null ? 0 : entry.getLatency();
    }

    private static final class Estimator {
        private double mean = -1;
        private double deviation;
        private long last;

        synchronized void sample(long value) {
            double rtt = Math.clamp(value, 0, 10_000);
            if (mean < 0) {
                mean = rtt;
                deviation = rtt / 2;
            } else {
                deviation = 0.75 * deviation + 0.25 * Math.abs(mean - rtt);
                mean = 0.875 * mean + 0.125 * rtt;
            }
            last = Util.getMeasuringTimeMs();
        }

        synchronized boolean fresh(long now) {
            return mean >= 0 && now - last < SAMPLE_LIFETIME;
        }

        synchronized double mean() {
            return Math.max(mean, 0);
        }

        synchronized double deviation() {
            return deviation;
        }

        synchronized void reset() {
            mean = -1;
            deviation = 0;
            last = 0;
        }
    }
}
