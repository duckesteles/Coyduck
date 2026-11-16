package dev.coyduck.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.coyduck.core.Bus;
import dev.coyduck.core.Core;
import dev.coyduck.events.EntityMoveEvent;
import dev.coyduck.events.Movement;
import dev.coyduck.events.PlayerMoveEvent;
import dev.coyduck.managers.RotationManager;
import dev.coyduck.modules.Esp;
import dev.coyduck.modules.NoClimb;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.MovementType;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Entity.class)
public abstract class EntityMixin {
    @WrapMethod(method = "move")
    private void coyduck$move(MovementType type, Vec3d movement, Operation<Void> original) {
        Entity self = (Entity) (Object) this;
        if (!self.getEntityWorld().isClient()) {
            original.call(type, movement);
            return;
        }
        boolean velocity = movement == self.getVelocity();
        Movement event = self == Core.mc.player ? Bus.post(PlayerMoveEvent.get(type, movement)) : Bus.post(EntityMoveEvent.get(self, movement));
        Vec3d result = event.result();
        if (velocity && result != movement && self.getVelocity() == movement) self.setVelocity(result);
        original.call(type, result);
    }

    @ModifyExpressionValue(method = "updateVelocity", at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/Entity;getYaw()F"))
    private float coyduck$velocityYaw(float original) {
        return RotationManager.get().physicsYaw((Entity) (Object) this, original);
    }

    @Inject(method = "slowMovement", at = @At("HEAD"), cancellable = true)
    private void coyduck$noWebSlow(BlockState state, Vec3d multiplier, CallbackInfo info) {
        if (NoClimb.ignoresSlow((Entity) (Object) this, state)) info.cancel();
    }

    @Inject(method = "shouldRender(D)Z", at = @At("HEAD"), cancellable = true)
    private void coyduck$espDistance(double distance, CallbackInfoReturnable<Boolean> info) {
        if (Esp.shows((Entity) (Object) this)) info.setReturnValue(true);
    }
}
