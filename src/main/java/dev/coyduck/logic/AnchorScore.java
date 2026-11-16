package dev.coyduck.logic;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class AnchorScore {
    public static final float FIRE_MARGIN = 1.0f;
    static final float LETHAL_BONUS = 100.0f;
    static final float FOCUS_BONUS = 1.0f;
    static final float AIM_BONUS = 1.0f;
    static final float CHARGED_TIE = 1.0f;
    static final float SELF_WEIGHT = 0.05f;
    static final float HARMLESS = 0.5f;

    public record Target(PlayerEntity player, Box box, Vec3d feet, BlockPos block, float health, float minDamage, Blast.Defense defense, boolean focused) {}

    public record Choice(BlockPos pos, Target target, float damage, float self, float score, boolean charged) {
        float rank() {
            return score + (charged ? CHARGED_TIE : 0) - SELF_WEIGHT * self;
        }

        public boolean beats(Choice other) {
            return other == null || rank() > other.rank();
        }
    }

    public record Spot(BlockPos pos, boolean charged, float potential, float timedPotential, long reachable, long timedReachable) {}

    public record Rules(double maxSelf, double minHealth) {}

    public interface Model {
        float rawBound(Target target, Vec3d center);

        float raw(BlockPos pos, int index, Target target);

        float timed(Target target, float raw, long timing);

        float selfRaw(BlockPos pos);

        float selfFull(float raw);

        float selfTimed(float raw, long arrival);

        boolean costly(BlockPos pos);

        boolean hurtsFriend(BlockPos pos, long arrival);

        boolean aimed(BlockPos pos);

        boolean placeable(Spot spot);
    }

    private final Model model;
    private final Verdicts verdicts = new Verdicts();
    private Rules rules;

    public AnchorScore(Model model) {
        this.model = model;
    }

    public void rules(Rules value) {
        rules = value;
    }

    public Verdicts verdicts() {
        return verdicts;
    }

    public Spot spot(List<Target> targets, BlockPos pos, boolean charged, long timing) {
        Vec3d center = Vec3d.ofCenter(pos);
        float bonus = model.aimed(pos) ? AIM_BONUS : 0;
        float potential = -1;
        float timedPotential = -1;
        long reachable = 0;
        long timedReachable = 0;
        for (int i = 0; i < targets.size(); i++) {
            Target target = targets.get(i);
            float rawBound = model.rawBound(target, center);
            float bound = target.defense.reduce(rawBound);
            if (bound < target.minDamage && bound < target.health) continue;
            float extra = bonus + (target.focused ? FOCUS_BONUS : 0);
            potential = Math.max(potential, bound + extra + (bound >= target.health ? LETHAL_BONUS : 0));
            reachable |= 1L << i;

            float timed = timing != 0 ? model.timed(target, rawBound, timing) : bound;
            if (timed < target.minDamage && timed < target.health) continue;
            timedPotential = Math.max(timedPotential, timed + extra + (timed >= target.health ? LETHAL_BONUS : 0));
            timedReachable |= 1L << i;
        }
        return reachable == 0 ? null : new Spot(pos, charged, potential, timedPotential, reachable, timedReachable);
    }

    public Choice pick(List<Spot> spots, List<Target> targets, float health, long timing, long arrival) {
        boolean timed = timing != 0;
        List<Spot> order = new ArrayList<>(spots.size());
        for (Spot spot : spots) {
            if (!timed || spot.timedReachable != 0) order.add(spot);
        }
        order.sort(Comparator.comparingDouble((Spot spot) -> ceiling(spot, timed)).reversed());

        Choice best = null;
        for (Spot spot : order) {
            if (best != null && ceiling(spot, timed) <= best.rank()) break;
            Choice choice = evaluate(targets, spot.pos, health, timing, arrival, spot.charged, timed ? spot.timedReachable : spot.reachable);
            if (choice == null || !choice.beats(best) || !model.placeable(spot)) continue;
            best = choice;
        }
        return best;
    }

    public Choice evaluate(List<Target> targets, BlockPos pos, float health, long timing, long arrival, boolean charged, long reachable) {
        int costly = -1;
        float bonus = model.aimed(pos) ? AIM_BONUS : 0;

        Choice best = null;
        for (int i = 0; i < targets.size(); i++) {
            if ((reachable & 1L << i) == 0) continue;
            Target target = targets.get(i);
            float raw = model.raw(pos, i, target);
            float damage = timing != 0 ? model.timed(target, raw, timing) : target.defense.reduce(raw);
            if (damage < target.minDamage && damage < target.health) continue;
            if (damage < target.health) {
                if (costly < 0) costly = model.costly(pos) ? 1 : 0;
                if (costly == 1) continue;
            }

            float score = damage + bonus + (target.focused ? FOCUS_BONUS : 0) + (damage >= target.health ? LETHAL_BONUS : 0);
            Choice choice = new Choice(pos, target, damage, 0, score, charged);
            if (choice.beats(best)) best = choice;
        }
        if (best == null) return null;

        float selfRaw = model.selfRaw(pos);
        float full = model.selfFull(selfRaw);
        float self = model.selfTimed(selfRaw, arrival);
        boolean finishing = best.damage >= best.target.health;
        if (full + FIRE_MARGIN >= health || self > rules.maxSelf || self >= HARMLESS && !finishing && health - self - FIRE_MARGIN < rules.minHealth) {
            verdicts.self = true;
            return null;
        }
        if (model.hurtsFriend(pos, arrival)) {
            verdicts.friend = true;
            return null;
        }
        return new Choice(pos, best.target, best.damage, self, best.score, charged);
    }

    private static float ceiling(Spot spot, boolean timed) {
        return (timed ? spot.timedPotential : spot.potential) + (spot.charged ? CHARGED_TIE : 0);
    }
}
