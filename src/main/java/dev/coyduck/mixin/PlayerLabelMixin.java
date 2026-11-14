package dev.coyduck.mixin;

import dev.coyduck.modules.NametagsPlus;
import net.minecraft.client.render.entity.LivingEntityRenderer;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LivingEntityRenderer.class)
public abstract class PlayerLabelMixin {
    @Inject(method = "hasLabel(Lnet/minecraft/entity/LivingEntity;D)Z", at = @At("HEAD"), cancellable = true)
    private void coyduck$hidePlayerLabel(LivingEntity entity, double distance, CallbackInfoReturnable<Boolean> info) {
        if (entity instanceof PlayerEntity && NametagsPlus.replacesVanilla()) info.setReturnValue(false);
    }
}
