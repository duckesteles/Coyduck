package dev.coyduck.mixin;

import dev.coyduck.MeteorGlow;
import dev.coyduck.modules.Shaders;
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
        if (Shaders.active() != null) MeteorGlow.get().clearTexture();
        if (MeteorGlow.esp().glowing()) MeteorGlow.esp().clearTexture();
    }

    @Inject(method = "pushEntityRenders", at = @At("TAIL"))
    private void coyduck$drawGlow(MatrixStack matrices, WorldRenderState world, OrderedRenderCommandQueue queue, CallbackInfo info) {
        if (Shaders.active() != null) MeteorGlow.get().drawEntities(world, matrices);
        if (MeteorGlow.esp().glowing()) MeteorGlow.esp().drawEntities(world, matrices);
    }

    @Inject(method = "method_62214", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/OutlineVertexConsumerProvider;draw()V"))
    private void coyduck$submitGlow(CallbackInfo info) {
        if (Shaders.active() != null) MeteorGlow.get().submitVertices();
        if (MeteorGlow.esp().glowing()) MeteorGlow.esp().submitVertices();
    }

    @Inject(method = "onResized", at = @At("HEAD"))
    private void coyduck$resizeGlow(int width, int height, CallbackInfo info) {
        MeteorGlow.resize(width, height);
    }
}
