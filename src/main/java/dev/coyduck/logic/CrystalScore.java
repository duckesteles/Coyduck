package dev.coyduck.logic;

import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.BlockView;

import java.util.List;

public final class CrystalScore {
    public static final float TIE = 0.5f;
    public static final float KEEP_BONUS = 0.75f;
    public static final float LETHAL_MARGIN = 0.5f;
    static final float LETHAL_BONUS = 100.0f;
    static final float FOCUS_BONUS = 1.0f;
    static final float GAP_BONUS = 2.0f;
    static final float OTHERS_WEIGHT = 0.3f;
    static final float LOW_HEALTH_BONUS = 3.0f;

    public record Target(PlayerEntity player, Box box, Vec3d feet, float health, float minDamage, LongSet gaps) {}

    public record Choice(Target target, float damage, float raw, float self, float score) {
        public boolean beats(Choice other) {
            if (other == null) return true;
            if (Math.abs(score - other.score) > TIE) return score > other.score;
            return self < other.self;
        }

        public Choice withScore(float value) {
            return value == score ? this : new Choice(target, damage, raw, self, value);
        }
    }

    public interface Rules {
        double maxSelf();

        double minHealth();

        boolean chip();

        float chipDamage();

        double chipSelf();

        double balance();

        float selfWeight();

        boolean antiSurround();

        default boolean leavesLow(float self, float health) {
            return self > 0 && health - self < minHealth();
        }

        default boolean balanced(float damage, float self, float health) {
            return balance() <= 0 || damage >= health || damage >= self * balance();
        }

        default float floor(Target target, boolean chipping) {
            return chip() && chipping ? Math.min(target.minDamage, chipDamage()) : target.minDamage;
        }
    }

    public interface Model {
        float bound(Target target, Vec3d crystal);

        float raw(Target target, Vec3d crystal, BlockView view);

        float damage(Target target, float raw, long arrival);

        float self(Vec3d crystal, BlockView view, long arrival);

        boolean hurtsFriend(Vec3d crystal, BlockView view);

        boolean focused(Target target);

        boolean holdsTotem(Target target);

        boolean canAffordPop();
    }

    public static final class Verdicts {
        private boolean self;
        private boolean friend;
        private boolean balance;

        public void clear() {
            self = false;
            friend = false;
            balance = false;
        }

        public boolean self() {
            return self;
        }

        public boolean friend() {
            return friend;
        }

        public boolean balance() {
            return balance;
        }
    }

    private final Model model;
    private final Rules rules;
    private final Verdicts verdicts = new Verdicts();
    private float[] raws = new float[0];
    private float[] damages = new float[0];

    public CrystalScore(Model model, Rules rules) {
        this.model = model;
        this.rules = rules;
    }

    public Verdicts verdicts() {
        return verdicts;
    }

    public Choice evaluate(List<Target> targets, Vec3d crystal, float health, long arrival, BlockView view) {
        return evaluate(targets, crystal, health, arrival, view, -Float.MAX_VALUE);
    }

    public Choice evaluate(List<Target> targets, Vec3d crystal, float health, long arrival, BlockView view, float beat) {
        long key = BlockPos.ofFloored(crystal).asLong();
        int count = targets.size();
        if (raws.length < count) {
            raws = new float[count];
            damages = new float[count];
        }

        float upper = -Float.MAX_VALUE;
        float spread = 0;
        for (int i = 0; i < count; i++) {
            Target target = targets.get(i);
            float bound = model.bound(target, crystal);
            damages[i] = Float.NaN;
            if (bound < rules.floor(target, true) && bound < target.health) continue;
            damages[i] = bound;
            float capped = Math.min(bound, target.health);
            spread += capped;
            upper = Math.max(upper, capped + bonuses(target, bound, key));
        }
        if (upper == -Float.MAX_VALUE || upper + spread * OTHERS_WEIGHT + KEEP_BONUS < beat) return null;

        upper = -Float.MAX_VALUE;
        spread = 0;
        for (int i = 0; i < count; i++) {
            if (Float.isNaN(damages[i])) continue;
            Target target = targets.get(i);
            float raw = model.raw(target, crystal, view);
            float damage = model.damage(target, raw, arrival);
            raws[i] = raw;
            if (damage < rules.floor(target, true) && damage < target.health) {
                damages[i] = Float.NaN;
                continue;
            }
            damages[i] = damage;
            float capped = Math.min(damage, target.health);
            spread += capped;
            upper = Math.max(upper, capped + bonuses(target, damage, key));
        }
        if (upper == -Float.MAX_VALUE || upper + spread * OTHERS_WEIGHT + KEEP_BONUS < beat) return null;

        float self = model.self(crystal, view, arrival);
        if (self >= health) {
            verdicts.self = true;
            return null;
        }
        boolean low = rules.leavesLow(self, health);
        boolean overSelf = self > rules.maxSelf();
        if (model.hurtsFriend(crystal, view)) {
            verdicts.friend = true;
            return null;
        }

        boolean chipping = !low && self <= rules.chipSelf();
        Choice best = null;
        float total = 0;
        for (int i = 0; i < count; i++) {
            float damage = damages[i];
            if (Float.isNaN(damage)) continue;
            Target target = targets.get(i);
            if (damage < rules.floor(target, chipping) && damage < target.health) continue;
            boolean lethal = damage >= target.health + LETHAL_MARGIN;
            boolean kills = lethal && !model.holdsTotem(target);
            if (overSelf && !(kills && !low) || low && !(lethal && model.canAffordPop())) {
                verdicts.self = true;
                continue;
            }
            if (!rules.balanced(damage, self, target.health)) {
                verdicts.balance = true;
                continue;
            }

            float counted = Math.min(damage, target.health);
            total += counted;
            float score = counted - self * rules.selfWeight() + (model.focused(target) ? FOCUS_BONUS : 0)
                + LOW_HEALTH_BONUS * Math.min(damage / Math.max(target.health, 1.0f), 1.0f);
            if (lethal) score += LETHAL_BONUS;
            if (kills) score += LETHAL_BONUS;
            if (rules.antiSurround() && target.gaps.contains(key)) score += GAP_BONUS;
            Choice choice = new Choice(target, damage, raws[i], self, score);
            if (choice.beats(best)) best = choice;
        }

        if (best == null) return null;
        float others = total - Math.min(best.damage, best.target.health);
        return others > 0 ? best.withScore(best.score + others * OTHERS_WEIGHT) : best;
    }

    private float bonuses(Target target, float damage, long key) {
        float bonus = (model.focused(target) ? FOCUS_BONUS : 0) + LOW_HEALTH_BONUS * Math.min(damage / Math.max(target.health, 1.0f), 1.0f);
        if (damage >= target.health + LETHAL_MARGIN) bonus += LETHAL_BONUS * (model.holdsTotem(target) ? 1 : 2);
        if (rules.antiSurround() && target.gaps.contains(key)) bonus += GAP_BONUS;
        return bonus;
    }
}
