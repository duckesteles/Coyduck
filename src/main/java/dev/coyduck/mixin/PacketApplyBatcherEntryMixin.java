package dev.coyduck.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.coyduck.core.Bus;
import dev.coyduck.events.PacketApplyEvent;
import net.minecraft.network.NetworkSide;
import net.minecraft.network.listener.PacketListener;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.BundleS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(targets = "net.minecraft.network.PacketApplyBatcher$Entry")
public abstract class PacketApplyBatcherEntryMixin {
    @WrapOperation(method = "apply", at = @At(value = "INVOKE", target = "Lnet/minecraft/network/packet/Packet;apply(Lnet/minecraft/network/listener/PacketListener;)V"))
    private void coyduck$apply(Packet<?> packet, PacketListener listener, Operation<Void> original) {
        if (listener.getSide() != NetworkSide.CLIENTBOUND || packet instanceof BundleS2CPacket) {
            original.call(packet, listener);
            return;
        }
        if (Bus.post(PacketApplyEvent.Pre.get(packet)).isCancelled()) return;
        original.call(packet, listener);
        Bus.post(PacketApplyEvent.Post.get(packet));
    }
}
