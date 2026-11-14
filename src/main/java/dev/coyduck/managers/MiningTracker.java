package dev.coyduck.managers;

import dev.coyduck.events.PacketApplyEvent;
import dev.coyduck.events.WorldResetEvent;
import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;
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
    private static final long SUPERSEDED = 1_000;
    private static final int PRIMED = 10;
    private static final int MAX_STAGE = 9;
    private static final int ABORTED = 255;

    public record Mining(int breaker, int stage, long time, long first, int raw) {}

    private final Long2ObjectOpenHashMap<Mining> mining = new Long2ObjectOpenHashMap<>();
    private final Long2LongOpenHashMap broken = new Long2LongOpenHashMap();

    private MiningTracker() {}

    public static MiningTracker get() {
        return INSTANCE;
    }

    public Mining mining(BlockPos pos) {
        return mining.get(pos.asLong());
    }

    public void forEach(java.util.function.BiConsumer<BlockPos, Mining> action) {
        mining.long2ObjectEntrySet().forEach(entry -> action.accept(BlockPos.fromLong(entry.getLongKey()), entry.getValue()));
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
        int breaker = packet.getEntityId();
        long now = Util.getMeasuringTimeMs();
        Mining previous = mining.get(key);
        boolean same = previous != null && previous.breaker == breaker;

        if (same && previous.raw >= PRIMED && (stage == ((previous.raw + 1) & 0xFF) || stage < previous.raw && stage != ABORTED)) {
            mining.put(key, new Mining(breaker, MAX_STAGE, now, previous.first, stage));
            return;
        }
        if (stage < 0 || stage >= ABORTED) {
            if (previous == null || same) mining.remove(key);
            return;
        }
        mining.put(key, new Mining(breaker, Math.min(stage, MAX_STAGE), now, same ? previous.first : now, stage));
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        long now = Util.getMeasuringTimeMs();
        if (!broken.isEmpty()) broken.long2LongEntrySet().removeIf(entry -> now - entry.getLongValue() > LIFETIME);
        if (mining.isEmpty()) return;
        mining.values().removeIf(entry -> now - entry.time > LIFETIME || mc.world != null && mc.world.getEntityById(entry.breaker) == null);

        Int2LongOpenHashMap newest = new Int2LongOpenHashMap();
        for (Mining entry : mining.values()) newest.put(entry.breaker, Math.max(newest.getOrDefault(entry.breaker, Long.MIN_VALUE), entry.first));
        mining.values().removeIf(entry -> newest.get(entry.breaker) > entry.first && now - entry.time > SUPERSEDED);
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
        long key = pos.asLong();
        if (mining.containsKey(key) && state.getBlock() != mc.world.getBlockState(pos).getBlock()) mining.remove(key);
        if (!state.isReplaceable() || InteractionManager.get().isPending(pos)) return;
        if (mc.world.getBlockState(pos).isReplaceable()) return;

        broken.put(key, Util.getMeasuringTimeMs());
        mining.remove(key);
    }
}
