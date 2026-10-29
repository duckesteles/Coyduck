package dev.coyduck.mixin;

import dev.coyduck.modules.CrystalChams;
import dev.coyduck.modules.Esp;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Sodium skips entities in chunk sections it cannot see; anything shown through walls has to stay.
@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer", remap = false)
public abstract class SodiumWorldRendererMixin {
    @Inject(method = "isEntityVisible", at = @At("HEAD"), cancellable = true, require = 0)
    private void coyduck$throughWalls(EntityRenderer<?, ?> renderer, Entity entity, CallbackInfoReturnable<Boolean> cir) {
        if (Esp.shows(entity) || CrystalChams.throughWalls(entity)) cir.setReturnValue(true);
    }
}
