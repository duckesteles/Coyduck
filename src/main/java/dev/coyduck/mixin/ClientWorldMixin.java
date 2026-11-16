package dev.coyduck.mixin;

import dev.coyduck.core.Bus;
import dev.coyduck.events.EntityAddedEvent;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientWorld.class)
public abstract class ClientWorldMixin {
    @Inject(method = "addEntity", at = @At("TAIL"))
    private void coyduck$entityAdded(Entity entity, CallbackInfo ci) {
        if (entity != null) Bus.post(EntityAddedEvent.get(entity));
    }
}
