package dev.coyduck.logic;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.fluid.FluidState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.BlockView;

public final class BlockOverlay implements BlockView {
    private final BlockView world;
    private long firstKey;
    private BlockState first;
    private Long2ObjectOpenHashMap<BlockState> more;

    public BlockOverlay(BlockView world) {
        this.world = world;
    }

    public BlockOverlay set(BlockPos pos, BlockState state) {
        long key = pos.asLong();
        if (first == null || firstKey == key) {
            firstKey = key;
            first = state;
        } else {
            if (more == null) more = new Long2ObjectOpenHashMap<>();
            more.put(key, state);
        }
        return this;
    }

    private BlockState override(BlockPos pos) {
        if (first == null) return null;
        long key = pos.asLong();
        if (key == firstKey) return first;
        return more == null ? null : more.get(key);
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
        BlockState state = override(pos);
        return state != null ? state : world.getBlockState(pos);
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        BlockState state = override(pos);
        return state != null ? state.getFluidState() : world.getFluidState(pos);
    }

    @Override
    public BlockEntity getBlockEntity(BlockPos pos) {
        return override(pos) != null ? null : world.getBlockEntity(pos);
    }

    @Override
    public int getHeight() {
        return world.getHeight();
    }

    @Override
    public int getBottomY() {
        return world.getBottomY();
    }
}
