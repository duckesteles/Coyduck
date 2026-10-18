package dev.coyduck.mixin;

import net.minecraft.client.network.ClientPlayerInteractionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ClientPlayerInteractionManager.class)
public interface ClientPlayerInteractionManagerAccessor {
    @Accessor("lastSelectedSlot")
    int coyduck$getLastSelectedSlot();

    @Invoker("syncSelectedSlot")
    void coyduck$syncSelectedSlot();
}
