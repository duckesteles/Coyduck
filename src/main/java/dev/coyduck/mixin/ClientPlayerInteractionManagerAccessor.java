package dev.coyduck.mixin;

import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.client.network.SequencedPacketCreator;
import net.minecraft.client.world.ClientWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ClientPlayerInteractionManager.class)
public interface ClientPlayerInteractionManagerAccessor {
    @Accessor("lastSelectedSlot")
    int coyduck$getLastSelectedSlot();

    @Invoker("syncSelectedSlot")
    void coyduck$syncSelectedSlot();

    @Invoker("sendSequencedPacket")
    void coyduck$sendSequencedPacket(ClientWorld world, SequencedPacketCreator creator);
}
