package dev.coyduck.managers;

import dev.coyduck.events.PacketApplyEvent;
import dev.coyduck.events.WorldResetEvent;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.block.BlockState;
import net.minecraft.network.packet.s2c.play.BlockBreakingProgressS2CPacket;
import net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ChunkDeltaUpdateS2CPacket;
import net.minecraft.util.Util;
import net.minecraft.util.math.BlockPos;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public final class MiningTracker {
    private static final MiningTracker INSTANCE = new MiningTracker();

    private static final long LIFETIME = 5_000;

    public record Mining(int breaker, int stage, long time) {}

    private final Long2ObjectOpenHashMap<Mining> mining = new Long2ObjectOpenHashMap<>();
    private final Long2LongOpenHashMap broken = new Long2LongOpenHashMap();

    private MiningTracker() {}

    public static MiningTracker get() {
        return INSTANCE;
    }

    public Mining mining(BlockPos pos) {
        return mining.get(pos.asLong());
    }

    public int stage(BlockPos pos) {
        Mining entry = mining.get(pos.asLong());
        return entry == null ? -1 : entry.stage;
    }

    public boolean wasBroken(BlockPos pos, long within) {
        long time = broken.getOrDefault(pos.asLong(), Long.MIN_VALUE);
        return time != Long.MIN_VALUE && Util.getMeasuringTimeMs() - time < within;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    private void onApplyPre(PacketApplyEvent.Pre event) {
        if (mc.world == null) return;

        switch (event.packet) {
            case BlockUpdateS2CPacket packet -> track(packet.getPos(), packet.getState());
            case ChunkDeltaUpdateS2CPacket packet -> packet.visitUpdates(this::track);
            default -> {}
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    private void onApplyPost(PacketApplyEvent.Post event) {
        if (!(event.packet instanceof BlockBreakingProgressS2CPacket packet)) return;

        long key = packet.getPos().asLong();
        int stage = packet.getProgress();

        if (stage < 0 || stage > 9) mining.remove(key);
        else mining.put(key, new Mining(packet.getEntityId(), stage, Util.getMeasuringTimeMs()));
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        long now = Util.getMeasuringTimeMs();
        mining.values().removeIf(entry -> now - entry.time > LIFETIME);
        broken.long2LongEntrySet().removeIf(entry -> now - entry.getLongValue() > LIFETIME);
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        reset();
    }

    @EventHandler
    private void onWorldReset(WorldResetEvent event) {
        reset();
    }

    private void reset() {
        mining.clear();
        broken.clear();
    }

    private void track(BlockPos pos, BlockState state) {
        if (!state.isReplaceable() || InteractionManager.get().isPending(pos)) return;
        if (mc.world.getBlockState(pos).isReplaceable()) return;

        long key = pos.asLong();
        broken.put(key, Util.getMeasuringTimeMs());
        mining.remove(key);
    }
}
