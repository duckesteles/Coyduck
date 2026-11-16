package dev.coyduck.utils;

import net.minecraft.block.AbstractCauldronBlock;
import net.minecraft.block.AbstractPressurePlateBlock;
import net.minecraft.block.AbstractRedstoneGateBlock;
import net.minecraft.block.AnvilBlock;
import net.minecraft.block.BedBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockWithEntity;
import net.minecraft.block.ButtonBlock;
import net.minecraft.block.CakeBlock;
import net.minecraft.block.CandleCakeBlock;
import net.minecraft.block.CartographyTableBlock;
import net.minecraft.block.CaveVines;
import net.minecraft.block.ComposterBlock;
import net.minecraft.block.CraftingTableBlock;
import net.minecraft.block.DaylightDetectorBlock;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.DragonEggBlock;
import net.minecraft.block.FenceGateBlock;
import net.minecraft.block.FlowerPotBlock;
import net.minecraft.block.GrindstoneBlock;
import net.minecraft.block.LeverBlock;
import net.minecraft.block.LoomBlock;
import net.minecraft.block.NoteBlock;
import net.minecraft.block.RedstoneWireBlock;
import net.minecraft.block.RespawnAnchorBlock;
import net.minecraft.block.StonecutterBlock;
import net.minecraft.block.SweetBerryBushBlock;
import net.minecraft.block.TrapdoorBlock;

public final class BlockRules {
    private BlockRules() {}

    public static boolean isInteractable(Block block) {
        return block instanceof CraftingTableBlock
            || block instanceof AnvilBlock
            || block instanceof LoomBlock
            || block instanceof CartographyTableBlock
            || block instanceof GrindstoneBlock
            || block instanceof StonecutterBlock
            || block instanceof ButtonBlock
            || block instanceof AbstractPressurePlateBlock
            || block instanceof BlockWithEntity
            || block instanceof BedBlock
            || block instanceof FenceGateBlock
            || block instanceof DoorBlock
            || block instanceof NoteBlock
            || block instanceof TrapdoorBlock
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
