package dev.coyduck.logger.mixin;

import dev.coyduck.logger.Recorder;
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
public abstract class PacketApplyEntryMixin {
    @Shadow
    @Final
    private PacketListener listener;

    @Shadow
    @Final
    private Packet<?> packet;

    @Inject(method = "apply", at = @At("HEAD"))
    private void coyduckLogger$before(CallbackInfo ci) {
        if (relevant()) Recorder.get().received(packet, false);
    }

    @Inject(method = "apply", at = @At("TAIL"))
    private void coyduckLogger$after(CallbackInfo ci) {
        if (relevant()) Recorder.get().received(packet, true);
    }

    @Unique
    private boolean relevant() {
        return listener.getSide() == NetworkSide.CLIENTBOUND && !(packet instanceof BundleS2CPacket);
    }
}
