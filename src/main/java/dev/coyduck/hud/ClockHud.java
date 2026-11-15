package dev.coyduck.hud;

import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.systems.hud.HudElement;
import meteordevelopment.meteorclient.systems.hud.HudElementInfo;
import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import meteordevelopment.meteorclient.utils.Utils;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public class ClockHud extends HudElement {
    public static final HudElementInfo<ClockHud> INFO = new HudElementInfo<>(CoyHud.GROUP, "coy-clock", "Real time clock.", ClockHud::new);

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final CoyHud.Background background = new CoyHud.Background(settings);

    private final Setting<Double> size = sgGeneral.add(new DoubleSetting.Builder()
        .name("size")
        .description("Size of the card.")
        .defaultValue(1)
        .range(0.5, 4)
        .sliderRange(0.5, 3)
        .build()
    );

    private final Setting<Double> timeSize = sgGeneral.add(new DoubleSetting.Builder()
        .name("time-size")
        .description("How much bigger the time is than the lines under it.")
        .defaultValue(1)
        .range(1, 3)
        .sliderRange(1, 2)
        .build()
    );

    private final Setting<String> zone = sgGeneral.add(new StringSetting.Builder()
        .name("time-zone")
        .description("Time zone like UTC or GMT+3; empty uses yours.")
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
        .name("show-date")
        .description("Shows the day and date in your computer's language.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> accentTime = sgGeneral.add(new BoolSetting.Builder()
        .name("accent-time")
        .description("Draws the time in the accent color instead of white.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> zoneName = sgGeneral.add(new BoolSetting.Builder()
        .name("zone-name")
        .description("Shows which time zone the clock is in.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> greeting = sgGeneral.add(new BoolSetting.Builder()
        .name("show-greeting")
        .description("Greets you by name for the time of day.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> session = sgGeneral.add(new BoolSetting.Builder()
        .name("show-session")
        .description("Shows how long you have been in this world or server.")
        .defaultValue(false)
        .build()
    );

    private Object connection;
    private long joined;

    public ClockHud() {
        super(INFO);
    }

    @Override
    public void render(HudRenderer r) {
        if (mc.getNetworkHandler() != connection) {
            connection = mc.getNetworkHandler();
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
        if (session.get() && connection != null) small.add("Session " + duration(System.currentTimeMillis() - joined));

        double scale = size.get();
        double big = scale * timeSize.get();
        boolean shadow = background.shadow();
        boolean boxed = background.boxed();
        double pad = boxed ? 5 * scale : 0;
        double bar = boxed ? 3 * scale : 0;
        double timeWidth = Math.max(r.textWidth(time, shadow, big), r.textWidth(steady(time), shadow, big));
        double timeHeight = r.textHeight(shadow, big);
        double width = timeWidth;
        for (String line : small) width = Math.max(width, r.textWidth(line, shadow, scale));
        double lineHeight = r.textHeight(shadow, scale) + 2 * scale;
        double w = pad * 2 + bar + width;
        double h = pad * 2 + timeHeight + (small.isEmpty() ? 0 : 2 * scale + lineHeight * small.size() - 2 * scale);
        setSize(w, h);
        int x = box.getRenderX();
        int y = box.getRenderY();
        boolean right = getX() + getWidth() / 2.0 > Utils.getWindowWidth() / 2.0;

        CoyHud.panel(r, x, y, w, h, background.alpha());
        if (boxed) CoyHud.round(r, x, y + 3 * scale, 2 * scale, h - 6 * scale, 1, CoyHud.color(CoyHud.accent()));

        double left = x + pad + bar;
        double end = x + w - pad;
        double ty = y + pad;
        r.text(time, right ? end - r.textWidth(time, shadow, big) : left, ty, CoyHud.color(accentTime.get() ? CoyHud.accent() : CoyHud.TEXT), shadow, big);
        ty += timeHeight + 2 * scale;
        for (int i = 0; i < small.size(); i++) {
            String line = small.get(i);
            r.text(line, right ? end - r.textWidth(line, shadow, scale) : left, ty, CoyHud.color(i == 0 && date.get() ? CoyHud.SUBTLE : CoyHud.MUTED), shadow, scale);
            ty += lineHeight;
        }
    }

    private static String steady(String time) {
        return time.replaceAll("[0-9]", "0");
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
