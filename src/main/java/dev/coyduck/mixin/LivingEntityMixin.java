package dev.coyduck.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.coyduck.managers.RotationManager;
import dev.coyduck.modules.NoClimb;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {
    @ModifyExpressionValue(method = "jump", at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/LivingEntity;getYaw()F"))
    private float coyduck$jumpYaw(float original) {
        return RotationManager.get().physicsYaw((Entity) (Object) this, original);
    }

    @Inject(method = "isClimbing", at = @At("HEAD"), cancellable = true)
    private void coyduck$noClimb(CallbackInfoReturnable<Boolean> info) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (NoClimb.ignoresClimb(self, self.getBlockStateAtPos())) info.setReturnValue(false);
    }
}
