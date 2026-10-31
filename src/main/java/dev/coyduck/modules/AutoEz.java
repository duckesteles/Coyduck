package dev.coyduck.modules;

import dev.coyduck.Coyduck;
import dev.coyduck.events.PacketApplyEvent;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.mixininterface.IPlayerInteractEntityC2SPacket;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringListSetting;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Module;
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public class AutoEz extends Module {
    private static final String SMALL_CAPS = "ᴀʙᴄᴅᴇꜰɢʜɪᴊᴋʟᴍɴᴏᴘqʀꜱᴛᴜᴠᴡxʏᴢ";
    private static final long ENGAGED_MS = 15_000;
    private static final double EXPLOSION_REACH = 12;
    private static final int CHAT_LIMIT = 256;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<List<String>> messages = sgGeneral.add(new StringListSetting.Builder()
        .name("messages")
        .description("Picked at random for every kill. {name} becomes the player's name.")
        .defaultValue(List.of(
            "gg",
            "gg wp",
            "gf {name}",
            "close one"
        ))
        .build()
    );

    private final Setting<String> suffix = sgGeneral.add(new StringSetting.Builder()
        .name("suffix")
        .description("Added after every message. Leave empty for none.")
        .defaultValue("| try coyduck addon » github.com/duckesteles/coyduck")
        .build()
    );

    private final Setting<Boolean> smallCaps = sgGeneral.add(new BoolSetting.Builder()
        .name("small-caps")
        .description("Writes the suffix in small caps.")
        .defaultValue(true)
        .build()
    );

    private final Setting<String> command = sgGeneral.add(new StringSetting.Builder()
        .name("command")
        .description("Whisper command, without the slash.")
        .defaultValue("msg")
        .build()
    );

    private final Setting<Integer> delay = sgGeneral.add(new IntSetting.Builder()
        .name("delay")
        .description("Ticks to wait after the kill before whispering.")
        .defaultValue(20)
        .range(0, 200)
        .sliderRange(0, 100)
        .build()
    );

    private final Map<UUID, Long> engaged = new HashMap<>();
    private final List<Pending> pending = new ArrayList<>();
    private int last = -1;

    private record Pending(String name, long at) {}

    public AutoEz() {
        super(Coyduck.CATEGORY, "auto-ez", "Whispers a good game message to players you kill.");
    }

    @Override
    public void onDeactivate() {
        engaged.clear();
        pending.clear();
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
        if (!(event.packet instanceof EntityStatusS2CPacket packet) || mc.world == null || mc.player == null) return;
        if (packet.getStatus() != EntityStatuses.PLAY_DEATH_SOUND_OR_ADD_PROJECTILE_HIT_PARTICLES) return;
        if (!(packet.getEntity(mc.world) instanceof PlayerEntity player) || player == mc.player || Friends.get().isFriend(player)) return;

        Long at = engaged.remove(player.getUuid());
        if (at == null || Util.getMeasuringTimeMs() - at > ENGAGED_MS) return;
        pending.add(new Pending(player.getName().getString(), Util.getMeasuringTimeMs() + delay.get() * 50L));
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (pending.isEmpty() || mc.getNetworkHandler() == null) return;

        long now = Util.getMeasuringTimeMs();
        pending.removeIf(entry -> {
            if (entry.at > now) return false;
            whisper(entry.name);
            return true;
        });
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        engaged.clear();
        pending.clear();
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

    private void engage(PlayerEntity player) {
        if (player != mc.player && !Friends.get().isFriend(player)) engaged.put(player.getUuid(), Util.getMeasuringTimeMs());
    }

    private void engageNear(Vec3d center) {
        for (PlayerEntity player : mc.world.getPlayers()) {
            if (player.squaredDistanceTo(center) <= EXPLOSION_REACH * EXPLOSION_REACH) engage(player);
        }
    }

    private void whisper(String name) {
        List<String> list = messages.get();
        if (list.isEmpty()) return;

        int index = ThreadLocalRandom.current().nextInt(list.size());
        if (list.size() > 1 && index == last) index = (index + 1) % list.size();
        last = index;

        String tail = suffix.get().isBlank() ? "" : " " + (smallCaps.get() ? smallCaps(suffix.get()) : suffix.get());
        String body = list.get(index).replace("{name}", name) + tail;
        String prefix = command.get().trim() + " " + name + " ";
        int room = CHAT_LIMIT - prefix.length();
        if (body.length() > room) body = body.substring(0, room);
        mc.getNetworkHandler().sendChatCommand(prefix + body);
    }

    private static String smallCaps(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (char c : text.toCharArray()) {
            char lower = Character.toLowerCase(c);
            out.append(lower >= 'a' && lower <= 'z' ? SMALL_CAPS.charAt(lower - 'a') : c);
        }
        return out.toString();
    }
}
