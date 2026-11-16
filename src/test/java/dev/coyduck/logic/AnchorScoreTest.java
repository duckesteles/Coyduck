package dev.coyduck.logic;

import dev.coyduck.logic.AnchorScore.Choice;
import dev.coyduck.logic.AnchorScore.Rules;
import dev.coyduck.logic.AnchorScore.Spot;
import dev.coyduck.logic.AnchorScore.Target;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnchorScoreTest {
    private static final Blast.Defense BARE = new Blast.Defense(0, 0, -1, 0);
    private static final Rules RULES = new Rules(10, 4, false);
    private static final BlockPos A = new BlockPos(1, 0, 0);
    private static final BlockPos B = new BlockPos(2, 0, 0);
    private static final float EPSILON = 1.0E-4f;

    static final class Model implements AnchorScore.Model {
        final Map<BlockPos, Map<Target, Float>> raws = new HashMap<>();
        final Map<BlockPos, Float> slack = new HashMap<>();
        final Map<BlockPos, Float> self = new HashMap<>();
        final Set<BlockPos> costly = new HashSet<>();
        final Set<BlockPos> friends = new HashSet<>();
        final Set<BlockPos> blocked = new HashSet<>();
        float timedShare = 1.0f;
        BlockPos aim;

        Model hit(BlockPos pos, Target target, float raw) {
            raws.computeIfAbsent(pos, key -> new IdentityHashMap<>()).put(target, raw);
            return this;
        }

        private float raw(BlockPos pos, Target target) {
            return raws.getOrDefault(pos, Map.of()).getOrDefault(target, 0.0f);
        }

        @Override
        public float rawBound(Target target, Vec3d center) {
            BlockPos pos = BlockPos.ofFloored(center);
            return raw(pos, target) + slack.getOrDefault(pos, 0.0f);
        }

        @Override
        public float raw(BlockPos pos, int index, Target target) {
            return raw(pos, target);
        }

        @Override
        public float timed(Target target, float raw, long timing) {
            return raw * timedShare;
        }

        @Override
        public float selfRaw(BlockPos pos) {
            return self.getOrDefault(pos, 0.0f);
        }

        @Override
        public float selfFull(float raw) {
            return raw;
        }

        @Override
        public float selfTimed(float raw, long arrival) {
            return raw;
        }

        @Override
        public boolean costly(BlockPos pos) {
            return costly.contains(pos);
        }

        @Override
        public boolean hurtsFriend(BlockPos pos, long arrival) {
            return friends.contains(pos);
        }

        @Override
        public boolean aimed(BlockPos pos) {
            return pos.equals(aim);
        }

        @Override
        public boolean placeable(Spot spot) {
            return !blocked.contains(spot.pos());
        }
    }

    private static Target target(float health, float minDamage) {
        return target(health, minDamage, false);
    }

    private static Target target(float health, float minDamage, boolean focused) {
        return new Target(null, new Box(0, 0, 0, 1, 2, 1), Vec3d.ZERO, BlockPos.ORIGIN, health, minDamage, BARE, focused, true);
    }

    private static AnchorScore scorer(Model model) {
        AnchorScore score = new AnchorScore(model);
        score.rules(RULES);
        return score;
    }

    private static long all(int count) {
        return (1L << count) - 1;
    }

    @Test
    void tradesAPopOnlyForAKill() {
        Target armed = target(4, 6);
        Target bare = new Target(null, new Box(0, 0, 0, 1, 2, 1), Vec3d.ZERO, BlockPos.ORIGIN, 4, 6, BARE, false, false);
        Model model = new Model().hit(A, armed, 5).hit(B, bare, 5);
        model.self.put(A, 9.0f);
        model.self.put(B, 9.0f);
        AnchorScore score = new AnchorScore(model);
        score.rules(new Rules(10, 4, true));
        assertNull(score.evaluate(List.of(armed), A, 8, 0, 0, false, all(1)));
        assertNotNull(score.evaluate(List.of(bare), B, 8, 0, 0, false, all(1)));
        score.rules(RULES);
        assertNull(score.evaluate(List.of(bare), B, 8, 0, 0, false, all(1)));
    }

    @Test
    void killTradeAcceptsHeavySelfDamageWithoutAPop() {
        Target bare = new Target(null, new Box(0, 0, 0, 1, 2, 1), Vec3d.ZERO, BlockPos.ORIGIN, 4, 6, BARE, false, false);
        Model model = new Model().hit(A, bare, 5);
        model.self.put(A, 15.0f);
        AnchorScore score = new AnchorScore(model);
        score.rules(new Rules(10, 4, true));
        assertNotNull(score.evaluate(List.of(bare), A, 20, 0, 0, false, all(1)));
    }

    @Test
    void bareKillsRaiseTheSpotCeiling() {
        Target bare = new Target(null, new Box(0, 0, 0, 1, 2, 1), Vec3d.ZERO, BlockPos.ORIGIN, 4, 6, BARE, false, false);
        Spot spot = scorer(new Model().hit(A, bare, 5)).spot(List.of(bare), A, false, 0);
        assertEquals(5 + AnchorScore.LETHAL_BONUS + AnchorScore.KILL_BONUS, spot.potential(), EPSILON);
    }

    @Test
    void weakSpotsAreUnreachable() {
        Target enemy = target(36, 6);
        assertNull(scorer(new Model().hit(A, enemy, 5)).spot(List.of(enemy), A, false, 0));
    }

    @Test
    void spotPotentialCountsAimFocusAndKills() {
        Target dying = target(4, 6, true);
        Model model = new Model().hit(A, dying, 5);
        model.aim = A;
        Spot spot = scorer(model).spot(List.of(dying), A, false, 0);
        assertNotNull(spot);
        assertEquals(5 + AnchorScore.AIM_BONUS + AnchorScore.FOCUS_BONUS + AnchorScore.LETHAL_BONUS, spot.potential(), EPSILON);
        assertEquals(1L, spot.reachable());
    }

    @Test
    void hurtSyncNarrowsReachableTargets() {
        Target enemy = target(36, 6);
        Model model = new Model().hit(A, enemy, 10);
        model.timedShare = 0.5f;
        Spot spot = scorer(model).spot(List.of(enemy), A, false, 1);
        assertEquals(1L, spot.reachable());
        assertEquals(0L, spot.timedReachable());
    }

    @Test
    void evaluatePicksTheBiggestHit() {
        Target weak = target(36, 4);
        Target strong = target(36, 4);
        Model model = new Model().hit(A, weak, 6).hit(A, strong, 9);
        Choice choice = scorer(model).evaluate(List.of(weak, strong), A, 36, 0, 0, false, all(2));
        assertSame(strong, choice.target());
        assertEquals(9, choice.damage(), EPSILON);
    }

    @Test
    void neverDetonatesIntoDeath() {
        Target enemy = target(36, 4);
        Model model = new Model().hit(A, enemy, 9);
        model.self.put(A, 6.0f);
        AnchorScore score = scorer(model);
        assertNull(score.evaluate(List.of(enemy), A, 7, 0, 0, false, all(1)));
        assertTrue(score.verdicts().self());
    }

    @Test
    void selfDamageCapHoldsEvenForKills() {
        Target dying = target(4, 4);
        Model model = new Model().hit(A, dying, 9);
        model.self.put(A, 11.0f);
        assertNull(scorer(model).evaluate(List.of(dying), A, 36, 0, 0, false, all(1)));
    }

    @Test
    void lowHealthOnlyForFinishers() {
        Target healthy = target(36, 4);
        Target dying = target(5, 4);
        Model model = new Model().hit(A, healthy, 9).hit(B, dying, 9);
        model.self.put(A, 6.0f);
        model.self.put(B, 6.0f);
        AnchorScore score = scorer(model);
        assertNull(score.evaluate(List.of(healthy), A, 10, 0, 0, false, all(1)));
        assertNotNull(score.evaluate(List.of(dying), B, 10, 0, 0, false, all(1)));
    }

    @Test
    void harmlessSelfDamageIgnoresMinHealth() {
        Target enemy = target(36, 4);
        Model model = new Model().hit(A, enemy, 9);
        model.self.put(A, 0.4f);
        assertNotNull(scorer(model).evaluate(List.of(enemy), A, 4.5f, 0, 0, false, all(1)));
    }

    @Test
    void sparesFriends() {
        Target enemy = target(36, 4);
        Model model = new Model().hit(A, enemy, 9);
        model.friends.add(A);
        AnchorScore score = scorer(model);
        assertNull(score.evaluate(List.of(enemy), A, 36, 0, 0, false, all(1)));
        assertTrue(score.verdicts().friend());
    }

    @Test
    void costlySpotsOnlyForKills() {
        Target healthy = target(36, 4);
        Target dying = target(5, 4);
        Model model = new Model().hit(A, healthy, 9).hit(A, dying, 9);
        model.costly.add(A);
        Choice choice = scorer(model).evaluate(List.of(healthy, dying), A, 36, 0, 0, false, all(2));
        assertSame(dying, choice.target());
        assertNull(scorer(model).evaluate(List.of(healthy), A, 36, 0, 0, false, all(1)));
    }

    @Test
    void unreachableTargetsAreSkipped() {
        Target first = target(36, 4);
        Target second = target(36, 4);
        Model model = new Model().hit(A, first, 6).hit(A, second, 9);
        assertSame(first, scorer(model).evaluate(List.of(first, second), A, 36, 0, 0, false, 1L).target());
    }

    @Test
    void chargedAnchorsWinTies() {
        Target enemy = target(36, 4);
        Model model = new Model().hit(A, enemy, 9).hit(B, enemy, 9);
        AnchorScore score = scorer(model);
        Spot placed = score.spot(List.of(enemy), A, false, 0);
        Spot charged = score.spot(List.of(enemy), B, true, 0);
        assertEquals(B, score.pick(List.of(placed, charged), List.of(enemy), 36, 0, 0).pos());
    }

    @Test
    void unplaceableSpotsAreSkipped() {
        Target enemy = target(36, 4);
        Model model = new Model().hit(A, enemy, 12).hit(B, enemy, 9);
        model.blocked.add(A);
        AnchorScore score = scorer(model);
        List<Spot> spots = List.of(score.spot(List.of(enemy), A, false, 0), score.spot(List.of(enemy), B, false, 0));
        assertEquals(B, score.pick(spots, List.of(enemy), 36, 0, 0).pos());
    }

    @Test
    void pickMatchesExhaustiveSearch() {
        Random random = new Random(13);
        for (int round = 0; round < 3000; round++) {
            Model model = new Model();
            List<Target> targets = new ArrayList<>();
            for (int i = 0, count = 1 + random.nextInt(3); i < count; i++) targets.add(target(1 + random.nextInt(36), 1 + random.nextInt(8), random.nextBoolean()));
            List<BlockPos> positions = new ArrayList<>();
            for (int i = 0, count = 1 + random.nextInt(6); i < count; i++) {
                BlockPos pos = new BlockPos(i, 0, 0);
                positions.add(pos);
                for (Target target : targets) model.hit(pos, target, random.nextFloat() * 20);
                model.slack.put(pos, random.nextFloat() * 5);
                model.self.put(pos, random.nextFloat() * 8);
                if (random.nextInt(5) == 0) model.costly.add(pos);
                if (random.nextInt(8) == 0) model.blocked.add(pos);
            }
            if (random.nextBoolean()) model.aim = positions.get(random.nextInt(positions.size()));
            long timing = random.nextBoolean() ? 1 : 0;
            if (timing != 0) model.timedShare = 0.1f + random.nextFloat() * 0.9f;
            float health = 1 + random.nextInt(36);
            AnchorScore score = scorer(model);
            List<Spot> spots = new ArrayList<>();
            for (BlockPos pos : positions) {
                Spot spot = score.spot(targets, pos, random.nextBoolean(), timing);
                if (spot != null) spots.add(spot);
            }

            Choice picked = score.pick(spots, targets, health, timing, 0);
            Choice best = null;
            for (Spot spot : spots) {
                long reachable = timing != 0 ? spot.timedReachable() : spot.reachable();
                if (reachable == 0) continue;
                Choice choice = score.evaluate(targets, spot.pos(), health, timing, 0, spot.charged(), reachable);
                if (choice != null && model.placeable(spot) && choice.beats(best)) best = choice;
            }
            assertEquals(best == null, picked == null, "round " + round);
            if (best != null) assertEquals(best.rank(), picked.rank(), EPSILON, "round " + round);
        }
    }

    @Test
    void beatsUsesRank() {
        Target enemy = target(36, 4);
        Choice safe = new Choice(A, enemy, 9, 0, 9, false);
        Choice risky = new Choice(B, enemy, 9, 10, 9, false);
        assertTrue(safe.beats(risky));
        assertFalse(risky.beats(safe));
        assertTrue(risky.beats(null));
    }
}
