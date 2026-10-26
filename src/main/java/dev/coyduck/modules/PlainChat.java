package dev.coyduck.modules;

import dev.coyduck.Coyduck;
import dev.coyduck.events.PacketApplyEvent;
import meteordevelopment.meteorclient.events.game.ReceiveMessageEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.ChatMessageS2CPacket;
import net.minecraft.network.packet.s2c.play.GameMessageS2CPacket;
import net.minecraft.network.packet.s2c.play.ProfilelessChatMessageS2CPacket;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PlainChat extends Module {
    private static final Pattern SEPARATOR = Pattern.compile("^\\s*(?:[:»>|]|->|=>)\\s?");
    private static final int MAX_PREFIX = 48;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> vanillaNames = sgGeneral.add(new BoolSetting.Builder()
        .name("vanilla-names")
        .description("Rewrites messages like \"[Rank] Name » hi\" into the vanilla \"<Name> hi\", dropping rank tags and symbols in front of the name.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> keepLinks = sgGeneral.add(new BoolSetting.Builder()
        .name("keep-links")
        .description("Keeps links and other click actions working. Colors, formatting and hover popups are always removed.")
        .defaultValue(true)
        .build()
    );

    private int serverDepth;

    public PlainChat() {
        super(Coyduck.CATEGORY, "plain-chat", "Shows server chat in plain vanilla style: no colors, rank tags, fancy names or hover popups. Messages from your own client keep their look.");
    }

    @Override
    public void onActivate() {
        serverDepth = 0;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        serverDepth = 0;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    private void onApplyPre(PacketApplyEvent.Pre event) {
        if (isChat(event.packet)) serverDepth++;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    private void onApplyPost(PacketApplyEvent.Post event) {
        if (serverDepth > 0 && isChat(event.packet)) serverDepth--;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    private void onMessage(ReceiveMessageEvent event) {
        if (serverDepth <= 0) return;

        Text plain = strip(event.getMessage());
        if (vanillaNames.get()) plain = vanilla(plain);
        event.setMessage(plain);
    }

    private static boolean isChat(Packet<?> packet) {
        return packet instanceof GameMessageS2CPacket game && !game.overlay()
            || packet instanceof ChatMessageS2CPacket || packet instanceof ProfilelessChatMessageS2CPacket;
    }

    private Text strip(Text text) {
        MutableText out = Text.empty();
        text.visit((style, string) -> {
            Style kept = keepLinks.get() ? Style.EMPTY.withClickEvent(style.getClickEvent()) : Style.EMPTY;
            out.append(Text.literal(string).setStyle(kept));
            return Optional.empty();
        }, Style.EMPTY);
        return out;
    }

    private Text vanilla(Text plain) {
        String string = plain.getString();
        if (string.startsWith("<")) return plain;

        for (String name : names()) {
            int at = string.indexOf(name);
            if (at < 0 || at > MAX_PREFIX) continue;
            if (at > 0 && Character.isLetterOrDigit(string.charAt(at - 1))) continue;

            Matcher separator = SEPARATOR.matcher(string.substring(at + name.length()));
            if (!separator.find()) continue;

            int start = at + name.length() + separator.end();
            MutableText out = Text.literal("<" + name + "> ");
            out.append(tail(plain, start));
            return out;
        }
        return plain;
    }

    private Text tail(Text plain, int start) {
        MutableText out = Text.empty();
        int[] index = {0};
        plain.visit((style, string) -> {
            int from = index[0];
            index[0] += string.length();
            if (index[0] <= start) return Optional.empty();
            out.append(Text.literal(string.substring(Math.max(0, start - from))).setStyle(style));
            return Optional.empty();
        }, Style.EMPTY);
        return out;
    }

    private List<String> names() {
        List<String> names = new ArrayList<>();
        if (mc.getNetworkHandler() == null) return names;
        for (PlayerListEntry entry : mc.getNetworkHandler().getPlayerList()) names.add(entry.getProfile().name());
        names.sort(Comparator.comparingInt(String::length).reversed());
        return names;
    }
}
