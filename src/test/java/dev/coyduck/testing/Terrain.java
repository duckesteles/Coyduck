package dev.coyduck.testing;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.fluid.FluidState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.BlockView;

public final class Terrain implements BlockView {
    private final Long2ObjectOpenHashMap<BlockState> blocks = new Long2ObjectOpenHashMap<>();

    public Terrain set(int x, int y, int z, BlockState state) {
        blocks.put(BlockPos.asLong(x, y, z), state);
        return this;
    }

    public Terrain fill(int x1, int y1, int z1, int x2, int y2, int z2, BlockState state) {
        for (int x = x1; x <= x2; x++) {
            for (int y = y1; y <= y2; y++) {
                for (int z = z1; z <= z2; z++) set(x, y, z, state);
            }
        }
        return this;
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
        BlockState state = blocks.get(pos.asLong());
        return state != null ? state : Blocks.AIR.getDefaultState();
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        return getBlockState(pos).getFluidState();
    }

    @Override
    public BlockEntity getBlockEntity(BlockPos pos) {
        return null;
    }

    @Override
    public int getHeight() {
        return 384;
    }

    @Override
    public int getBottomY() {
        return -64;
    }
}
