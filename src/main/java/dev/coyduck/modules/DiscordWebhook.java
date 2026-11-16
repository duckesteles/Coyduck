package dev.coyduck.modules;

import dev.coyduck.core.Core;
import dev.coyduck.events.GameLeftEvent;
import dev.coyduck.events.PacketApplyEvent;
import dev.coyduck.events.TickEvent;
import dev.coyduck.feature.BoolSetting;
import dev.coyduck.feature.Feature;
import dev.coyduck.feature.IntSetting;
import dev.coyduck.feature.Setting;
import dev.coyduck.feature.SettingGroup;
import dev.coyduck.feature.StringSetting;
import dev.coyduck.managers.FightTracker;
import dev.coyduck.managers.PopTracker;
import meteordevelopment.meteorclient.events.game.ReceiveMessageEvent;
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
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

public class DiscordWebhook extends Feature {
    private static final long SEND_GAP = 2_000;
    private static final int MAX_QUEUE = 20;
    private static final Pattern COORDS = Pattern.compile("-?\\d{3,}[ ,]+-?\\d{1,3}[ ,]+-?\\d{3,}|-?\\d{3,}[ ,]+-?\\d{3,}");
    private static final int GREEN = 0x57F287;
    private static final int RED = 0xED4245;
    private static final int YELLOW = 0xFEE75C;
    private static final int ORANGE = 0xF0883E;
    private static final int PURPLE = 0x9B6BDF;
    private static final int BLUE = 0x5865F2;
    private static final int GREY = 0x99AAB5;
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

    private final Setting<Boolean> embeds = sgGeneral.add(new BoolSetting.Builder()
        .name("embeds")
        .description("Sends colored cards instead of plain text.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> kills = sgEvents.add(new BoolSetting.Builder()
        .name("kills")
        .description("You kill someone.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> enemyPops = sgEvents.add(new BoolSetting.Builder()
        .name("enemy-pops")
        .description("Someone you fight pops a totem.")
        .defaultValue(false)
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

    private record Alert(boolean important, String title, String text, int color, long at) {}

    private final ArrayDeque<Alert> queue = new ArrayDeque<>();
    private final Set<UUID> inView = new HashSet<>();
    private long nextSend;
    private boolean lowSent;

    public DiscordWebhook() {
        super("discord-webhook", "Sends alerts to Discord.");
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
                    if (player == mc.player || Core.host().isFriend(player)) continue;
                    now.add(player.getUuid());
                    if (!inView.contains(player.getUuid())) send(true, "Player in view", player.getName().getString() + " came into view.", BLUE);
                }
                inView.clear();
                inView.addAll(now);
            }
            int threshold = lowHealth.get();
            float health = mc.player.getHealth() + mc.player.getAbsorptionAmount();
            if (threshold > 0 && health <= threshold && mc.player.isAlive() && !lowSent) {
                lowSent = true;
                send(true, "Low health", "Your health is " + Math.round(health) + ".", ORANGE);
            } else if (health > threshold + 4) {
                lowSent = false;
            }
        }
        flush();
    }

    @EventHandler
    private void onPacket(PacketApplyEvent.Pre event) {
        if (!(event.packet instanceof EntityStatusS2CPacket packet) || mc.world == null || mc.player == null) return;
        boolean pop = packet.getStatus() == EntityStatuses.USE_TOTEM_OF_UNDYING;
        boolean died = packet.getStatus() == EntityStatuses.PLAY_DEATH_SOUND_OR_ADD_PROJECTILE_HIT_PARTICLES;
        if (!pop && !died || !(packet.getEntity(mc.world) instanceof PlayerEntity player)) return;
        if (player == mc.player) {
            if (pop && pops.get()) send(true, "Totem popped", "You popped a totem.", YELLOW);
            if (died && death.get()) send(true, "Death", "You died.", RED);
            return;
        }
        if (Core.host().isFriend(player) || !FightTracker.get().engaged(player)) return;
        String name = player.getName().getString();
        int count = PopTracker.get().pops(player);
        if (pop && enemyPops.get()) send(false, "Enemy pop", name + " popped " + times(Math.max(1, count)) + ".", PURPLE);
        if (died && kills.get()) send(false, "Kill", "You killed " + name + (count > 0 ? " after " + times(count) + "." : "."), GREEN);
    }

    @EventHandler
    private void onMessage(ReceiveMessageEvent event) {
        if (!whispers.get()) return;
        String text = event.getMessage().getString();
        String lower = text.toLowerCase();
        if (lower.contains("whispers to you") || lower.contains("whispers:") || lower.contains("-> me") || lower.contains("-> you")) send(true, "Whisper", text, GREY);
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        inView.clear();
        if (!disconnect.get()) return;
        send(true, "Disconnected", "You left the server.", GREY);
        while (queue.size() > 1) queue.poll();
        nextSend = 0;
        flush();
    }

    private static String times(int count) {
        return count == 1 ? "1 totem" : count + " totems";
    }

    private void send(boolean important, String title, String text, int color) {
        if (url.get().isBlank() || queue.size() >= MAX_QUEUE) return;
        queue.add(new Alert(important, title, COORDS.matcher(text).replaceAll("[hidden]"), color, System.currentTimeMillis()));
    }

    private void flush() {
        long now = Util.getMeasuringTimeMs();
        if (queue.isEmpty() || now < nextSend) return;
        nextSend = now + SEND_GAP;
        Alert alert = queue.poll();
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url.get().trim()))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body(alert)))
                .build();
            CLIENT.sendAsync(request, HttpResponse.BodyHandlers.discarding());
        } catch (IllegalArgumentException exception) {
            warning("The webhook url is not valid.");
            queue.clear();
        }
    }

    private String body(Alert alert) {
        String ping = alert.important && !mention.get().isBlank() ? mention.get().trim() : "";
        String text = alert.text.length() > 1900 ? alert.text.substring(0, 1900) : alert.text;
        if (!embeds.get()) return "{\"content\":\"" + escape(ping.isEmpty() ? text : ping + " " + text) + "\"}";
        String server = mc.getCurrentServerEntry() != null ? mc.getCurrentServerEntry().address : "";
        String footer = server.isEmpty() ? "Coyduck" : "Coyduck · " + server;
        return "{\"content\":\"" + escape(ping) + "\",\"embeds\":[{"
            + "\"title\":\"" + escape(alert.title) + "\","
            + "\"description\":\"" + escape(text) + "\","
            + "\"color\":" + alert.color + ","
            + "\"footer\":{\"text\":\"" + escape(footer) + "\"},"
            + "\"timestamp\":\"" + Instant.ofEpochMilli(alert.at) + "\"}]}";
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
