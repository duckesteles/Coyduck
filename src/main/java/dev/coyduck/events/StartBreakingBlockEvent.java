package dev.coyduck.events;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

public final class StartBreakingBlockEvent extends Cancellable {
    private static final StartBreakingBlockEvent INSTANCE = new StartBreakingBlockEvent();

    public BlockPos blockPos;
    public Direction direction;

    private StartBreakingBlockEvent() {}

    public static StartBreakingBlockEvent get(BlockPos blockPos, Direction direction) {
        INSTANCE.setCancelled(false);
        INSTANCE.blockPos = blockPos;
        INSTANCE.direction = direction;
        return INSTANCE;
    }
}
