package dev.coyduck.hud;

import dev.coyduck.feature.BoolSetting;
import dev.coyduck.feature.IntSetting;
import dev.coyduck.feature.Setting;
import dev.coyduck.feature.SettingGroup;
import dev.coyduck.managers.LatencyManager;
import dev.coyduck.managers.TickRate;
import dev.coyduck.render.Palette;
import dev.coyduck.render.Panel;
import dev.coyduck.widget.Widget;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static dev.coyduck.core.Core.mc;

public class InfoHud extends Widget {
    public static final Info INFO = new Info("coy-info", "FPS, ping, TPS, speed and coordinates.", InfoHud::new);

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final CoyHud.Background background = new CoyHud.Background(settings);

    private static final long SETBACK_SHOWN = 1_500;

    private final Setting<Boolean> fps = add("fps", "Frames per second.");
    private final Setting<Boolean> ping = add("ping", "Your ping.");
    private final Setting<Boolean> tps = add("tps", "Server ticks per second.");
    private final Setting<Boolean> lag = add("lag", "Shows how long the server has been silent, and fresh setbacks.");
    private final Setting<Boolean> speed = add("speed", "Your speed in blocks per second.");
    private final Setting<Boolean> coords = add("coords", "Your coordinates, with the other dimension's next to them.");
    private final Setting<Integer> hideBeyond = sgGeneral.add(new IntSetting.Builder()
        .name("hide-coords-beyond")
        .description("Hides coords beyond this distance from 0 0. 0 is off.")
        .defaultValue(0)
        .range(0, 30_000_000)
        .sliderRange(0, 100_000)
        .visible(coords::get)
        .build()
    );
    private final Setting<Boolean> facing = add("facing", "Your facing direction and its axis.", false);
    private final Setting<Boolean> biome = add("biome", "The biome you are in.", false);
    private final Setting<Boolean> dimension = add("dimension", "The dimension you are in.", false);
    private final Setting<Boolean> day = add("day", "The in-game day and time.", false);
    private final Setting<Boolean> server = add("server", "The server address.", false);
    private final Setting<Boolean> players = add("players", "How many players are online.", false);

    private record Line(String label, String value, int color) {}

    public InfoHud() {
        super(INFO);
    }

    private Setting<Boolean> add(String name, String description) {
        return add(name, description, true);
    }

    private Setting<Boolean> add(String name, String description, boolean value) {
        return sgGeneral.add(new BoolSetting.Builder().name(name).description(description).defaultValue(value).build());
    }

    @Override
    public void render(Panel r) {
        List<Line> lines = new ArrayList<>();
        if (fps.get()) lines.add(new Line("FPS", String.valueOf(mc.getCurrentFps()), Palette.TEXT));
        if (ping.get()) {
            int latency = latency();
            lines.add(new Line("Ping", latency + "ms", latency > 250 ? Palette.BAD : latency > 150 ? Palette.WARN : Palette.TEXT));
        }
        if (lag.get() && mc.getNetworkHandler() != null) {
            LatencyManager latency = LatencyManager.get();
            if (latency.frozen()) lines.add(new Line("Lag", String.format(Locale.ROOT, "%.1fs", latency.silentFor() / 1000.0), Palette.BAD));
            if (latency.sinceSetback() < SETBACK_SHOWN) lines.add(new Line("Setback", "now", Palette.WARN));
        }
        if (tps.get()) {
            float rate = TickRate.get().tickRate();
            lines.add(new Line("TPS", String.format(Locale.ROOT, "%.1f", rate), rate < 15 ? Palette.BAD : rate < 19 ? Palette.WARN : Palette.TEXT));
        }
        if (speed.get() && mc.player != null) {
            double dx = mc.player.getX() - mc.player.lastX;
            double dz = mc.player.getZ() - mc.player.lastZ;
            lines.add(new Line("Speed", String.format(Locale.ROOT, "%.1f", Math.sqrt(dx * dx + dz * dz) * 20), Palette.TEXT));
        }
        if (coords.get() && mc.player != null && mc.world != null) {
            double px = mc.player.getX();
            double pz = mc.player.getZ();
            boolean nether = mc.world.getRegistryKey() == World.NETHER;
            double scale = nether ? 8 : 1 / 8.0;
            String main = String.format(Locale.ROOT, "%.0f %.0f %.0f", px, mc.player.getY(), pz);
            String other = String.format(Locale.ROOT, "%.0f %.0f", px * scale, pz * scale);
            int limit = hideBeyond.get();
            if (limit > 0 && Math.max(Math.abs(px), Math.abs(pz)) > limit) {
                lines.add(new Line("XYZ", "hidden", Palette.MUTED));
            } else {
                lines.add(new Line("XYZ", main, Palette.TEXT));
                if (mc.world.getRegistryKey() != World.END) lines.add(new Line(nether ? "Overworld" : "Nether", other, Palette.SUBTLE));
            }
        }
        if (facing.get() && mc.player != null) {
            float yaw = MathHelper.wrapDegrees(mc.player.getYaw());
            Direction direction = mc.player.getHorizontalFacing();
            String axis = (direction.getDirection() == Direction.AxisDirection.POSITIVE ? "+" : "-") + direction.getAxis().asString().toUpperCase(Locale.ROOT);
            lines.add(new Line("Facing", capitalize(direction.asString()) + " " + axis + String.format(Locale.ROOT, " %.0f°", yaw), Palette.TEXT));
        }
        if (biome.get() && mc.player != null && mc.world != null) {
            String name = mc.world.getBiome(mc.player.getBlockPos()).getKey().map(key -> key.getValue().getPath()).orElse("unknown");
            lines.add(new Line("Biome", capitalize(name.replace('_', ' ')), Palette.TEXT));
        }
        if (dimension.get() && mc.world != null) {
            lines.add(new Line("Dimension", capitalize(mc.world.getRegistryKey().getValue().getPath().replace('_', ' ')), Palette.TEXT));
        }
        if (day.get() && mc.world != null) {
            long time = mc.world.getTimeOfDay();
            long hours = (time / 1000 + 6) % 24;
            long minutes = (time % 1000) * 60 / 1000;
            lines.add(new Line("Day", String.format(Locale.ROOT, "%d  %02d:%02d", time / 24000 + 1, hours, minutes), Palette.TEXT));
        }
        if (server.get()) {
            ServerInfo entry = mc.getCurrentServerEntry();
            lines.add(new Line("Server", mc.isInSingleplayer() ? "Singleplayer" : entry == null ? "-" : entry.address, Palette.TEXT));
        }
        if (players.get() && mc.getNetworkHandler() != null) {
            lines.add(new Line("Players", String.valueOf(mc.getNetworkHandler().getPlayerList().size()), Palette.TEXT));
        }

        double pad = 6;
        double gap = 10;
        double labelWidth = 0;
        double valueWidth = 0;
        for (Line line : lines) {
            labelWidth = Math.max(labelWidth, r.textWidth(line.label, false, Panel.DEFAULT_SCALE));
            valueWidth = Math.max(valueWidth, r.textWidth(line.value, false, Panel.DEFAULT_SCALE));
        }
        double rowHeight = r.textHeight(false, Panel.DEFAULT_SCALE) + 3;
        double w = pad * 2 + labelWidth + gap + valueWidth;
        double h = pad * 2 + rowHeight * lines.size() - 3;
        size(Math.max(w, 40), Math.max(h, rowHeight));
        int x = x();
        int y = y();
        if (lines.isEmpty()) return;

        CoyHud.panel(r, x, y, w, h, background.alpha());
        boolean right = rightSide();
        double rowY = y + pad;
        for (Line line : lines) {
            r.text(line.label, x + pad, rowY, Palette.SUBTLE, background.shadow(), Panel.DEFAULT_SCALE);
            double vx = right ? x + w - pad - r.textWidth(line.value, false, Panel.DEFAULT_SCALE) : x + pad + labelWidth + gap;
            r.text(line.value, vx, rowY, (line.color), background.shadow(), Panel.DEFAULT_SCALE);
            rowY += rowHeight;
        }
    }

    private static String capitalize(String text) {
        return text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    private static int latency() {
        if (mc.getNetworkHandler() == null || mc.player == null) return 0;
        return Math.max(LatencyManager.get().measuredPing(), 0);
    }
}
