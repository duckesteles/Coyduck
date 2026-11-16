package dev.coyduck.logic;

import dev.coyduck.testing.Terrain;
import dev.coyduck.testing.Vanilla;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(Vanilla.class)
class CellSightTest {
    private static BlockState obsidian() {
        return Blocks.OBSIDIAN.getDefaultState();
    }

    @Test
    void openAirIsVisible() {
        assertTrue(CellSight.sees(new Terrain(), new BlockPos(3, 0, 2), 0, 1, 0));
    }

    @Test
    void sameCellIsVisible() {
        assertTrue(CellSight.sees(new Terrain().set(0, 1, 0, obsidian()), new BlockPos(0, 1, 0), 0, 1, 0));
    }

    @Test
    void fullWallBlocks() {
        Terrain terrain = new Terrain().fill(1, -2, -2, 1, 4, 4, obsidian());
        assertFalse(CellSight.sees(terrain, new BlockPos(2, 0, 1), 0, 1, 0));
    }

    @Test
    void diagonalGapDoesNotLetTheWalkThrough() {
        Terrain terrain = new Terrain().set(1, 1, 0, obsidian()).set(0, 1, 1, obsidian());
        assertFalse(CellSight.sees(terrain, new BlockPos(1, 1, 1), 0, 1, 0));
    }

    @Test
    void walkGoesAroundAnObstacle() {
        Terrain terrain = new Terrain().set(1, 1, 0, obsidian());
        assertTrue(CellSight.sees(terrain, new BlockPos(2, 1, 1), 0, 1, 0));
    }

    @Test
    void webAndWaterDoNotBlock() {
        Terrain terrain = new Terrain().fill(1, -2, -2, 1, 4, 4, Blocks.COBWEB.getDefaultState());
        assertTrue(CellSight.sees(terrain, new BlockPos(2, 0, 1), 0, 1, 0));
        Terrain water = new Terrain().fill(1, -2, -2, 1, 4, 4, Blocks.WATER.getDefaultState());
        assertTrue(CellSight.sees(water, new BlockPos(2, 0, 1), 0, 1, 0));
    }

    @Test
    void farCellsAreNotVisible() {
        assertFalse(CellSight.sees(new Terrain(), new BlockPos(20, 0, 0), 0, 1, 0));
    }

    @Test
    void surroundCellBehindOwnObsidian() {
        Terrain terrain = new Terrain()
            .set(1, 0, 0, obsidian()).set(-1, 0, 0, obsidian()).set(0, 0, -1, obsidian())
            .set(0, -1, 1, obsidian()).set(1, 0, 1, obsidian());
        assertTrue(CellSight.sees(terrain, new BlockPos(0, 0, 1), 0, 1, 0));
    }
}
