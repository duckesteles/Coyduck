package dev.coyduck.modules;

import dev.coyduck.Coyduck;
import dev.coyduck.events.PacketApplyEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.EntityStatuses;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LightningEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.packet.s2c.play.EntityStatusS2CPacket;

public class KillEffects extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Double> range = sgGeneral.add(new DoubleSetting.Builder()
        .name("range")
        .description("Range enemies must die in.")
        .defaultValue(64)
        .range(1, 256)
        .sliderRange(8, 128)
        .build()
    );

    private final Setting<Boolean> onlyPopped = sgGeneral.add(new BoolSetting.Builder()
        .name("only-popped")
        .description("Only strikes enemies that popped at least one totem first. Needs Pop Counter on.")
        .defaultValue(false)
        .build()
    );

    private int nextId = -4_000_000;

    public KillEffects() {
        super(Coyduck.CATEGORY, "kill-effects", "Strikes lightning where an enemy dies. Only you see it.");
    }

    @EventHandler
    private void onPacket(PacketApplyEvent.Pre event) {
        if (!(event.packet instanceof EntityStatusS2CPacket packet) || mc.world == null || mc.player == null) return;
        if (packet.getStatus() != EntityStatuses.PLAY_DEATH_SOUND_OR_ADD_PROJECTILE_HIT_PARTICLES) return;
        if (!(packet.getEntity(mc.world) instanceof PlayerEntity player) || player == mc.player || Friends.get().isFriend(player)) return;
        if (player.squaredDistanceTo(mc.player) > range.get() * range.get()) return;
        if (onlyPopped.get() && PopCounter.pops(player) == 0) return;

        LightningEntity bolt = new LightningEntity(EntityType.LIGHTNING_BOLT, mc.world);
        bolt.setCosmetic(true);
        bolt.setId(nextId--);
        bolt.refreshPositionAfterTeleport(player.getX(), player.getY(), player.getZ());
        mc.world.addEntity(bolt);
    }
}
