package dev.coyduck.modules;

import dev.coyduck.Coyduck;
import dev.coyduck.events.PacketApplyEvent;
import dev.coyduck.managers.PopTracker;
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
    private static final long CHAT_GAP = 1_500;
    private static final int MAX_PENDING = 5;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<List<String>> messages = sgGeneral.add(new StringListSetting.Builder()
        .name("messages")
        .description("Picked at random for every kill. {name} becomes the player's name. In public chat the name is added after the message when it has no {name}.")
        .defaultValue(List.of(
            "gg",
            "gg wp",
            "gf {name}",
            "ggs"
        ))
        .build()
    );

    private final Setting<Boolean> whisper = sgGeneral.add(new BoolSetting.Builder()
        .name("whisper")
        .description("Whispers the message to the player you killed. Turning it on turns public-chat off, turning it off turns public-chat on.")
        .defaultValue(true)
        .onChanged(on -> {
            if (this.publicChat != null && on == this.publicChat.get()) this.publicChat.set(!on);
        })
        .build()
    );

    private final Setting<Boolean> publicChat = sgGeneral.add(new BoolSetting.Builder()
        .name("public-chat")
        .description("Writes the message with the player's name in public chat instead of whispering. Turning it on turns whisper off, turning it off turns whisper on.")
        .defaultValue(false)
        .onChanged(on -> {
            if (on == this.whisper.get()) this.whisper.set(!on);
        })
        .build()
    );

    private final Setting<String> suffix = sgGeneral.add(new StringSetting.Builder()
        .name("suffix")
        .description("Added after every whisper. Leave empty for none.")
        .defaultValue("| try coyduck addon » github.com/duckesteles/coyduck")
        .visible(() -> whisper.get())
        .build()
    );

    private final Setting<String> publicSuffix = sgGeneral.add(new StringSetting.Builder()
        .name("public-suffix")
        .description("Added after every public chat message. Leave empty for none.")
        .defaultValue("| coyduck addon")
        .visible(() -> publicChat.get())
        .build()
    );

    private final Setting<Boolean> smallCaps = sgGeneral.add(new BoolSetting.Builder()
        .name("small-caps")
        .description("Writes the suffix and the public suffix in small caps.")
        .defaultValue(true)
        .build()
    );

    private final Setting<String> command = sgGeneral.add(new StringSetting.Builder()
        .name("command")
        .description("Whisper command, without the slash.")
        .defaultValue("msg")
        .visible(() -> whisper.get())
        .build()
    );

    private final Setting<Boolean> onPop = sgGeneral.add(new BoolSetting.Builder()
        .name("on-pop")
        .description("Also sends a message when a player you are fighting pops a totem.")
        .defaultValue(false)
        .build()
    );

    private final Setting<List<String>> popMessages = sgGeneral.add(new StringListSetting.Builder()
        .name("pop-messages")
        .description("Picked at random for every pop. {name} is the player, {pops} how many they popped.")
        .defaultValue(List.of("{pops} down {name}", "pop {pops}"))
        .visible(onPop::get)
        .build()
    );

    private final Setting<Integer> delay = sgGeneral.add(new IntSetting.Builder()
        .name("delay")
        .description("Ticks to wait after the kill before sending.")
        .defaultValue(20)
        .range(0, 200)
        .sliderRange(0, 100)
        .build()
    );

    private final Map<UUID, Long> engaged = new HashMap<>();
    private final List<Pending> pending = new ArrayList<>();
    private int last = -1;
    private long nextSendAt;

    private record Pending(String name, long at, int pops) {}

    public AutoEz() {
        super(Coyduck.CATEGORY, "auto-ez", "Sends a good game message for players you kill, as a whisper to them or in public chat with their name.");
    }

    @Override
    public void onDeactivate() {
        reset();
    }

    private void reset() {
        engaged.clear();
        pending.clear();
        nextSendAt = 0;
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
        boolean pop = packet.getStatus() == EntityStatuses.USE_TOTEM_OF_UNDYING;
        if (!pop && packet.getStatus() != EntityStatuses.PLAY_DEATH_SOUND_OR_ADD_PROJECTILE_HIT_PARTICLES) return;
        if (!(packet.getEntity(mc.world) instanceof PlayerEntity player) || player == mc.player || Friends.get().isFriend(player)) return;

        if (pop) {
            Long at = engaged.get(player.getUuid());
            if (!onPop.get() || at == null || Util.getMeasuringTimeMs() - at > ENGAGED_MS || pending.size() >= MAX_PENDING) return;
            pending.removeIf(entry -> entry.pops > 0 && entry.name.equals(player.getName().getString()));
            pending.add(new Pending(player.getName().getString(), Util.getMeasuringTimeMs() + delay.get() * 50L, Math.max(1, PopTracker.get().pops(player))));
            return;
        }
        Long at = engaged.remove(player.getUuid());
        pending.removeIf(entry -> entry.pops > 0 && entry.name.equals(player.getName().getString()));
        if (at == null || Util.getMeasuringTimeMs() - at > ENGAGED_MS || pending.size() >= MAX_PENDING) return;
        pending.add(new Pending(player.getName().getString(), Util.getMeasuringTimeMs() + delay.get() * 50L, 0));
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        long now = Util.getMeasuringTimeMs();
        if (!engaged.isEmpty()) engaged.values().removeIf(at -> now - at > ENGAGED_MS);
        if (pending.isEmpty() || mc.getNetworkHandler() == null || now < nextSendAt) return;

        Pending first = pending.getFirst();
        if (first.at > now) return;
        pending.removeFirst();
        send(first.name, first.pops);
        nextSendAt = now + CHAT_GAP;
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        reset();
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

    private void send(String name, int pops) {
        List<String> list = pops > 0 ? popMessages.get() : messages.get();
        if (list.isEmpty()) return;

        int index = ThreadLocalRandom.current().nextInt(list.size());
        if (list.size() > 1 && index == last) index = (index + 1) % list.size();
        last = index;

        String message = list.get(index).replace("{pops}", String.valueOf(pops));
        if (publicChat.get()) {
            String text = message.contains("{name}") ? message.replace("{name}", name) : message + " " + name;
            String line = text + tail(publicSuffix.get());
            mc.getNetworkHandler().sendChatMessage(line.length() > CHAT_LIMIT ? line.substring(0, CHAT_LIMIT) : line);
            return;
        }
        if (!whisper.get()) return;

        String body = message.replace("{name}", name) + tail(suffix.get());
        String prefix = command.get().trim() + " " + name + " ";
        int room = CHAT_LIMIT - prefix.length();
        if (body.length() > room) body = body.substring(0, room);
        mc.getNetworkHandler().sendChatCommand(prefix + body);
    }

    private String tail(String text) {
        if (text.isBlank()) return "";
        return " " + (smallCaps.get() ? smallCaps(text) : text);
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
