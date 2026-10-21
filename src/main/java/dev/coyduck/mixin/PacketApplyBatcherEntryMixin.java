package dev.coyduck.mixin;

import dev.coyduck.Coyduck;
import dev.coyduck.events.PacketApplyEvent;
import meteordevelopment.meteorclient.MeteorClient;
import net.minecraft.network.NetworkSide;
import net.minecraft.network.listener.PacketListener;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.BundleS2CPacket;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "net.minecraft.network.PacketApplyBatcher$Entry")
public abstract class PacketApplyBatcherEntryMixin {
    @Shadow
    @Final
    private PacketListener listener;

    @Shadow
    @Final
    private Packet<?> packet;

    @Inject(method = "apply", at = @At("HEAD"), cancellable = true)
    private void coyduck$onApplyHead(CallbackInfo ci) {
        if (!relevant()) return;
        try {
            if (MeteorClient.EVENT_BUS.post(PacketApplyEvent.Pre.get(packet)).isCancelled()) ci.cancel();
        } catch (RuntimeException e) {
            Coyduck.LOG.error("Packet handler failed", e);
        }
    }

    @Inject(method = "apply", at = @At("TAIL"))
    private void coyduck$onApplyTail(CallbackInfo ci) {
        if (!relevant()) return;
        try {
            MeteorClient.EVENT_BUS.post(PacketApplyEvent.Post.get(packet));
        } catch (RuntimeException e) {
            Coyduck.LOG.error("Packet handler failed", e);
        }
    }

    @Unique
    private boolean relevant() {
        return listener.getSide() == NetworkSide.CLIENTBOUND && !(packet instanceof BundleS2CPacket) && listener.accepts(packet);
    }
}
