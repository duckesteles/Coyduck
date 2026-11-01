package dev.coyduck.mixin;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.coyduck.Coyduck;
import dev.coyduck.events.PacketApplyEvent;
import dev.coyduck.events.WorldResetEvent;
import dev.coyduck.modules.NoExplosion;
import meteordevelopment.meteorclient.MeteorClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.network.listener.PacketListener;
import net.minecraft.network.packet.Packet;
import net.minecraft.particle.BlockParticleEffect;
import net.minecraft.util.collection.WeightedPool;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPlayNetworkHandler.class)
public abstract class ClientPlayNetworkHandlerMixin {
    @Inject(method = {"onGameJoin", "onPlayerRespawn"}, at = @At("TAIL"))
    private void coyduck$onWorldReset(CallbackInfo ci) {
        MeteorClient.EVENT_BUS.post(WorldResetEvent.INSTANCE);
    }

    @WrapOperation(method = "onBundle", at = @At(value = "INVOKE", target = "Lnet/minecraft/network/packet/Packet;apply(Lnet/minecraft/network/listener/PacketListener;)V"))
    private void coyduck$wrapBundled(Packet<?> packet, PacketListener listener, Operation<Void> original) {
        boolean cancelled = false;
        try {
            cancelled = MeteorClient.EVENT_BUS.post(PacketApplyEvent.Pre.get(packet)).isCancelled();
        } catch (RuntimeException e) {
            Coyduck.LOG.error("Packet handler failed", e);
        }
        if (cancelled) return;

        original.call(packet, listener);
        try {
            MeteorClient.EVENT_BUS.post(PacketApplyEvent.Post.get(packet));
        } catch (RuntimeException e) {
            Coyduck.LOG.error("Packet handler failed", e);
        }
    }

    @WrapWithCondition(method = "onExplosion", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/world/ClientWorld;addBlockParticleEffects(Lnet/minecraft/util/math/Vec3d;FILnet/minecraft/util/collection/WeightedPool;)V"))
    private boolean coyduck$hideSmoke(ClientWorld world, Vec3d center, float radius, int blockCount, WeightedPool<BlockParticleEffect> particles) {
        return !NoExplosion.hidesSmoke();
    }
}
