package dev.coyduck.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.coyduck.managers.RotationManager;
import dev.coyduck.modules.Phase;
import net.minecraft.client.network.ClientPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPlayerEntity.class)
public abstract class ClientPlayerEntityMixin {
    @ModifyExpressionValue(method = {"sendMovementPackets", "tick"}, at = @At(value = "INVOKE", target = "Lnet/minecraft/client/network/ClientPlayerEntity;getYaw()F"))
    private float coyduck$packetYaw(float original) {
        return RotationManager.get().packetYaw(original);
    }

    @ModifyExpressionValue(method = {"sendMovementPackets", "tick"}, at = @At(value = "INVOKE", target = "Lnet/minecraft/client/network/ClientPlayerEntity;getPitch()F"))
    private float coyduck$packetPitch(float original) {
        return RotationManager.get().packetPitch(original);
    }

    @Inject(method = "pushOutOfBlocks", at = @At("HEAD"), cancellable = true)
    private void coyduck$keepPhase(double x, double z, CallbackInfo ci) {
        if (Phase.holding()) ci.cancel();
    }
}
