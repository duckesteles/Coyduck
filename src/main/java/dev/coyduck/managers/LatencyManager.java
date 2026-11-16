package dev.coyduck.managers;

import dev.coyduck.core.Core;
import dev.coyduck.events.GameLeftEvent;
import dev.coyduck.events.PacketApplyEvent;
import dev.coyduck.events.PacketEvent;
import dev.coyduck.events.TickEvent;
import dev.coyduck.events.WorldResetEvent;
import dev.coyduck.mixin.ClientWorldAccessor;
import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractItemC2SPacket;
import net.minecraft.network.packet.c2s.query.QueryPingC2SPacket;
import net.minecraft.network.packet.s2c.play.PlayerActionResponseS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerPositionLookS2CPacket;
import net.minecraft.network.packet.s2c.query.PingResultS2CPacket;
import net.minecraft.util.Util;

import static dev.coyduck.core.Core.mc;

public final class LatencyManager {
    private static final LatencyManager INSTANCE = new LatencyManager();

    private static final int PROBE_INTERVAL = 20;
    private static final long SAMPLE_LIFETIME = 10_000;
    private static final long SEQUENCE_LIFETIME = 5_000;
    private static final int TICK_WAIT = 25;
    private static final int FALLBACK = 150;
    private static final long FREEZE = 1_200;

    private final Estimator network = new Estimator();
    private final Estimator action = new Estimator();
    private final Int2LongOpenHashMap sequences = new Int2LongOpenHashMap();
    private int probeTimer;
    private volatile long lastPacketAt;
    private volatile int tabLatency;
    private long lastSetbackAt;

    private LatencyManager() {}

    public static LatencyManager get() {
        return INSTANCE;
    }

    public int ping() {
        int measured = measuredPing();
        return measured >= 0 ? measured : FALLBACK;
    }

    public int measuredPing() {
        long now = Util.getMeasuringTimeMs();
        if (network.fresh(now)) return (int) network.mean();
        int tab = tabLatency;
        if (tab > 0) return tab;
        if (action.fresh(now)) return (int) Math.max(action.mean() - TICK_WAIT, 0);
        return -1;
    }

    public int rtt() {
        long now = Util.getMeasuringTimeMs();
        if (action.fresh(now)) return (int) action.mean();
        if (network.fresh(now)) return (int) network.mean() + TICK_WAIT;
        int tab = tabLatency;
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

    public long silentFor() {
        long at = lastPacketAt;
        return at == 0 ? 0 : Util.getMeasuringTimeMs() - at;
    }

    public boolean frozen() {
        return silentFor() > Math.max(FREEZE, 2L * rtt());
    }

    public long sinceSetback() {
        return lastSetbackAt == 0 ? Long.MAX_VALUE : Util.getMeasuringTimeMs() - lastSetbackAt;
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
        lastPacketAt = Util.getMeasuringTimeMs();
        if (event.packet instanceof PingResultS2CPacket packet) {
            network.sample(Util.getMeasuringTimeMs() - packet.startTime());
        }
    }

    @EventHandler
    private void onApply(PacketApplyEvent.Pre event) {
        if (event.packet instanceof PlayerPositionLookS2CPacket) lastSetbackAt = Util.getMeasuringTimeMs();
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
        PlayerListEntry self = mc.player == null ? null : mc.getNetworkHandler().getPlayerListEntry(mc.player.getUuid());
        tabLatency = self == null ? 0 : self.getLatency();

        if (++probeTimer >= PROBE_INTERVAL && Core.host().anyModuleActive()) {
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
        lastPacketAt = 0;
        lastSetbackAt = 0;
        tabLatency = 0;
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
