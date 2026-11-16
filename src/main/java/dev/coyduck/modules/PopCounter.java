package dev.coyduck.modules;

import dev.coyduck.core.Core;
import dev.coyduck.events.DeathEvent;
import dev.coyduck.events.TotemPopEvent;
import dev.coyduck.feature.BoolSetting;
import dev.coyduck.feature.Feature;
import dev.coyduck.feature.Setting;
import dev.coyduck.feature.SettingGroup;
import dev.coyduck.managers.PopTracker;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

public class PopCounter extends Feature {
    private static final int NAME = 0xB94AE6;
    private static final int COUNT = 0xFBC02D;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> friends = sgGeneral.add(new BoolSetting.Builder()
        .name("friends")
        .description("Also counts your friends' pops.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> deaths = sgGeneral.add(new BoolSetting.Builder()
        .name("deaths")
        .description("Says how many totems a player popped once they die.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> replace = sgGeneral.add(new BoolSetting.Builder()
        .name("replace")
        .description("Updates one chat line per player.")
        .defaultValue(true)
        .build()
    );

    public PopCounter() {
        super("pop-counter", "Counts enemy totem pops.");
    }

    public static int pops(PlayerEntity player) {
        return PopTracker.get().pops(player);
    }

    @EventHandler
    private void onPop(TotemPopEvent event) {
        if (counted(event.player)) send(event.player, Text.literal(" popped ").formatted(Formatting.GRAY), event.pops);
    }

    @EventHandler
    private void onDeath(DeathEvent event) {
        if (deaths.get() && counted(event.player)) send(event.player, Text.literal(" died after popping ").formatted(Formatting.GRAY), event.pops);
    }

    private boolean counted(PlayerEntity player) {
        return mc.player != null && player != mc.player && (friends.get() || !Core.host().isFriend(player));
    }

    private void send(PlayerEntity player, MutableText verb, int count) {
        MutableText message = Text.literal(player.getName().getString()).withColor(NAME)
            .append(verb)
            .append(Text.literal(String.valueOf(count)).withColor(COUNT))
            .append(Text.literal(count == 1 ? " totem" : " totems").formatted(Formatting.GRAY));
        Core.host().notify(replace.get() ? id(player) : 0, message);
    }

    private static int id(PlayerEntity player) {
        int hash = player.getUuid().hashCode();
        return hash == 0 ? 1 : hash;
    }
}
