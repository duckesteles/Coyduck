package dev.coyduck.logic;

import dev.coyduck.testing.Terrain;
import dev.coyduck.testing.Vanilla;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ShapeContext;
import net.minecraft.entity.DamageUtil;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Difficulty;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(Vanilla.class)
class BlastTest {
    private static final float CRYSTAL = 6.0f;
    private static final float EPSILON = 1.0E-4f;
    private static final Box PLAYER = new Box(-0.3, 0.0, -0.3, 0.3, 1.8, 0.3);
    private static final ShapeContext CONTEXT = ShapeContext.absent();

    @Test
    void crystalAtFeetDealsEightyFive() {
        assertEquals(85.0f, Blast.damage(0.0, 1.0, CRYSTAL), EPSILON);
    }

    @Test
    void damageFollowsVanillaCurve() {
        assertEquals(32.5f, Blast.damage(0.5, 1.0, CRYSTAL), EPSILON);
        assertEquals(1.0f, Blast.damage(1.0, 1.0, CRYSTAL), EPSILON);
        assertEquals(1.0f, Blast.damage(0.3, 0.0, CRYSTAL), EPSILON);
    }

    @Test
    void distanceIsRelativeToBlastDiameter() {
        assertEquals(0.5, Blast.distance(Vec3d.ZERO, new Vec3d(6, 0, 0), CRYSTAL), 1.0E-9);
        assertTrue(Blast.distance(Vec3d.ZERO, new Vec3d(12.01, 0, 0), CRYSTAL) > 1.0);
    }

    @Test
    void difficultyScalesLikePlayerDamage() {
        assertEquals(0.0f, Blast.difficulty(20.0f, Difficulty.PEACEFUL));
        assertEquals(11.0f, Blast.difficulty(20.0f, Difficulty.EASY));
        assertEquals(1.0f, Blast.difficulty(1.0f, Difficulty.EASY));
        assertEquals(20.0f, Blast.difficulty(20.0f, Difficulty.NORMAL));
        assertEquals(30.0f, Blast.difficulty(20.0f, Difficulty.HARD));
    }

    @Test
    void netheriteArmorFloorsAtFourPoints() {
        assertEquals(71.4f, Blast.armor(85.0f, 20.0f, 12.0f), EPSILON);
    }

    @Test
    void armorCapsAtTwentyPoints() {
        assertEquals(0.928f, Blast.armor(4.0f, 20.0f, 12.0f), EPSILON);
        assertEquals(10.0f, Blast.armor(10.0f, 0.0f, 0.0f), EPSILON);
    }

    @Test
    void resistanceRemovesFivePerLevel() {
        assertEquals(16.0f, Blast.resistance(20.0f, 0), EPSILON);
        assertEquals(0.0f, Blast.resistance(20.0f, 4), EPSILON);
        assertEquals(0.0f, Blast.resistance(20.0f, 9), EPSILON);
    }

    @Test
    void protectionMatchesVanilla() {
        for (int points = 0; points <= 40; points++) {
            assertEquals(points == 0 ? 50.0f : DamageUtil.getInflictedDamage(50.0f, points), Blast.protection(50.0f, points), EPSILON);
        }
        assertEquals(18.0f, Blast.protection(50.0f, 16), EPSILON);
        assertEquals(10.0f, Blast.protection(50.0f, 32), EPSILON);
    }

    @Test
    void openAirIsFullyExposed() {
        assertEquals(1.0f, Blast.exposure(new Vec3d(3.5, 0.5, 0.5), PLAYER, new Terrain(), CONTEXT));
    }

    @Test
    void obsidianWallBlocksEverything() {
        Terrain terrain = new Terrain().fill(2, -1, -2, 2, 3, 2, obsidian());
        assertEquals(0.0f, Blast.exposure(new Vec3d(3.5, 0.5, 0.5), PLAYER, terrain, CONTEXT));
    }

    @Test
    void halfWallBlocksPartOfTheRays() {
        Terrain terrain = new Terrain().fill(1, 0, -2, 1, 0, 2, obsidian());
        float exposure = Blast.exposure(new Vec3d(2.5, 1.0, 0.0), PLAYER, terrain, CONTEXT);
        assertTrue(exposure > 0.0f && exposure < 1.0f, "exposure " + exposure);
    }

    @Test
    void sourceBlockCountsAsRemoved() {
        Terrain terrain = new Terrain().set(3, 0, 0, Blocks.RESPAWN_ANCHOR.getDefaultState());
        assertEquals(1.0f, Blast.exposure(new Vec3d(3.5, 0.5, 0.5), PLAYER, terrain, CONTEXT));
    }

    @Test
    void exposureIsAFractionOfFortyFiveRays() {
        Terrain terrain = new Terrain().fill(1, 0, -2, 1, 0, 2, obsidian());
        float exposure = Blast.exposure(new Vec3d(2.5, 1.0, 0.0), PLAYER, terrain, CONTEXT);
        float rays = exposure * 45.0f;
        assertEquals(Math.round(rays), rays, EPSILON);
    }

    private static BlockState obsidian() {
        return Blocks.OBSIDIAN.getDefaultState();
    }
}
