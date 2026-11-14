package dev.coyduck.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.coyduck.managers.RotationManager;
import dev.coyduck.modules.NoRotate;
import dev.coyduck.modules.BoatFly;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.PlayerInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
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

    @Inject(method = "sendMovementPackets", at = @At("HEAD"), cancellable = true)
    private void coyduck$holdInBlocks(CallbackInfo info) {
        if (NoRotate.frozen()) info.cancel();
    }

    @Inject(method = "sendMovementPackets", at = @At("TAIL"))
    private void coyduck$afterMovement(CallbackInfo info) {
        RotationManager.get().afterMovement();
    }

    @ModifyArg(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/network/packet/c2s/play/PlayerInputC2SPacket;<init>(Lnet/minecraft/util/PlayerInput;)V"))
    private PlayerInput coyduck$keepSeat(PlayerInput input) {
        if (!input.sneak() || !BoatFly.keepSeat()) return input;
        return new PlayerInput(input.forward(), input.backward(), input.left(), input.right(), input.jump(), false, input.sprint());
    }
}
