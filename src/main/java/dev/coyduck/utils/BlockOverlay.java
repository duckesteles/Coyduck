package dev.coyduck.utils;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.fluid.FluidState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.BlockView;

public final class BlockOverlay implements BlockView {
    private final BlockView world;
    private final Long2ObjectOpenHashMap<BlockState> states = new Long2ObjectOpenHashMap<>();

    public BlockOverlay(BlockView world) {
        this.world = world;
    }

    public BlockOverlay set(BlockPos pos, BlockState state) {
        states.put(pos.asLong(), state);
        return this;
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
        BlockState state = states.get(pos.asLong());
        return state != null ? state : world.getBlockState(pos);
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        BlockState state = states.get(pos.asLong());
        return state != null ? state.getFluidState() : world.getFluidState(pos);
    }

    @Override
    public BlockEntity getBlockEntity(BlockPos pos) {
        return states.containsKey(pos.asLong()) ? null : world.getBlockEntity(pos);
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
