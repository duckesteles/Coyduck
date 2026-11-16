package dev.coyduck.modules;

import dev.coyduck.events.RenderWorldEvent;
import dev.coyduck.feature.ColorSetting;
import dev.coyduck.feature.Feature;
import dev.coyduck.feature.Rgba;
import dev.coyduck.feature.SettingGroup;
import dev.coyduck.managers.MiningTracker;
import dev.coyduck.render.Argb;
import dev.coyduck.render.DuckRender;
import dev.coyduck.render.Shape;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.util.Util;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

public class BreakEsp extends Feature {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final ColorSetting startColor = sgGeneral.add(new ColorSetting.Builder()
        .name("start-color")
        .description("Color when the block has just started breaking.")
        .defaultValue(new Rgba(255, 255, 255, 40))
        .build()
    );

    private final ColorSetting endColor = sgGeneral.add(new ColorSetting.Builder()
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
    private void onRender(RenderWorldEvent event) {
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

        int start = startColor.argb();
        int end = endColor.argb();
        int edgeStart = Argb.alpha(start, Argb.a(start) * 4);
        int edgeEnd = Argb.alpha(end, Argb.a(end) * 3);
        var iterator = entries.values().iterator();
        while (iterator.hasNext()) {
            Entry entry = iterator.next();
            entry.progress += (entry.target - entry.progress) * Math.min(1, delta * 9);
            entry.shown += ((entry.present ? 1 : 0) - entry.shown) * Math.min(1, delta * (entry.present ? 14 : 8));
            if (!entry.present && entry.shown < 0.01) {
                iterator.remove();
                continue;
            }

            int side = Argb.lerp(start, end, entry.progress);
            int line = Argb.lerp(edgeStart, edgeEnd, entry.progress);
            double inset = (1 - entry.progress) * 0.5;
            DuckRender.box(event.canvas, new Box(entry.pos).contract(inset), side, line, Shape.Both, entry.shown);
        }
    }
}
