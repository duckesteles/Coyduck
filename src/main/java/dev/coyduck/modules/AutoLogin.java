package dev.coyduck.modules;

import dev.coyduck.Coyduck;
import meteordevelopment.meteorclient.events.game.GameJoinedEvent;
import meteordevelopment.meteorclient.events.game.ReceiveMessageEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringListSetting;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.util.Util;

import java.util.List;
import java.util.Locale;

public class AutoLogin extends Module {
    private static final long JOIN_WINDOW = 60_000;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<String> password = sgGeneral.add(new StringSetting.Builder()
        .name("password")
        .description("Your login password. Meteor saves settings as plain text in its config folder.")
        .defaultValue("")
        .build()
    );

    private final Setting<List<String>> servers = sgGeneral.add(new StringListSetting.Builder()
        .name("servers")
        .description("The password is only sent to servers whose address ends with one of these, so another server asking for /login never gets it.")
        .defaultValue(List.of("6b6t.org"))
        .build()
    );

    private final Setting<Integer> delay = sgGeneral.add(new IntSetting.Builder()
        .name("delay")
        .description("Milliseconds to wait after the server asks before logging in.")
        .defaultValue(800)
        .range(0, 5000)
        .sliderRange(0, 3000)
        .build()
    );

    private long sendAt;
    private long joinedAt;
    private boolean sent;

    public AutoLogin() {
        super(Coyduck.CATEGORY, "auto-login", "Types /login with your password when a server you listed asks for it in the first minute after you join. Messages from other players never trigger it.");
    }

    @Override
    public void onActivate() {
        sendAt = 0;
        sent = false;
        joinedAt = Util.getMeasuringTimeMs();
    }

    @EventHandler
    private void onJoin(GameJoinedEvent event) {
        sendAt = 0;
        sent = false;
        joinedAt = Util.getMeasuringTimeMs();
    }

    @EventHandler
    private void onMessage(ReceiveMessageEvent event) {
        if (sent || sendAt != 0 || password.get().isEmpty() || !trusted()) return;
        if (Util.getMeasuringTimeMs() - joinedAt > JOIN_WINDOW) return;
        String text = event.getMessage().getString().toLowerCase(Locale.ROOT);
        if (text.contains("»") || text.startsWith("<") || text.contains("whispers") || text.contains(" -> ")) return;
        if (text.contains("/login") && (text.contains("please login") || text.contains("please log in") || text.contains("login with"))) {
            sendAt = Util.getMeasuringTimeMs() + delay.get();
        }
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (sendAt == 0 || sent || Util.getMeasuringTimeMs() < sendAt || mc.getNetworkHandler() == null) return;
        sent = true;
        sendAt = 0;
        if (!trusted()) return;
        mc.getNetworkHandler().sendChatCommand("login " + password.get());
        info("Logged in.");
    }

    private boolean trusted() {
        if (mc.getCurrentServerEntry() == null) return false;
        String address = mc.getCurrentServerEntry().address.toLowerCase(Locale.ROOT);
        int port = address.lastIndexOf(':');
        String host = port > 0 ? address.substring(0, port) : address;
        for (String server : servers.get()) {
            String entry = server.toLowerCase(Locale.ROOT).trim();
            if (!entry.isEmpty() && (host.equals(entry) || host.endsWith("." + entry))) return true;
        }
        return false;
    }
}
