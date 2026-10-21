package dev.coyduck.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.coyduck.managers.RotationManager;
import net.minecraft.client.network.ClientPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

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
}
