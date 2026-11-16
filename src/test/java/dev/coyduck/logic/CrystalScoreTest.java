package dev.coyduck.logic;

import dev.coyduck.logic.CrystalScore.Choice;
import dev.coyduck.logic.CrystalScore.Rules;
import dev.coyduck.logic.CrystalScore.Target;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.BlockView;
import org.junit.jupiter.api.Test;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CrystalScoreTest {
    private static final Vec3d CRYSTAL = new Vec3d(0.5, 1.0, 0.5);
    private static final long KEY = BlockPos.ofFloored(CRYSTAL).asLong();
    private static final float EPSILON = 1.0E-4f;
    private static final Rules STANDARD = rules(false, 0, false, false);

    static final class Model implements CrystalScore.Model {
        final Map<Target, Float> damage = new IdentityHashMap<>();
        final Map<Target, Float> slack = new IdentityHashMap<>();
        float self;
        boolean friend;
        int raycasts;

        Model hit(Target target, float value) {
            damage.put(target, value);
            return this;
        }

        @Override
        public float bound(Target target, Vec3d crystal) {
            return damage.getOrDefault(target, 0.0f) + slack.getOrDefault(target, 0.0f);
        }

        @Override
        public float raw(Target target, Vec3d crystal, BlockView view) {
            raycasts++;
            return damage.getOrDefault(target, 0.0f);
        }

        @Override
        public float damage(Target target, float raw, long arrival) {
            return raw;
        }

        @Override
        public float self(Vec3d crystal, BlockView view, long arrival) {
            return self;
        }

        @Override
        public boolean hurtsFriend(Vec3d crystal, BlockView view) {
            return friend;
        }
    }

    private static Rules rules(boolean chip, double balance, boolean antiSurround, boolean affordPop) {
        return new Rules(12, 4, chip, 2, 2, balance, 0.5f, antiSurround, affordPop);
    }

    private static Target target(float health, float minDamage) {
        return target(health, minDamage, false, false, false);
    }

    private static Target target(float health, float minDamage, boolean focused, boolean totem, boolean gap) {
        LongSet gaps = new LongOpenHashSet();
        if (gap) gaps.add(KEY);
        return new Target(null, new Box(0, 0, 0, 1, 2, 1), Vec3d.ZERO, health, minDamage, gaps, null, focused, totem);
    }

    private static Choice evaluate(Model model, Rules rules, List<Target> targets, float health) {
        return new CrystalScore(model, rules).evaluate(targets, CRYSTAL, health, 0, null);
    }

    @Test
    void picksTheTargetThatLosesMore() {
        Target weak = target(36, 6);
        Target strong = target(36, 6);
        Model model = new Model().hit(weak, 10).hit(strong, 15);
        model.self = 2;
        Choice choice = evaluate(model, STANDARD, List.of(weak, strong), 36);
        assertNotNull(choice);
        assertSame(strong, choice.target());
        float expected = 15 - 2 * 0.5f + CrystalScore.LOW_HEALTH_BONUS * 15 / 36 + 10 * CrystalScore.OTHERS_WEIGHT;
        assertEquals(expected, choice.score(), EPSILON);
    }

    @Test
    void neverKillsItself() {
        Target enemy = target(20, 4);
        Model model = new Model().hit(enemy, 30);
        model.self = 20;
        CrystalScore score = new CrystalScore(model, STANDARD);
        assertNull(score.evaluate(List.of(enemy), CRYSTAL, 20, 0, null));
        assertTrue(score.verdicts().self());
    }

    @Test
    void sparesFriends() {
        Target enemy = target(20, 4);
        Model model = new Model().hit(enemy, 15);
        model.friend = true;
        CrystalScore score = new CrystalScore(model, STANDARD);
        assertNull(score.evaluate(List.of(enemy), CRYSTAL, 36, 0, null));
        assertTrue(score.verdicts().friend());
    }

    @Test
    void ignoresWeakHits() {
        Target enemy = target(36, 6);
        CrystalScore score = new CrystalScore(new Model().hit(enemy, 5), STANDARD);
        assertNull(score.evaluate(List.of(enemy), CRYSTAL, 36, 0, null));
        assertFalse(score.verdicts().self() || score.verdicts().friend() || score.verdicts().balance());
    }

    @Test
    void weakHitThatFinishesCounts() {
        Target enemy = target(3, 6);
        assertNotNull(evaluate(new Model().hit(enemy, 4), STANDARD, List.of(enemy), 36));
    }

    @Test
    void chipsOnlyWhileSelfDamageIsSmall() {
        Target enemy = target(36, 6);
        Rules chipping = rules(true, 0, false, false);
        Model model = new Model().hit(enemy, 3);
        model.self = 1;
        assertNotNull(evaluate(model, chipping, List.of(enemy), 36));
        model.self = 3;
        assertNull(evaluate(model, chipping, List.of(enemy), 36));
    }

    @Test
    void killWithoutTotemBeatsBiggerHit() {
        Target dying = target(4, 6);
        Target healthy = target(36, 6);
        Model model = new Model().hit(dying, 5).hit(healthy, 12);
        assertSame(dying, evaluate(model, STANDARD, List.of(dying, healthy), 36).target());
    }

    @Test
    void totemHalvesTheKillBonus() {
        Target holding = target(4, 6, false, true, false);
        Target bare = target(4, 6);
        Model model = new Model().hit(bare, 5).hit(holding, 5);
        assertSame(bare, evaluate(model, STANDARD, List.of(holding, bare), 36).target());
    }

    @Test
    void heavySelfDamageOnlyForKills() {
        Target enemy = target(36, 6);
        Model model = new Model().hit(enemy, 20);
        model.self = 13;
        CrystalScore score = new CrystalScore(model, STANDARD);
        assertNull(score.evaluate(List.of(enemy), CRYSTAL, 36, 0, null));
        assertTrue(score.verdicts().self());

        Target dying = target(10, 6);
        Model kill = new Model().hit(dying, 20);
        kill.self = 13;
        assertNotNull(evaluate(kill, STANDARD, List.of(dying), 36));

        Target guarded = target(10, 6, false, true, false);
        Model blocked = new Model().hit(guarded, 20);
        blocked.self = 13;
        assertNull(evaluate(blocked, STANDARD, List.of(guarded), 36));
    }

    @Test
    void goingLowNeedsAnAffordablePop() {
        Target dying = target(10, 6);
        Model model = new Model().hit(dying, 20);
        model.self = 7;
        assertNull(evaluate(model, STANDARD, List.of(dying), 10));
        assertNotNull(evaluate(model, rules(false, 0, false, true), List.of(dying), 10));
    }

    @Test
    void balanceRejectsTrades() {
        Target enemy = target(36, 4);
        Model model = new Model().hit(enemy, 5);
        model.self = 3;
        CrystalScore score = new CrystalScore(model, rules(false, 2, false, false));
        assertNull(score.evaluate(List.of(enemy), CRYSTAL, 36, 0, null));
        assertTrue(score.verdicts().balance());
        model.self = 2;
        assertNotNull(score.evaluate(List.of(enemy), CRYSTAL, 36, 0, null));
    }

    @Test
    void gapBonusOnlyWithAntiSurround() {
        Target enemy = target(36, 4, false, false, true);
        Model model = new Model().hit(enemy, 10);
        float plain = evaluate(model, STANDARD, List.of(enemy), 36).score();
        float bonus = evaluate(model, rules(false, 0, true, false), List.of(enemy), 36).score();
        assertEquals(CrystalScore.GAP_BONUS, bonus - plain, EPSILON);
    }

    @Test
    void focusBreaksTies() {
        Target first = target(36, 4);
        Target second = target(36, 4, true, false, false);
        Model model = new Model().hit(first, 10).hit(second, 10);
        assertSame(second, evaluate(model, STANDARD, List.of(first, second), 36).target());
    }

    @Test
    void earlierTargetKeepsTies() {
        Target first = target(36, 4);
        Target second = target(36, 4);
        Model model = new Model().hit(first, 10).hit(second, 10.4f);
        assertSame(first, evaluate(model, STANDARD, List.of(first, second), 36).target());
    }

    @Test
    void hopelessSpotsSkipRaycasts() {
        Target enemy = target(36, 4);
        Model model = new Model().hit(enemy, 10);
        assertNull(new CrystalScore(model, STANDARD).evaluate(List.of(enemy), CRYSTAL, 36, 0, null, 1000));
        assertEquals(0, model.raycasts);
    }

    @Test
    void pruningNeverChangesTheAnswer() {
        Random random = new Random(7);
        for (int round = 0; round < 5000; round++) {
            Rules rules = rules(random.nextBoolean(), random.nextInt(3), random.nextBoolean(), random.nextBoolean());
            Model model = new Model();
            Target[] targets = new Target[1 + random.nextInt(3)];
            for (int i = 0; i < targets.length; i++) {
                targets[i] = target(1 + random.nextInt(36), 1 + random.nextInt(8), random.nextBoolean(), random.nextBoolean(), random.nextBoolean());
                model.hit(targets[i], random.nextFloat() * 40);
                model.slack.put(targets[i], random.nextFloat() * 10);
            }
            model.self = random.nextFloat() * 14;
            float health = 1 + random.nextInt(36);
            CrystalScore score = new CrystalScore(model, rules);
            Choice full = score.evaluate(List.of(targets), CRYSTAL, health, 0, null);
            if (full == null) continue;
            Choice pruned = score.evaluate(List.of(targets), CRYSTAL, health, 0, null, full.score() - CrystalScore.TIE);
            assertEquals(full, pruned, "round " + round);
        }
    }

    @Test
    void choicesAreAlwaysSurvivable() {
        Random random = new Random(11);
        for (int round = 0; round < 5000; round++) {
            Target enemy = target(1 + random.nextInt(36), 1 + random.nextInt(8), false, random.nextBoolean(), false);
            Model model = new Model().hit(enemy, random.nextFloat() * 40);
            model.self = random.nextFloat() * 40;
            float health = 1 + random.nextInt(36);
            Choice choice = evaluate(model, rules(false, 0, false, random.nextBoolean()), List.of(enemy), health);
            if (choice == null) continue;
            assertTrue(choice.self() < health, "round " + round);
            assertTrue(choice.self() <= 12 || choice.damage() >= enemy.health() + CrystalScore.LETHAL_MARGIN && !enemy.totem(), "round " + round);
        }
    }

    @Test
    void tiesGoToLessSelfDamage() {
        Target enemy = target(36, 4);
        Choice safer = new Choice(enemy, 10, 10, 1, 10.0f);
        Choice riskier = new Choice(enemy, 10, 10, 3, 10.4f);
        assertTrue(safer.beats(riskier));
        assertFalse(riskier.beats(safer));
        assertTrue(riskier.beats(null));
    }

    @Test
    void rulesHelpers() {
        Rules rules = rules(true, 2, false, false);
        assertTrue(rules.leavesLow(17, 20));
        assertFalse(rules.leavesLow(0, 2));
        assertTrue(rules.balanced(6, 3, 36));
        assertFalse(rules.balanced(5, 3, 36));
        assertTrue(rules.balanced(5, 3, 5));
        assertEquals(2.0f, rules.floor(target(36, 6), true));
        assertEquals(6.0f, rules.floor(target(36, 6), false));
    }
}
