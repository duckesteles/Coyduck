package dev.coyduck.mixin;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalFloatRef;
import dev.coyduck.Coyduck;
import dev.coyduck.events.PacketApplyEvent;
import dev.coyduck.events.WorldResetEvent;
import dev.coyduck.modules.NoExplosion;
import dev.coyduck.modules.NoRotate;
import meteordevelopment.meteorclient.MeteorClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.listener.PacketListener;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.PlayerPositionLookS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerRotationS2CPacket;
import net.minecraft.particle.BlockParticleEffect;
import net.minecraft.util.collection.WeightedPool;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;
import java.util.UUID;

@Mixin(ClientPlayNetworkHandler.class)
public abstract class ClientPlayNetworkHandlerMixin {
    @Shadow
    @Final
    private Map<UUID, PlayerListEntry> playerListEntries;

    @Inject(method = "getCaseInsensitivePlayerInfo", at = @At("HEAD"), cancellable = true)
    private void coyduck$safePlayerLookup(String name, CallbackInfoReturnable<PlayerListEntry> info) {
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                for (PlayerListEntry entry : playerListEntries.values()) {
                    if (entry != null && entry.getProfile().name().equalsIgnoreCase(name)) {
                        info.setReturnValue(entry);
                        return;
                    }
                }
                info.setReturnValue(null);
                return;
            } catch (RuntimeException ignored) {
            }
        }
        info.setReturnValue(null);
    }

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

    @Inject(method = "onPlayerPositionLook", at = @At("HEAD"))
    private void coyduck$keepLookHead(PlayerPositionLookS2CPacket packet, CallbackInfo info, @Share("yaw") LocalFloatRef yaw, @Share("pitch") LocalFloatRef pitch) {
        saveLook(yaw, pitch);
    }

    @Inject(method = "onPlayerPositionLook", at = @At("RETURN"))
    private void coyduck$keepLookReturn(PlayerPositionLookS2CPacket packet, CallbackInfo info, @Share("yaw") LocalFloatRef yaw, @Share("pitch") LocalFloatRef pitch) {
        restoreLook(yaw, pitch);
    }

    @Inject(method = "onPlayerRotation", at = @At("HEAD"))
    private void coyduck$keepRotationHead(PlayerRotationS2CPacket packet, CallbackInfo info, @Share("yaw") LocalFloatRef yaw, @Share("pitch") LocalFloatRef pitch) {
        saveLook(yaw, pitch);
    }

    @Inject(method = "onPlayerRotation", at = @At("RETURN"))
    private void coyduck$keepRotationReturn(PlayerRotationS2CPacket packet, CallbackInfo info, @Share("yaw") LocalFloatRef yaw, @Share("pitch") LocalFloatRef pitch) {
        restoreLook(yaw, pitch);
    }

    private static void saveLook(LocalFloatRef yaw, LocalFloatRef pitch) {
        PlayerEntity player = MinecraftClient.getInstance().player;
        if (!NoRotate.on() || player == null || !MinecraftClient.getInstance().isOnThread()) {
            yaw.set(Float.NaN);
            return;
        }
        yaw.set(player.getYaw());
        pitch.set(player.getPitch());
    }

    private static void restoreLook(LocalFloatRef yaw, LocalFloatRef pitch) {
        PlayerEntity player = MinecraftClient.getInstance().player;
        if (player == null || Float.isNaN(yaw.get())) return;
        player.setYaw(yaw.get() + 1.0E-6f);
        player.setPitch(pitch.get() + 1.0E-6f);
        player.lastYaw = yaw.get();
        player.lastPitch = pitch.get();
        player.headYaw = yaw.get();
        player.lastHeadYaw = yaw.get();
        player.bodyYaw = yaw.get();
        player.lastBodyYaw = yaw.get();
    }
}
