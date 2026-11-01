package dev.coyduck.mixin;

import dev.coyduck.modules.Shaders;
import net.minecraft.client.world.ClientWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ClientWorld.Properties.class)
public abstract class ClientWorldPropertiesMixin {
    @Inject(method = "getTimeOfDay", at = @At("HEAD"), cancellable = true)
    private void coyduck$timeOfDay(CallbackInfoReturnable<Long> info) {
        Shaders module = Shaders.atmosphere();
        if (module != null && module.changesTime()) info.setReturnValue(module.timeOfDay());
    }
}
