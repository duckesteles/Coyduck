package dev.coyduck.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.coyduck.modules.BoatFly;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ClientPlayerInteractionManager.class)
public abstract class ClientPlayerInteractionManagerMixin {
    @ModifyExpressionValue(method = {"attackEntity", "interactEntity", "interactEntityAtLocation"}, at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/player/PlayerEntity;isSneaking()Z"))
    private boolean coyduck$keepSeat(boolean sneaking) {
        return sneaking && !BoatFly.keepSeat();
    }
}
