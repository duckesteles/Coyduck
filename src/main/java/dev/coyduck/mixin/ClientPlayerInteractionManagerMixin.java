package dev.coyduck.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.coyduck.core.Bus;
import dev.coyduck.events.AttackEntityEvent;
import dev.coyduck.modules.BoatFly;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPlayerInteractionManager.class)
public abstract class ClientPlayerInteractionManagerMixin {
    @Inject(method = "attackEntity", at = @At("HEAD"), cancellable = true)
    private void coyduck$attack(PlayerEntity player, Entity target, CallbackInfo ci) {
        if (Bus.post(AttackEntityEvent.get(target)).isCancelled()) ci.cancel();
    }

    @ModifyExpressionValue(method = {"attackEntity", "interactEntity", "interactEntityAtLocation"}, at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/player/PlayerEntity;isSneaking()Z"))
    private boolean coyduck$keepSeat(boolean sneaking) {
        return sneaking && !BoatFly.keepSeat();
    }
}
