package dev.coyduck.mixin;

import dev.coyduck.modules.Shaders;
import dev.coyduck.render.Argb;
import net.minecraft.client.render.fog.FogRenderer;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(FogRenderer.class)
public abstract class FogRendererMixin {
    @Inject(method = "getFogColor", at = @At("RETURN"), cancellable = true)
    private void coyduck$fogColor(CallbackInfoReturnable<Vector4f> info) {
        Shaders module = Shaders.atmosphere();
        if (module == null) return;
        int color = module.maskColor();
        info.setReturnValue(new Vector4f(Argb.r(color) / 255.0f, Argb.g(color) / 255.0f, Argb.b(color) / 255.0f, Argb.a(color) / 255.0f));
    }

    @ModifyVariable(method = "applyFog(Ljava/nio/ByteBuffer;ILorg/joml/Vector4f;FFFFFF)V", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private float coyduck$fogStart(float original) {
        Shaders module = Shaders.atmosphere();
        return module != null ? Math.min(original, module.fogStart()) : original;
    }

    @ModifyVariable(method = "applyFog(Ljava/nio/ByteBuffer;ILorg/joml/Vector4f;FFFFFF)V", at = @At("HEAD"), argsOnly = true, ordinal = 1)
    private float coyduck$fogEnd(float original) {
        Shaders module = Shaders.atmosphere();
        return module != null ? Math.min(original, module.fogEnd()) : original;
    }

    @ModifyVariable(method = "applyFog(Ljava/nio/ByteBuffer;ILorg/joml/Vector4f;FFFFFF)V", at = @At("HEAD"), argsOnly = true, ordinal = 5)
    private float coyduck$cloudEnd(float original) {
        Shaders module = Shaders.atmosphere();
        return module != null ? Math.min(original, module.fogEnd()) : original;
    }
}
