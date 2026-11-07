package dev.coyduck.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.coyduck.managers.RotationManager;
import dev.coyduck.modules.NoClimb;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Entity.class)
public abstract class EntityMixin {
    @ModifyExpressionValue(method = "updateVelocity", at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/Entity;getYaw()F"))
    private float coyduck$velocityYaw(float original) {
        return RotationManager.get().physicsYaw((Entity) (Object) this, original);
    }

    @Inject(method = "slowMovement", at = @At("HEAD"), cancellable = true)
    private void coyduck$noWebSlow(BlockState state, Vec3d multiplier, CallbackInfo info) {
        if (NoClimb.ignoresSlow((Entity) (Object) this, state)) info.cancel();
    }
}
