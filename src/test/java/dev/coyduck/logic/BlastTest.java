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

import java.util.Random;

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
        assertEquals(2.0f, Blast.armor(10.0f, 30.0f, 0.0f), EPSILON);
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
    void cobwebAndWaterDoNotShield() {
        Terrain webs = new Terrain().fill(2, -1, -2, 2, 3, 2, Blocks.COBWEB.getDefaultState());
        Terrain water = new Terrain().fill(2, -1, -2, 2, 3, 2, Blocks.WATER.getDefaultState());
        Vec3d source = new Vec3d(3.5, 0.5, 0.5);
        assertEquals(1.0f, Blast.exposure(source, PLAYER, webs, CONTEXT));
        assertEquals(1.0f, Blast.exposure(source, PLAYER, water, CONTEXT));
    }

    @Test
    void slabsShieldOnlyTheirHalf() {
        Terrain solid = new Terrain().fill(2, 0, -2, 2, 0, 2, Blocks.OBSIDIAN.getDefaultState());
        Terrain halves = new Terrain().fill(2, 0, -2, 2, 0, 2, Blocks.STONE_SLAB.getDefaultState());
        Vec3d source = new Vec3d(3.5, 0.6, 0.0);
        float full = Blast.exposure(source, PLAYER, solid, CONTEXT);
        float half = Blast.exposure(source, PLAYER, halves, CONTEXT);
        assertTrue(full < half && half < 1.0f, full + " " + half);
    }

    @Test
    void negativeSourceBlockCountsAsRemoved() {
        Terrain terrain = new Terrain().set(-4, 0, -1, Blocks.RESPAWN_ANCHOR.getDefaultState());
        Box box = PLAYER.offset(-7, 0, -1);
        assertEquals(1.0f, Blast.exposure(new Vec3d(-3.5, 0.5, -0.5), box, terrain, CONTEXT));
    }

    @Test
    void defenseAppliesArmorThenResistanceThenProtection() {
        float armored = Blast.armor(85.0f, 20.0f, 12.0f);
        float resisted = Blast.resistance(armored, 0);
        assertEquals(Blast.protection(resisted, 16), new Blast.Defense(20, 12, 0, 16).reduce(85.0f), EPSILON);
        assertEquals(Blast.protection(armored, 16), new Blast.Defense(20, 12, -1, 16).reduce(85.0f), EPSILON);
        assertEquals(0.0f, new Blast.Defense(20, 12, 4, 16).reduce(85.0f));
        assertEquals(0.0f, new Blast.Defense(20, 12, -1, 16).reduce(0.0f));
    }

    @Test
    void reductionsNeverIncreaseWithLessDamage() {
        Random random = new Random(3);
        for (int round = 0; round < 10000; round++) {
            float low = random.nextFloat() * 100;
            float high = low + random.nextFloat() * 100;
            Blast.Defense defense = new Blast.Defense(random.nextInt(31), random.nextInt(21), random.nextInt(6) - 1, random.nextInt(41));
            Difficulty difficulty = Difficulty.values()[random.nextInt(4)];
            assertTrue(defense.reduce(low) <= defense.reduce(high) + EPSILON, "defense " + round);
            assertTrue(Blast.difficulty(low, difficulty) <= Blast.difficulty(high, difficulty) + EPSILON, "difficulty " + round);
        }
    }

    @Test
    void damageGrowsWithExposureAndCloseness() {
        Random random = new Random(5);
        for (int round = 0; round < 10000; round++) {
            double distance = random.nextDouble();
            double exposure = random.nextDouble();
            float damage = Blast.damage(distance, exposure, CRYSTAL);
            assertTrue(damage <= Blast.damage(distance, Math.min(1.0, exposure + random.nextDouble() * 0.2), CRYSTAL), "exposure " + round);
            assertTrue(damage <= Blast.damage(Math.max(0.0, distance - random.nextDouble() * 0.2), exposure, CRYSTAL), "distance " + round);
        }
    }

    private static BlockState obsidian() {
        return Blocks.OBSIDIAN.getDefaultState();
    }
}
