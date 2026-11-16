package dev.coyduck.managers;

import dev.coyduck.mixin.LivingEntityAccessor;
import net.minecraft.util.math.MathHelper;

import static dev.coyduck.core.Core.mc;

public final class AttackCharge {
    private AttackCharge() {}

    public static float server(float lead) {
        int ticks = ((LivingEntityAccessor) mc.player).coyduck$ticksSinceLastAttack();
        float full = mc.player.getAttackCooldownProgressPerTick();
        return MathHelper.clamp((ticks + lead) * TickRate.get().scale() / full, 0.0f, 1.0f);
    }
}
