package dev.coyduck.mixin;

import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.particle.ParticleEffect;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

@Mixin(LivingEntity.class)
public interface LivingEntityAccessor {
    @Accessor("POTION_SWIRLS")
    static TrackedData<List<ParticleEffect>> coyduck$potionSwirls() {
        throw new AssertionError();
    }
}
