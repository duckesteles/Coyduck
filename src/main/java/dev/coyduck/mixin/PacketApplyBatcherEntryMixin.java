package dev.coyduck.mixin;

import dev.coyduck.events.PacketApplyEvent;
import meteordevelopment.meteorclient.MeteorClient;
import net.minecraft.network.packet.Packet;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "net.minecraft.network.PacketApplyBatcher$Entry")
public abstract class PacketApplyBatcherEntryMixin {
    @Shadow
    @Final
    private Packet<?> packet;

    @Inject(method = "apply", at = @At("HEAD"), cancellable = true)
    private void coyduck$onApplyHead(CallbackInfo ci) {
        if (MeteorClient.EVENT_BUS.post(PacketApplyEvent.Pre.get(packet)).isCancelled()) ci.cancel();
    }

    @Inject(method = "apply", at = @At("TAIL"))
    private void coyduck$onApplyTail(CallbackInfo ci) {
        MeteorClient.EVENT_BUS.post(PacketApplyEvent.Post.get(packet));
    }
}
