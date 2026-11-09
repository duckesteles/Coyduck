package dev.coyduck.logger.mixin;

import dev.coyduck.logger.Recorder;
import io.netty.channel.ChannelFutureListener;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.NetworkSide;
import net.minecraft.network.packet.Packet;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientConnection.class)
public abstract class ClientConnectionMixin {
    @Shadow
    @Final
    private NetworkSide side;

    @Inject(method = "send(Lnet/minecraft/network/packet/Packet;Lio/netty/channel/ChannelFutureListener;Z)V", at = @At("HEAD"))
    private void coyduckLogger$sent(Packet<?> packet, ChannelFutureListener listener, boolean flush, CallbackInfo ci) {
        if (side == NetworkSide.CLIENTBOUND) Recorder.get().sent(packet);
    }

    @Inject(method = "disconnect(Lnet/minecraft/network/DisconnectionInfo;)V", at = @At("HEAD"))
    private void coyduckLogger$disconnect(CallbackInfo ci) {
        if (side == NetworkSide.CLIENTBOUND) Recorder.get().close("disconnected");
    }
}
