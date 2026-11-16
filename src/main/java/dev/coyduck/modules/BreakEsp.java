package dev.coyduck.modules;

import dev.coyduck.MeteorColors;
import dev.coyduck.feature.ColorSetting;
import dev.coyduck.feature.Feature;
import dev.coyduck.feature.Rgba;
import dev.coyduck.feature.Setting;
import dev.coyduck.feature.SettingGroup;
import dev.coyduck.managers.MiningTracker;
import dev.coyduck.utils.DuckRender;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.util.Util;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

public class BreakEsp extends Feature {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Rgba> startColor = sgGeneral.add(new ColorSetting.Builder()
        .name("start-color")
        .description("Color when the block has just started breaking.")
        .defaultValue(new Rgba(255, 255, 255, 40))
        .build()
    );

    private final Setting<Rgba> endColor = sgGeneral.add(new ColorSetting.Builder()
        .name("end-color")
        .description("Color when the block is about to break.")
        .defaultValue(new Rgba(230, 60, 90, 80))
        .build()
    );

    private static final class Entry {
        final BlockPos pos;
        double progress;
        double target;
        double shown;
        boolean present;

        Entry(BlockPos pos) {
            this.pos = pos;
        }
    }

    private final Long2ObjectOpenHashMap<Entry> entries = new Long2ObjectOpenHashMap<>();
    private final Color side = new Color();
    private final Color line = new Color();
    private final Color edgeStart = new Color();
    private final Color edgeEnd = new Color();
    private long last;

    public BreakEsp() {
        super("break-esp+", "Shows blocks being mined.");
    }

    @Override
    public void onActivate() {
        entries.clear();
        last = 0;
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (mc.player == null) return;

        long now = Util.getMeasuringTimeMs();
        double delta = last == 0 ? 0 : Math.min((now - last) / 1000.0, 0.1);
        last = now;

        for (Entry entry : entries.values()) entry.present = false;
        MiningTracker.get().forEach((pos, mining) -> {
            Entry entry = entries.computeIfAbsent(pos.asLong(), key -> new Entry(pos));
            entry.present = true;
            entry.target = (mining.stage() + 1) / 10.0;
        });

        var iterator = entries.values().iterator();
        while (iterator.hasNext()) {
            Entry entry = iterator.next();
            entry.progress += (entry.target - entry.progress) * Math.min(1, delta * 9);
            entry.shown += ((entry.present ? 1 : 0) - entry.shown) * Math.min(1, delta * (entry.present ? 14 : 8));
            if (!entry.present && entry.shown < 0.01) {
                iterator.remove();
                continue;
            }

            DuckRender.lerp(side, MeteorColors.of(startColor), MeteorColors.of(endColor), entry.progress);
            edgeStart.set(MeteorColors.of(startColor).r, MeteorColors.of(startColor).g, MeteorColors.of(startColor).b, Math.min(255, MeteorColors.of(startColor).a * 4));
            edgeEnd.set(MeteorColors.of(endColor).r, MeteorColors.of(endColor).g, MeteorColors.of(endColor).b, Math.min(255, MeteorColors.of(endColor).a * 3));
            DuckRender.lerp(line, edgeStart, edgeEnd, entry.progress);
            double inset = (1 - entry.progress) * 0.5;
            DuckRender.box(event.renderer, new Box(entry.pos).contract(inset), side, line, ShapeMode.Both, entry.shown);
        }
    }
}
