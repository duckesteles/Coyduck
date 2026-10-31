package dev.coyduck.hud;

import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.hud.HudElement;
import meteordevelopment.meteorclient.systems.hud.HudElementInfo;
import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.world.TickRate;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public class InfoHud extends HudElement {
    public static final HudElementInfo<InfoHud> INFO = new HudElementInfo<>(CoyHud.GROUP, "coy-info", "FPS, ping, TPS, speed and coordinates in one panel.", InfoHud::new);

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> fps = add("fps", "Frames per second.");
    private final Setting<Boolean> ping = add("ping", "Your ping.");
    private final Setting<Boolean> tps = add("tps", "Server ticks per second.");
    private final Setting<Boolean> speed = add("speed", "Your speed in blocks per second.");
    private final Setting<Boolean> coords = add("coords", "Your coordinates, with the other dimension's next to them.");

    private record Line(String label, String value, int color) {}

    public InfoHud() {
        super(INFO);
    }

    private Setting<Boolean> add(String name, String description) {
        return sgGeneral.add(new BoolSetting.Builder().name(name).description(description).defaultValue(true).build());
    }

    @Override
    public void render(HudRenderer r) {
        List<Line> lines = new ArrayList<>();
        if (fps.get()) lines.add(new Line("FPS", String.valueOf(mc.getCurrentFps()), CoyHud.TEXT));
        if (ping.get()) {
            int latency = latency();
            lines.add(new Line("Ping", latency + "ms", latency > 250 ? CoyHud.BAD : latency > 150 ? CoyHud.WARN : CoyHud.TEXT));
        }
        if (tps.get()) {
            float rate = TickRate.INSTANCE.getTickRate();
            lines.add(new Line("TPS", String.format(Locale.ROOT, "%.1f", rate), rate < 15 ? CoyHud.BAD : rate < 19 ? CoyHud.WARN : CoyHud.TEXT));
        }
        if (speed.get() && mc.player != null) {
            double dx = mc.player.getX() - mc.player.lastX;
            double dz = mc.player.getZ() - mc.player.lastZ;
            lines.add(new Line("Speed", String.format(Locale.ROOT, "%.1f", Math.sqrt(dx * dx + dz * dz) * 20), CoyHud.TEXT));
        }
        if (coords.get() && mc.player != null && mc.world != null) {
            double px = mc.player.getX();
            double pz = mc.player.getZ();
            boolean nether = mc.world.getRegistryKey() == World.NETHER;
            double scale = nether ? 8 : 1 / 8.0;
            String main = String.format(Locale.ROOT, "%.0f %.0f %.0f", px, mc.player.getY(), pz);
            String other = String.format(Locale.ROOT, "%.0f %.0f", px * scale, pz * scale);
            lines.add(new Line("XYZ", main, CoyHud.TEXT));
            if (mc.world.getRegistryKey() != World.END) lines.add(new Line(nether ? "Overworld" : "Nether", other, CoyHud.SUBTLE));
        }

        double pad = 6;
        double gap = 10;
        double labelWidth = 0;
        double valueWidth = 0;
        for (Line line : lines) {
            labelWidth = Math.max(labelWidth, r.textWidth(line.label));
            valueWidth = Math.max(valueWidth, r.textWidth(line.value));
        }
        double rowHeight = r.textHeight() + 3;
        double w = pad * 2 + labelWidth + gap + valueWidth;
        double h = pad * 2 + rowHeight * lines.size() - 3;
        setSize(Math.max(w, 40), Math.max(h, rowHeight));
        if (lines.isEmpty()) return;

        CoyHud.panel(r, x, y, w, h, 1);
        boolean right = getX() + getWidth() / 2.0 > Utils.getWindowWidth() / 2.0;
        double rowY = y + pad;
        for (Line line : lines) {
            r.text(line.label, x + pad, rowY, CoyHud.color(CoyHud.SUBTLE), false);
            double vx = right ? x + w - pad - r.textWidth(line.value) : x + pad + labelWidth + gap;
            r.text(line.value, vx, rowY, CoyHud.color(line.color), false);
            rowY += rowHeight;
        }
    }

    private static int latency() {
        if (mc.getNetworkHandler() == null || mc.player == null) return 0;
        PlayerListEntry entry = mc.getNetworkHandler().getPlayerListEntry(mc.player.getUuid());
        return entry == null ? 0 : entry.getLatency();
    }
}
