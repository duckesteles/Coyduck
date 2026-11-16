package dev.coyduck.events;

import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;

public final class BlockUpdateEvent {
    private static final BlockUpdateEvent INSTANCE = new BlockUpdateEvent();

    public BlockPos pos;
    public BlockState oldState;
    public BlockState newState;

    private BlockUpdateEvent() {}

    public static BlockUpdateEvent get(BlockPos pos, BlockState oldState, BlockState newState) {
        INSTANCE.pos = pos;
        INSTANCE.oldState = oldState;
        INSTANCE.newState = newState;
        return INSTANCE;
    }
}
