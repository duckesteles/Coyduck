package dev.coyduck.events;

import net.minecraft.util.math.BlockPos;

public final class MineStopEvent {
    private static final MineStopEvent INSTANCE = new MineStopEvent();

    public BlockPos pos;

    public static MineStopEvent get(BlockPos pos) {
        INSTANCE.pos = pos;
        return INSTANCE;
    }
}
