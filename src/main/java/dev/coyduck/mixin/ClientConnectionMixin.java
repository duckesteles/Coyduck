package dev.coyduck.mixin;

import dev.coyduck.core.Bus;
import dev.coyduck.events.PacketEvent;
import dev.coyduck.utils.PacketBatch;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.client.network.ClientCommonNetworkHandler;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.listener.PacketListener;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.BundleS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Iterator;

@Mixin(ClientConnection.class)
public abstract class ClientConnectionMixin {
    @Shadow
    public abstract PacketListener getPacketListener();

    @Inject(method = "channelRead0(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/packet/Packet;)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/network/ClientConnection;handlePacket(Lnet/minecraft/network/packet/Packet;Lnet/minecraft/network/listener/PacketListener;)V", shift = At.Shift.BEFORE), cancellable = true)
    private void coyduck$receive(ChannelHandlerContext context, Packet<?> packet, CallbackInfo ci) {
        if (!game()) return;
        if (packet instanceof BundleS2CPacket bundle) {
            for (Iterator<?> it = bundle.getPackets().iterator(); it.hasNext(); ) {
                if (Bus.post(new PacketEvent.Receive((Packet<?>) it.next())).isCancelled()) it.remove();
            }
        } else if (Bus.post(new PacketEvent.Receive(packet)).isCancelled()) {
            ci.cancel();
        }
    }

    @Inject(method = "send(Lnet/minecraft/network/packet/Packet;Lio/netty/channel/ChannelFutureListener;)V", at = @At("HEAD"), cancellable = true)
    private void coyduck$send(Packet<?> packet, ChannelFutureListener listener, CallbackInfo ci) {
        if (game() && Bus.post(new PacketEvent.Send(packet)).isCancelled()) ci.cancel();
    }

    @Inject(method = "send(Lnet/minecraft/network/packet/Packet;Lio/netty/channel/ChannelFutureListener;)V", at = @At("TAIL"))
    private void coyduck$sent(Packet<?> packet, ChannelFutureListener listener, CallbackInfo ci) {
        if (game()) Bus.post(new PacketEvent.Sent(packet));
    }

    @ModifyArg(method = "send(Lnet/minecraft/network/packet/Packet;Lio/netty/channel/ChannelFutureListener;)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/network/ClientConnection;send(Lnet/minecraft/network/packet/Packet;Lio/netty/channel/ChannelFutureListener;Z)V"), index = 2)
    private boolean coyduck$batch(boolean flush) {
        return flush && !PacketBatch.active();
    }

    private boolean game() {
        return getPacketListener() instanceof ClientCommonNetworkHandler;
    }
}
