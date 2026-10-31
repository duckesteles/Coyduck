package dev.coyduck.hud;

import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.systems.hud.HudElement;
import meteordevelopment.meteorclient.systems.hud.HudElementInfo;
import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import net.minecraft.client.world.ClientWorld;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public class ClockHud extends HudElement {
    public static final HudElementInfo<ClockHud> INFO = new HudElementInfo<>(CoyHud.GROUP, "coy-clock", "Real time and date in any time zone, with your session length.", ClockHud::new);

    private static final double BIG = 1.6;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<String> zone = sgGeneral.add(new StringSetting.Builder()
        .name("time-zone")
        .description("Empty uses your computer's time zone. Otherwise a zone like Europe/Istanbul, America/New_York, UTC or GMT+3.")
        .defaultValue("")
        .build()
    );

    private final Setting<Boolean> hours24 = sgGeneral.add(new BoolSetting.Builder()
        .name("24-hour")
        .description("Uses a 24 hour clock instead of AM and PM.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> seconds = sgGeneral.add(new BoolSetting.Builder()
        .name("seconds")
        .description("Shows seconds.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> date = sgGeneral.add(new BoolSetting.Builder()
        .name("date")
        .description("Shows the day and date in your computer's language.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> zoneName = sgGeneral.add(new BoolSetting.Builder()
        .name("zone-name")
        .description("Shows which time zone the clock is in.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> greeting = sgGeneral.add(new BoolSetting.Builder()
        .name("greeting")
        .description("Says good morning, afternoon, evening or night with your name.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> session = sgGeneral.add(new BoolSetting.Builder()
        .name("session")
        .description("Shows how long you have been in this world or server.")
        .defaultValue(true)
        .build()
    );

    private ClientWorld world;
    private long joined;

    public ClockHud() {
        super(INFO);
    }

    @Override
    public void render(HudRenderer r) {
        if (mc.world != world) {
            world = mc.world;
            joined = System.currentTimeMillis();
        }

        ZoneId id = zoneId();
        ZonedDateTime now = ZonedDateTime.now(id);
        Locale locale = Locale.getDefault();

        String pattern = (hours24.get() ? "HH:mm" : "h:mm") + (seconds.get() ? ":ss" : "") + (hours24.get() ? "" : " a");
        String time = now.format(DateTimeFormatter.ofPattern(pattern, Locale.ROOT));

        List<String> small = new ArrayList<>();
        if (date.get()) {
            String text = now.format(DateTimeFormatter.ofPattern("EEEE, d MMMM", locale));
            if (zoneName.get()) text += "  " + id.getId();
            small.add(text);
        } else if (zoneName.get()) {
            small.add(id.getId());
        }
        if (greeting.get() && mc.player != null) small.add(greet(now.getHour()) + ", " + mc.player.getName().getString());
        if (session.get() && world != null) small.add("Session " + duration(System.currentTimeMillis() - joined));

        double pad = 6;
        double bar = 3;
        double timeWidth = r.textWidth(time, false, BIG);
        double timeHeight = r.textHeight(false, BIG);
        double width = timeWidth;
        for (String line : small) width = Math.max(width, r.textWidth(line));
        double lineHeight = r.textHeight() + 2;
        double w = pad * 2 + bar + width;
        double h = pad * 2 + timeHeight + (small.isEmpty() ? 0 : 3 + lineHeight * small.size() - 2);
        setSize(w, h);

        CoyHud.panel(r, x, y, w, h, 1);
        CoyHud.round(r, x, y + 4, 2, h - 8, 1, CoyHud.color(CoyHud.accent()));

        double tx = x + pad + bar;
        double ty = y + pad;
        r.text(time, tx, ty, CoyHud.color(CoyHud.TEXT), false, BIG);
        ty += timeHeight + 3;
        for (int i = 0; i < small.size(); i++) {
            r.text(small.get(i), tx, ty, CoyHud.color(i == 0 && date.get() ? CoyHud.SUBTLE : CoyHud.MUTED), false);
            ty += lineHeight;
        }
    }

    private ZoneId zoneId() {
        String value = zone.get().trim();
        if (value.isEmpty()) return ZoneId.systemDefault();
        try {
            return ZoneId.of(value);
        } catch (DateTimeException ignored) {
            return ZoneId.systemDefault();
        }
    }

    private static String greet(int hour) {
        if (hour >= 5 && hour < 12) return "Good morning";
        if (hour >= 12 && hour < 18) return "Good afternoon";
        if (hour >= 18 && hour < 23) return "Good evening";
        return "Good night";
    }

    private static String duration(long millis) {
        long minutes = millis / 60_000;
        long hours = minutes / 60;
        minutes %= 60;
        return hours > 0 ? hours + "h " + minutes + "m" : minutes + "m";
    }
}
