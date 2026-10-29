package dev.coyduck.mixin;

import dev.coyduck.modules.CrystalChams;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.state.EntityRenderState;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(EntityRenderer.class)
public abstract class EntityRendererMixin {
    // The game's glow outline, the one the glowing effect uses, in the chams colour.
    @Inject(method = "updateRenderState(Lnet/minecraft/entity/Entity;Lnet/minecraft/client/render/entity/state/EntityRenderState;F)V", at = @At("TAIL"))
    private void coyduck$glow(Entity entity, EntityRenderState state, float tickProgress, CallbackInfo ci) {
        int glow = CrystalChams.glow(entity);
        if (glow != 0) state.outlineColor = glow;
    }
}
