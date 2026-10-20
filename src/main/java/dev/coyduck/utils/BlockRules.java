package dev.coyduck.utils;

import meteordevelopment.meteorclient.utils.world.BlockUtils;
import net.minecraft.block.AbstractCauldronBlock;
import net.minecraft.block.AbstractRedstoneGateBlock;
import net.minecraft.block.Block;
import net.minecraft.block.CakeBlock;
import net.minecraft.block.CandleCakeBlock;
import net.minecraft.block.CaveVines;
import net.minecraft.block.ComposterBlock;
import net.minecraft.block.DaylightDetectorBlock;
import net.minecraft.block.DragonEggBlock;
import net.minecraft.block.FlowerPotBlock;
import net.minecraft.block.LeverBlock;
import net.minecraft.block.RedstoneWireBlock;
import net.minecraft.block.RespawnAnchorBlock;
import net.minecraft.block.SweetBerryBushBlock;

public final class BlockRules {
    private BlockRules() {}

    public static boolean isInteractable(Block block) {
        return BlockUtils.isClickable(block)
            || block instanceof RespawnAnchorBlock
            || block instanceof LeverBlock
            || block instanceof AbstractRedstoneGateBlock
            || block instanceof RedstoneWireBlock
            || block instanceof CakeBlock
            || block instanceof CandleCakeBlock
            || block instanceof ComposterBlock
            || block instanceof FlowerPotBlock
            || block instanceof DragonEggBlock
            || block instanceof AbstractCauldronBlock
            || block instanceof DaylightDetectorBlock
            || block instanceof CaveVines
            || block instanceof SweetBerryBushBlock;
    }
}
