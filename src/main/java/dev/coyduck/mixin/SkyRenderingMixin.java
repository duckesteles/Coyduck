package dev.coyduck.mixin;

import dev.coyduck.modules.Shaders;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.SkyRendering;
import net.minecraft.client.render.state.SkyRenderState;
import net.minecraft.client.world.ClientWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SkyRendering.class)
public abstract class SkyRenderingMixin {
    @Inject(method = "updateRenderState", at = @At("TAIL"))
    private void coyduck$skyColor(ClientWorld world, float tickProgress, Camera camera, SkyRenderState state, CallbackInfo info) {
        Shaders module = Shaders.atmosphere();
        if (module != null) state.skyColor = module.maskColor().getPacked();
    }
}
