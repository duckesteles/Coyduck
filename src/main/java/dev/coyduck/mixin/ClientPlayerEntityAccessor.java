package dev.coyduck.mixin;

import net.minecraft.client.network.ClientPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ClientPlayerEntity.class)
public interface ClientPlayerEntityAccessor {
    @Accessor("lastYawClient")
    void coyduck$setLastYawClient(float yaw);

    @Accessor("lastPitchClient")
    void coyduck$setLastPitchClient(float pitch);
}
