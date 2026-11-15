package dev.coyduck.managers;

import dev.coyduck.events.PacketApplyEvent;
import dev.coyduck.events.WorldResetEvent;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.mixininterface.IPlayerInteractEntityC2SPacket;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityStatuses;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractEntityC2SPacket;
import net.minecraft.network.packet.s2c.play.EntityStatusS2CPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.Util;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public final class FightTracker {
    private static final FightTracker INSTANCE = new FightTracker();

    private static final long ENGAGED_MS = 15_000;
    private static final double EXPLOSION_REACH = 12;

    private final Map<UUID, Long> engaged = new HashMap<>();
    private final Set<UUID> died = new HashSet<>();

    private FightTracker() {}

    public static FightTracker get() {
        return INSTANCE;
    }

    public boolean engaged(PlayerEntity player) {
        Long at = engaged.get(player.getUuid());
        return at != null && Util.getMeasuringTimeMs() - at <= ENGAGED_MS;
    }

    @EventHandler
    private void onSend(PacketEvent.Send event) {
        if (mc.world == null || mc.player == null) return;

        if (event.packet instanceof PlayerInteractEntityC2SPacket packet && isAttack(packet)) {
            Entity target = ((IPlayerInteractEntityC2SPacket) packet).meteor$getEntity();
            if (target instanceof PlayerEntity player) engage(player);
            else if (target instanceof EndCrystalEntity crystal) engageNear(crystal.getEntityPos());
        } else if (event.packet instanceof PlayerInteractBlockC2SPacket packet) {
            BlockPos pos = packet.getBlockHitResult().getBlockPos();
            if (mc.world.getBlockState(pos).isOf(Blocks.RESPAWN_ANCHOR)) engageNear(Vec3d.ofCenter(pos));
        }
    }

    @EventHandler
    private void onPacket(PacketApplyEvent.Pre event) {
        if (!(event.packet instanceof EntityStatusS2CPacket packet) || mc.world == null) return;
        if (packet.getStatus() != EntityStatuses.PLAY_DEATH_SOUND_OR_ADD_PROJECTILE_HIT_PARTICLES) return;
        if (packet.getEntity(mc.world) instanceof PlayerEntity player) died.add(player.getUuid());
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (!died.isEmpty()) {
            for (UUID uuid : died) engaged.remove(uuid);
            died.clear();
        }
        if (engaged.isEmpty()) return;
        long now = Util.getMeasuringTimeMs();
        engaged.values().removeIf(at -> now - at > ENGAGED_MS);
    }

    @EventHandler
    private void onWorldReset(WorldResetEvent event) {
        clear();
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        clear();
    }

    private void clear() {
        engaged.clear();
        died.clear();
    }

    private void engage(PlayerEntity player) {
        if (player != mc.player && !Friends.get().isFriend(player)) engaged.put(player.getUuid(), Util.getMeasuringTimeMs());
    }

    private void engageNear(Vec3d center) {
        for (PlayerEntity player : mc.world.getPlayers()) {
            if (player.squaredDistanceTo(center) <= EXPLOSION_REACH * EXPLOSION_REACH) engage(player);
        }
    }

    private static boolean isAttack(PlayerInteractEntityC2SPacket packet) {
        boolean[] attack = {false};
        packet.handle(new PlayerInteractEntityC2SPacket.Handler() {
            @Override
            public void interact(Hand hand) {
            }

            @Override
            public void interactAt(Hand hand, Vec3d pos) {
            }

            @Override
            public void attack() {
                attack[0] = true;
            }
        });
        return attack[0];
    }
}
