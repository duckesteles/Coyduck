package dev.coyduck.mixin;

import dev.coyduck.modules.Shaders;
import dev.coyduck.utils.ShaderGlow;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.state.WorldRenderState;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(WorldRenderer.class)
public abstract class WorldRendererMixin {
    @Inject(method = "render", at = @At("HEAD"))
    private void coyduck$clearGlow(CallbackInfo info) {
        if (Shaders.active() != null) ShaderGlow.get().clearTexture();
    }

    @Inject(method = "pushEntityRenders", at = @At("TAIL"))
    private void coyduck$drawGlow(MatrixStack matrices, WorldRenderState world, OrderedRenderCommandQueue queue, CallbackInfo info) {
        Shaders module = Shaders.active();
        if (module != null) ShaderGlow.get().drawEntities(world, matrices, module);
    }

    @Inject(method = "method_62214", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/OutlineVertexConsumerProvider;draw()V"))
    private void coyduck$submitGlow(CallbackInfo info) {
        if (Shaders.active() != null) ShaderGlow.get().submitVertices();
    }

    @Inject(method = "onResized", at = @At("HEAD"))
    private void coyduck$resizeGlow(int width, int height, CallbackInfo info) {
        ShaderGlow.resize(width, height);
    }
}
