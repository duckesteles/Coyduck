package dev.coyduck.modules;

import dev.coyduck.Coyduck;
import dev.coyduck.events.PacketApplyEvent;
import dev.coyduck.events.WorldResetEvent;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.EntityStatuses;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.packet.s2c.play.EntityStatusS2CPacket;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.UUID;

public class PopCounter extends Module {
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
        .description("Updates one chat line per player instead of writing a new line for every pop.")
        .defaultValue(true)
        .build()
    );

    private final Object2IntOpenHashMap<UUID> pops = new Object2IntOpenHashMap<>();

    public PopCounter() {
        super(Coyduck.CATEGORY, "pop-counter", "Counts every totem your enemies pop in chat, and how many they popped when they die.");
    }

    public static int pops(PlayerEntity player) {
        PopCounter module = Modules.get().get(PopCounter.class);
        return module == null ? 0 : module.pops.getInt(player.getUuid());
    }

    @Override
    public void onDeactivate() {
        pops.clear();
    }

    @EventHandler
    private void onPacket(PacketApplyEvent.Pre event) {
        if (!(event.packet instanceof EntityStatusS2CPacket packet) || mc.world == null || mc.player == null) return;
        if (!(packet.getEntity(mc.world) instanceof PlayerEntity player) || player == mc.player) return;
        if (!friends.get() && Friends.get().isFriend(player)) return;

        String name = player.getName().getString();
        if (packet.getStatus() == EntityStatuses.USE_TOTEM_OF_UNDYING) {
            int count = pops.addTo(player.getUuid(), 1) + 1;
            send(player, name, Text.literal(" popped ").formatted(Formatting.GRAY), count);
        } else if (packet.getStatus() == EntityStatuses.PLAY_DEATH_SOUND_OR_ADD_PROJECTILE_HIT_PARTICLES) {
            int count = pops.removeInt(player.getUuid());
            if (deaths.get()) send(player, name, Text.literal(" died after popping ").formatted(Formatting.GRAY), count);
        }
    }

    private void send(PlayerEntity player, String name, MutableText verb, int count) {
        MutableText message = Text.literal(name).withColor(NAME)
            .append(verb)
            .append(Text.literal(String.valueOf(count)).withColor(COUNT))
            .append(Text.literal(count == 1 ? " totem" : " totems").formatted(Formatting.GRAY));
        ChatUtils.sendMsg(replace.get() ? id(player) : 0, "Coyduck", Formatting.LIGHT_PURPLE, message);
    }

    private static int id(PlayerEntity player) {
        int hash = player.getUuid().hashCode();
        return hash == 0 ? 1 : hash;
    }

    @EventHandler
    private void onWorldReset(WorldResetEvent event) {
        pops.clear();
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        pops.clear();
    }
}
