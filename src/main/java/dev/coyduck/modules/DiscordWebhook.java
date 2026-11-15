package dev.coyduck.modules;

import dev.coyduck.Coyduck;
import dev.coyduck.events.PacketApplyEvent;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.game.ReceiveMessageEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.EntityStatuses;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.packet.s2c.play.EntityStatusS2CPacket;
import net.minecraft.util.Util;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

public class DiscordWebhook extends Module {
    private static final long SEND_GAP = 2_000;
    private static final int MAX_QUEUE = 20;
    private static final Pattern COORDS = Pattern.compile("-?\\d{3,}[ ,]+-?\\d{1,3}[ ,]+-?\\d{3,}|-?\\d{3,}[ ,]+-?\\d{3,}");
    private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgEvents = settings.createGroup("Events");

    private final Setting<String> url = sgGeneral.add(new StringSetting.Builder()
        .name("url")
        .description("Your Discord webhook link.")
        .defaultValue("")
        .build()
    );

    private final Setting<String> mention = sgGeneral.add(new StringSetting.Builder()
        .name("mention")
        .description("Prefix for important alerts, like <@your id>.")
        .defaultValue("")
        .build()
    );

    private final Setting<Boolean> players = sgEvents.add(new BoolSetting.Builder()
        .name("players")
        .description("Someone who is not a friend comes into view.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> disconnect = sgEvents.add(new BoolSetting.Builder()
        .name("disconnect")
        .description("You get disconnected.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> pops = sgEvents.add(new BoolSetting.Builder()
        .name("pops")
        .description("You pop a totem.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> death = sgEvents.add(new BoolSetting.Builder()
        .name("death")
        .description("You die.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> lowHealth = sgEvents.add(new IntSetting.Builder()
        .name("low-health")
        .description("Alerts when your health drops to this. 0 turns it off.")
        .defaultValue(8)
        .range(0, 19)
        .sliderRange(0, 19)
        .build()
    );

    private final Setting<Boolean> whispers = sgEvents.add(new BoolSetting.Builder()
        .name("whispers")
        .description("Someone whispers to you.")
        .defaultValue(true)
        .build()
    );

    private final ArrayDeque<String> queue = new ArrayDeque<>();
    private final Set<UUID> inView = new HashSet<>();
    private long nextSend;
    private boolean lowSent;

    public DiscordWebhook() {
        super(Coyduck.CATEGORY, "discord-webhook", "Sends alerts to Discord.");
    }

    @Override
    public void onActivate() {
        inView.clear();
        queue.clear();
        lowSent = false;
        if (url.get().isBlank()) warning("Set the webhook url first.");
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player != null && mc.world != null) {
            if (players.get()) {
                Set<UUID> now = new HashSet<>();
                for (PlayerEntity player : mc.world.getPlayers()) {
                    if (player == mc.player || Friends.get().isFriend(player)) continue;
                    now.add(player.getUuid());
                    if (!inView.contains(player.getUuid())) send(true, player.getName().getString() + " came into view.");
                }
                inView.clear();
                inView.addAll(now);
            }
            int threshold = lowHealth.get();
            float health = mc.player.getHealth() + mc.player.getAbsorptionAmount();
            if (threshold > 0 && health <= threshold && mc.player.isAlive() && !lowSent) {
                lowSent = true;
                send(true, "Low health: " + Math.round(health) + ".");
            } else if (health > threshold + 4) {
                lowSent = false;
            }
        }
        flush();
    }

    @EventHandler
    private void onPacket(PacketApplyEvent.Pre event) {
        if (!(event.packet instanceof EntityStatusS2CPacket packet) || mc.world == null || mc.player == null) return;
        if (packet.getEntity(mc.world) != mc.player) return;
        if (packet.getStatus() == EntityStatuses.USE_TOTEM_OF_UNDYING && pops.get()) send(true, "You popped a totem.");
        if (packet.getStatus() == EntityStatuses.PLAY_DEATH_SOUND_OR_ADD_PROJECTILE_HIT_PARTICLES && death.get()) send(true, "You died.");
    }

    @EventHandler
    private void onMessage(ReceiveMessageEvent event) {
        if (!whispers.get()) return;
        String text = event.getMessage().getString();
        String lower = text.toLowerCase();
        if (lower.contains("whispers to you") || lower.contains("whispers:") || lower.contains("-> me") || lower.contains("-> you")) send(true, "Whisper: " + text);
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        inView.clear();
        if (!disconnect.get()) return;
        send(true, "Disconnected.");
        while (queue.size() > 1) queue.poll();
        nextSend = 0;
        flush();
    }

    private void send(boolean important, String text) {
        if (url.get().isBlank() || queue.size() >= MAX_QUEUE) return;
        String clean = COORDS.matcher(text).replaceAll("[hidden]");
        String prefix = important && !mention.get().isBlank() ? mention.get() + " " : "";
        queue.add(prefix + clean);
    }

    private void flush() {
        long now = Util.getMeasuringTimeMs();
        if (queue.isEmpty() || now < nextSend) return;
        nextSend = now + SEND_GAP;
        String content = queue.poll();
        String body = "{\"content\":\"" + escape(content.length() > 1900 ? content.substring(0, 1900) : content) + "\"}";
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url.get().trim()))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
            CLIENT.sendAsync(request, HttpResponse.BodyHandlers.discarding());
        } catch (IllegalArgumentException exception) {
            warning("The webhook url is not valid.");
            queue.clear();
        }
    }

    private static String escape(String text) {
        StringBuilder out = new StringBuilder(text.length() + 16);
        for (char c : text.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        return out.toString();
    }
}
