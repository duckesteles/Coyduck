package dev.coyduck.modules;

import dev.coyduck.core.Core;
import dev.coyduck.events.PacketApplyEvent;
import dev.coyduck.feature.BoolSetting;
import dev.coyduck.feature.DoubleSetting;
import dev.coyduck.feature.Feature;
import dev.coyduck.feature.IntSetting;
import dev.coyduck.feature.Setting;
import dev.coyduck.feature.SettingGroup;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.entity.EntityStatuses;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LightningEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.packet.s2c.play.EntityStatusS2CPacket;
import net.minecraft.sound.SoundEvents;

public class KillEffects extends Feature {
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
        .description("Only strikes enemies that popped at least one totem first.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> bolts = sgGeneral.add(new IntSetting.Builder()
        .name("bolts")
        .description("How many lightning bolts strike.")
        .defaultValue(1)
        .range(0, 5)
        .sliderRange(0, 5)
        .build()
    );

    private final Setting<Boolean> sound = sgGeneral.add(new BoolSetting.Builder()
        .name("sound")
        .description("Plays a short sound for the kill. Only you hear it.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> streak = sgGeneral.add(new BoolSetting.Builder()
        .name("streak")
        .description("Shows kills since your last death.")
        .defaultValue(true)
        .build()
    );

    private int nextId = -4_000_000;
    private int kills;

    @Override
    public String getInfoString() {
        return streak.get() && kills > 0 ? kills + (kills == 1 ? " kill" : " kills") : null;
    }

    public KillEffects() {
        super("kill-effects", "Lightning and a sound on kills.");
    }

    @EventHandler(priority = EventPriority.HIGH)
    private void onPacket(PacketApplyEvent.Pre event) {
        if (!(event.packet instanceof EntityStatusS2CPacket packet) || mc.world == null || mc.player == null) return;
        if (packet.getStatus() != EntityStatuses.PLAY_DEATH_SOUND_OR_ADD_PROJECTILE_HIT_PARTICLES) return;
        if (packet.getEntity(mc.world) == mc.player) {
            kills = 0;
            return;
        }
        if (!(packet.getEntity(mc.world) instanceof PlayerEntity player) || Core.host().isFriend(player)) return;
        if (player.squaredDistanceTo(mc.player) > range.get() * range.get()) return;
        if (onlyPopped.get() && PopCounter.pops(player) == 0) return;

        kills++;
        for (int i = 0; i < bolts.get(); i++) {
            LightningEntity bolt = new LightningEntity(EntityType.LIGHTNING_BOLT, mc.world);
            bolt.setCosmetic(true);
            bolt.setId(nextId--);
            bolt.refreshPositionAfterTeleport(player.getX(), player.getY(), player.getZ());
            mc.world.addEntity(bolt);
        }
        if (sound.get()) mc.getSoundManager().play(PositionedSoundInstance.ui(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 0.6f));
    }
}
