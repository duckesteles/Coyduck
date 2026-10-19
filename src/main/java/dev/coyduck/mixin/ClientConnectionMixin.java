package dev.coyduck.mixin;

import dev.coyduck.utils.PacketBatch;
import net.minecraft.network.ClientConnection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(ClientConnection.class)
public abstract class ClientConnectionMixin {
    @ModifyArg(method = "send(Lnet/minecraft/network/packet/Packet;Lio/netty/channel/ChannelFutureListener;)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/network/ClientConnection;send(Lnet/minecraft/network/packet/Packet;Lio/netty/channel/ChannelFutureListener;Z)V"), index = 2)
    private boolean coyduck$batch(boolean flush) {
        return flush && !PacketBatch.active();
    }
}
