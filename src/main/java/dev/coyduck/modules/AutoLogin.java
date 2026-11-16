package dev.coyduck.modules;

import dev.coyduck.events.GameJoinedEvent;
import dev.coyduck.events.PacketEvent;
import dev.coyduck.events.ReceiveMessageEvent;
import dev.coyduck.events.TickEvent;
import dev.coyduck.feature.Feature;
import dev.coyduck.feature.IntSetting;
import dev.coyduck.feature.Setting;
import dev.coyduck.feature.SettingGroup;
import dev.coyduck.feature.StringListSetting;
import dev.coyduck.feature.StringSetting;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.packet.s2c.common.ServerTransferS2CPacket;
import net.minecraft.util.Util;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

public class AutoLogin extends Feature {
    private static final long JOIN_WINDOW = 60_000;
    private static final long TRANSFER_WINDOW = 30_000;
    private static final Pattern TIMESTAMP = Pattern.compile("^<\\d{1,2}:\\d{2}(?::\\d{2})?> ");

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<String> password = sgGeneral.add(new StringSetting.Builder()
        .name("password")
        .description("Your login password, stored as plain text.")
        .defaultValue("")
        .build()
    );

    private final Setting<List<String>> servers = sgGeneral.add(new StringListSetting.Builder()
        .name("servers")
        .description("Only servers ending with these get the password.")
        .defaultValue(List.of("6b6t.org"))
        .build()
    );

    private final Setting<Integer> delay = sgGeneral.add(new IntSetting.Builder()
        .name("delay")
        .description("Delay before logging in, in milliseconds.")
        .defaultValue(800)
        .range(0, 5000)
        .sliderRange(0, 3000)
        .build()
    );

    private long sendAt;
    private long joinedAt;
    private boolean sent;
    private volatile String transferHost;
    private volatile long transferAt;
    private String sessionHost;

    public AutoLogin() {
        super("auto-login", "Logs you in on listed servers.");
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
        sessionHost = transferAt != 0 && joinedAt - transferAt < TRANSFER_WINDOW ? transferHost : null;
        transferAt = 0;
    }

    @EventHandler
    private void onReceive(PacketEvent.Receive event) {
        if (!(event.packet instanceof ServerTransferS2CPacket packet)) return;
        transferHost = packet.host().toLowerCase(Locale.ROOT);
        transferAt = Util.getMeasuringTimeMs();
    }

    @EventHandler
    private void onMessage(ReceiveMessageEvent event) {
        if (sent || sendAt != 0 || password.get().isEmpty() || !trusted()) return;
        if (Util.getMeasuringTimeMs() - joinedAt > JOIN_WINDOW) return;
        String text = TIMESTAMP.matcher(event.getMessage().getString().toLowerCase(Locale.ROOT)).replaceFirst("");
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
        String host = sessionHost;
        if (host == null) {
            if (mc.getCurrentServerEntry() == null) return false;
            String address = mc.getCurrentServerEntry().address.toLowerCase(Locale.ROOT);
            int port = address.lastIndexOf(':');
            host = port > 0 ? address.substring(0, port) : address;
        }
        for (String server : servers.get()) {
            String entry = server.toLowerCase(Locale.ROOT).trim();
            if (!entry.isEmpty() && (host.equals(entry) || host.endsWith("." + entry))) return true;
        }
        return false;
    }
}
