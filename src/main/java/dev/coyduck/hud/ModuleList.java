package dev.coyduck.hud;

import dev.coyduck.core.Core;
import dev.coyduck.core.Host.Listing;
import dev.coyduck.feature.BoolSetting;
import dev.coyduck.feature.DoubleSetting;
import dev.coyduck.feature.EnumSetting;
import dev.coyduck.feature.IntSetting;
import dev.coyduck.feature.ModuleListSetting;
import dev.coyduck.feature.Setting;
import dev.coyduck.feature.SettingGroup;
import dev.coyduck.render.Argb;
import dev.coyduck.render.Palette;
import dev.coyduck.render.Panel;
import dev.coyduck.widget.Widget;
import net.minecraft.util.Util;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ModuleList extends Widget {
    public static final Info INFO = new Info("coy-modules", "Active modules.", ModuleList::new);

    public enum Sort {
        Length,
        Alphabetical
    }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgLayout = settings.createGroup("Layout");

    private final Setting<Boolean> onlyCoyduck = sgGeneral.add(new BoolSetting.Builder()
        .name("only-coyduck")
        .description("Lists only Coyduck modules.")
        .defaultValue(false)
        .build()
    );

    private final Setting<List<String>> hidden = sgGeneral.add(new ModuleListSetting.Builder()
        .name("hidden")
        .description("Modules never listed.")
        .defaultValue("click-gui+", "update-checker", "auto-login")
        .build()
    );

    private final Setting<Boolean> info = sgGeneral.add(new BoolSetting.Builder()
        .name("info")
        .description("Shows each module's extra info next to its name.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> infoLength = sgGeneral.add(new IntSetting.Builder()
        .name("info-length")
        .description("Longest info text in characters before it gets cut short.")
        .defaultValue(12)
        .range(1, 64)
        .sliderRange(4, 32)
        .visible(info::get)
        .build()
    );

    private final Setting<Sort> sort = sgLayout.add(new EnumSetting.Builder<Sort>()
        .name("sort")
        .description("Longest first, or by name.")
        .defaultValue(Sort.Length)
        .build()
    );

    private final Setting<Double> scale = sgLayout.add(new DoubleSetting.Builder()
        .name("scale")
        .description("Text size.")
        .defaultValue(1)
        .range(0.5, 2)
        .sliderRange(0.5, 1.5)
        .build()
    );

    private final Setting<Boolean> compact = sgLayout.add(new BoolSetting.Builder()
        .name("compact")
        .description("Packs the rows tighter.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> maxRows = sgLayout.add(new IntSetting.Builder()
        .name("max-rows")
        .description("Most modules listed. The rest are shown as a count.")
        .defaultValue(14)
        .range(1, 100)
        .sliderRange(4, 40)
        .build()
    );

    private final Setting<Boolean> bar = sgLayout.add(new BoolSetting.Builder()
        .name("bar")
        .description("Draws an accent line on each row's outer edge.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> slide = sgLayout.add(new BoolSetting.Builder()
        .name("slide")
        .description("Slides modules in and out instead of showing them at once.")
        .defaultValue(true)
        .build()
    );

    private final CoyHud.Background background = new CoyHud.Background(settings);

    private static final double BAR = 2;

    private final Map<Listing, Double> shown = new HashMap<>();
    private final Map<Listing, String> extras = new HashMap<>();
    private final Map<Listing, Double> widths = new HashMap<>();
    private long last;
    private double shownWidth = -1;

    public ModuleList() {
        super(INFO);
    }

    @Override
    public void render(Panel r) {
        long now = Util.getMeasuringTimeMs();
        double delta = last == 0 ? 0 : Math.min((now - last) / 1000.0, 0.1);
        last = now;
        double s = scale.get();
        boolean shadow = background.shadow();
        boolean boxed = background.boxed();
        List<String> hide = hidden.get();

        for (Listing module : Core.host().modules()) {
            boolean wanted = module.active() && !hide.contains(module.name()) && !module.hidden() && (!onlyCoyduck.get() || module.ours());
            double value = shown.getOrDefault(module, 0.0);
            value = slide.get() ? CoyHud.approach(value, wanted ? 1 : 0, delta, 12) : wanted ? 1 : 0;
            if (!wanted && value < 0.01) shown.remove(module);
            else shown.put(module, value);
        }

        List<Listing> list = new ArrayList<>(shown.keySet());
        extras.clear();
        widths.clear();
        for (Listing module : list) {
            String extra = info.get() ? cut(module.info()) : null;
            extras.put(module, extra);
            widths.put(module, rowWidth(r, module, extra, s, shadow, boxed));
        }
        if (sort.get() == Sort.Length) list.sort((a, b) -> Double.compare(widths.get(b), widths.get(a)));
        else list.sort((a, b) -> a.title().compareToIgnoreCase(b.title()));

        int overflow = Math.max(0, list.size() - maxRows.get());
        if (overflow > 0) list = list.subList(0, maxRows.get());
        String more = overflow > 0 ? "+" + overflow + " more" : null;

        double textHeight = r.textHeight(shadow, s);
        double rowHeight = textHeight + (compact.get() ? 1 : 4) * s + (boxed ? 2 * s : 0);
        double side = side(s, boxed);
        double widest = more == null ? 0 : r.textWidth(more, shadow, s) + side * 2;
        double total = more == null ? 0 : rowHeight;
        for (Listing module : list) {
            widest = Math.max(widest, widths.get(module));
            total += rowHeight * shown.get(module);
        }
        if (list.isEmpty() && inEditor()) {
            widest = r.textWidth("Modules", shadow, s) + side * 2;
            total = rowHeight;
        }
        double target = Math.max(widest, 30 * s);
        shownWidth = shownWidth < 0 || !slide.get() ? target : CoyHud.approach(shownWidth, target, delta, 14);
        if (Math.abs(shownWidth - target) < 0.5) shownWidth = target;
        size(Math.max(shownWidth, target), Math.max(total, rowHeight));
        int x = x();
        int y = y();

        boolean right = rightSide();
        double alpha = background.alpha();
        if (list.isEmpty() && inEditor()) {
            CoyHud.panel(r, x, y, width(), rowHeight, alpha);
            r.text("Modules", x + side, y + (rowHeight - textHeight) / 2, Palette.SUBTLE, shadow, s);
            return;
        }

        double rowY = y;
        for (Listing module : list) {
            double t = shown.get(module);
            double w = widths.get(module);
            double h = rowHeight * t;
            double offset = (1 - t) * (w * 0.6);
            double rowX = right ? x + width() - w + offset : x - offset;

            if (boxed) r.quad(rowX, rowY, w, h, Argb.fade(Palette.BACKGROUND | 0xFF000000, t * alpha));
            if (bar.get()) r.quad(right ? rowX + w - BAR : rowX, rowY, BAR, h, Argb.fade(CoyHud.accent(), t));

            if (t > 0.5) {
                double tx = rowX + side;
                double ty = rowY + (h - textHeight) / 2;
                tx = r.text(module.title(), tx, ty, Argb.fade(CoyHud.accent(), t), shadow, s);
                String extra = extras.get(module);
                if (extra != null) {
                    tx = r.text(" [", tx, ty, Argb.fade(Palette.SUBTLE, t), shadow, s);
                    tx = r.text(extra, tx, ty, Argb.fade(Palette.TEXT, t), shadow, s);
                    r.text("]", tx, ty, Argb.fade(Palette.SUBTLE, t), shadow, s);
                }
            }
            rowY += h;
        }
        if (more != null) {
            double w = r.textWidth(more, shadow, s) + side * 2;
            double rowX = right ? x + width() - w : x;
            if (boxed) r.quad(rowX, rowY, w, rowHeight, Argb.fade(Palette.BACKGROUND | 0xFF000000, alpha));
            r.text(more, rowX + side, rowY + (rowHeight - textHeight) / 2, Palette.MUTED, shadow, s);
        }
    }

    private String cut(String extra) {
        if (extra == null || extra.isBlank()) return null;
        int max = infoLength.get();
        return extra.length() <= max ? extra : extra.substring(0, Math.max(1, max - 1)).stripTrailing() + "..";
    }

    private double side(double s, boolean boxed) {
        return (boxed ? 4 * s : 0) + (bar.get() ? BAR + 2 * s : 0);
    }

    private double rowWidth(Panel r, Listing module, String extra, double s, boolean shadow, boolean boxed) {
        double w = r.textWidth(module.title(), shadow, s) + side(s, boxed) * 2;
        if (extra != null) w += r.textWidth(" [" + extra + "]", shadow, s);
        return w;
    }
}
