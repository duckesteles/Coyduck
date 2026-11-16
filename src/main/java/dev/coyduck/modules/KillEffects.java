package dev.coyduck.modules;

import dev.coyduck.core.Core;
import dev.coyduck.events.DeathEvent;
import dev.coyduck.feature.BoolSetting;
import dev.coyduck.feature.DoubleSetting;
import dev.coyduck.feature.Feature;
import dev.coyduck.feature.IntSetting;
import dev.coyduck.feature.Setting;
import dev.coyduck.feature.SettingGroup;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LightningEntity;
import net.minecraft.entity.player.PlayerEntity;
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
    private void onDeath(DeathEvent event) {
        if (mc.player == null || mc.world == null) return;
        if (event.player == mc.player) {
            kills = 0;
            return;
        }
        PlayerEntity player = event.player;
        if (Core.host().isFriend(player) || player.squaredDistanceTo(mc.player) > range.get() * range.get()) return;
        if (onlyPopped.get() && event.pops == 0) return;

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
