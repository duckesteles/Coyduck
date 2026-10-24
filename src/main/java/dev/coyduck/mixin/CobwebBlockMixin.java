package dev.coyduck.mixin;

import dev.coyduck.modules.AntiWeb;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.CobwebBlock;
import net.minecraft.block.ShapeContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(CobwebBlock.class)
public abstract class CobwebBlockMixin extends Block {
    private CobwebBlockMixin(Settings settings) {
        super(settings);
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return AntiWeb.solid(pos, context) ? VoxelShapes.fullCube() : super.getCollisionShape(state, world, pos, context);
    }
}
