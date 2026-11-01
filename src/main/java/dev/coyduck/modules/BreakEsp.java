package dev.coyduck.modules;

import dev.coyduck.Coyduck;
import dev.coyduck.managers.MiningTracker;
import dev.coyduck.utils.DuckRender;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.util.Util;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

public class BreakEsp extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> self = sgGeneral.add(new BoolSetting.Builder()
        .name("self")
        .description("Also shows blocks you are mining.")
        .defaultValue(false)
        .build()
    );

    private final Setting<SettingColor> startColor = sgGeneral.add(new ColorSetting.Builder()
        .name("start-color")
        .description("Color when the block has just started breaking.")
        .defaultValue(new SettingColor(255, 255, 255, 40))
        .build()
    );

    private final Setting<SettingColor> endColor = sgGeneral.add(new ColorSetting.Builder()
        .name("end-color")
        .description("Color when the block is about to break.")
        .defaultValue(new SettingColor(230, 60, 90, 80))
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
        super(Coyduck.CATEGORY, "break-esp+", "Shows blocks being mined with a box that grows smoothly with the progress.");
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
        int own = mc.player.getId();
        MiningTracker.get().forEach((pos, mining) -> {
            if (!self.get() && mining.breaker() == own) return;
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

            DuckRender.lerp(side, startColor.get(), endColor.get(), entry.progress);
            edgeStart.set(startColor.get().r, startColor.get().g, startColor.get().b, Math.min(255, startColor.get().a * 4));
            edgeEnd.set(endColor.get().r, endColor.get().g, endColor.get().b, Math.min(255, endColor.get().a * 3));
            DuckRender.lerp(line, edgeStart, edgeEnd, entry.progress);
            double inset = (1 - entry.progress) * 0.5;
            DuckRender.box(event.renderer, new Box(entry.pos).contract(inset), side, line, ShapeMode.Both, entry.shown);
        }
    }
}
