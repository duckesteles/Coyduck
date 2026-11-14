package dev.coyduck.hud;

import dev.coyduck.Coyduck;
import dev.coyduck.modules.AutoLogin;
import dev.coyduck.modules.ClickGui;
import dev.coyduck.modules.UpdateChecker;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.ModuleListSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.config.Config;
import meteordevelopment.meteorclient.systems.hud.HudElement;
import meteordevelopment.meteorclient.systems.hud.HudElementInfo;
import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.Utils;
import net.minecraft.util.Util;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ModuleList extends HudElement {
    public static final HudElementInfo<ModuleList> INFO = new HudElementInfo<>(CoyHud.GROUP, "coy-modules", "Enabled modules in a compact list.", ModuleList::new);

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

    private final Setting<List<Module>> hidden = sgGeneral.add(new ModuleListSetting.Builder()
        .name("hidden")
        .description("Modules never listed, for ones that are always on like the menu or the update checker.")
        .defaultValue(ClickGui.class, UpdateChecker.class, AutoLogin.class)
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
        .description("Draws a thin accent line on the screen-edge side of each row.")
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

    private final Map<Module, Double> shown = new HashMap<>();
    private final Map<Module, String> extras = new HashMap<>();
    private final Map<Module, Double> widths = new HashMap<>();
    private long last;
    private double shownWidth = -1;

    public ModuleList() {
        super(INFO);
    }

    @Override
    public void render(HudRenderer r) {
        long now = Util.getMeasuringTimeMs();
        double delta = last == 0 ? 0 : Math.min((now - last) / 1000.0, 0.1);
        last = now;
        double s = scale.get();
        boolean shadow = background.shadow();
        boolean boxed = background.boxed();
        List<Module> hide = hidden.get();

        for (Module module : Modules.get().getAll()) {
            boolean wanted = module.isActive() && !hide.contains(module) && !Config.get().hiddenModules.get().contains(module)
                && (!onlyCoyduck.get() || module.category == Coyduck.CATEGORY);
            double value = shown.getOrDefault(module, 0.0);
            value = slide.get() ? CoyHud.approach(value, wanted ? 1 : 0, delta, 12) : wanted ? 1 : 0;
            if (!wanted && value < 0.01) shown.remove(module);
            else shown.put(module, value);
        }

        List<Module> list = new ArrayList<>(shown.keySet());
        extras.clear();
        widths.clear();
        for (Module module : list) {
            String extra = info.get() ? cut(module.getInfoString()) : null;
            extras.put(module, extra);
            widths.put(module, rowWidth(r, module, extra, s, shadow, boxed));
        }
        if (sort.get() == Sort.Length) list.sort((a, b) -> Double.compare(widths.get(b), widths.get(a)));
        else list.sort((a, b) -> a.title.compareToIgnoreCase(b.title));

        int overflow = Math.max(0, list.size() - maxRows.get());
        if (overflow > 0) list = list.subList(0, maxRows.get());
        String more = overflow > 0 ? "+" + overflow + " more" : null;

        double textHeight = r.textHeight(shadow, s);
        double rowHeight = textHeight + (compact.get() ? 1 : 4) * s + (boxed ? 2 * s : 0);
        double side = side(s, boxed);
        double widest = more == null ? 0 : r.textWidth(more, shadow, s) + side * 2;
        double total = more == null ? 0 : rowHeight;
        for (Module module : list) {
            widest = Math.max(widest, widths.get(module));
            total += rowHeight * shown.get(module);
        }
        if (list.isEmpty() && isInEditor()) {
            widest = r.textWidth("Modules", shadow, s) + side * 2;
            total = rowHeight;
        }
        double target = Math.max(widest, 30 * s);
        shownWidth = shownWidth < 0 || !slide.get() ? target : CoyHud.approach(shownWidth, target, delta, 14);
        if (Math.abs(shownWidth - target) < 0.5) shownWidth = target;
        setSize(Math.max(shownWidth, target), Math.max(total, rowHeight));
        int x = box.getRenderX();
        int y = box.getRenderY();

        boolean right = getX() + getWidth() / 2.0 > Utils.getWindowWidth() / 2.0;
        double alpha = background.alpha();
        if (list.isEmpty() && isInEditor()) {
            CoyHud.panel(r, x, y, getWidth(), rowHeight, alpha);
            r.text("Modules", x + side, y + (rowHeight - textHeight) / 2, CoyHud.color(CoyHud.SUBTLE), shadow, s);
            return;
        }

        double rowY = y;
        for (Module module : list) {
            double t = shown.get(module);
            double w = widths.get(module);
            double h = rowHeight * t;
            double offset = (1 - t) * (w * 0.6);
            double rowX = right ? x + getWidth() - w + offset : x - offset;

            if (boxed) r.quad(rowX, rowY, w, h, CoyHud.color(CoyHud.BACKGROUND | 0xFF000000, t * alpha));
            if (bar.get()) r.quad(right ? rowX + w - BAR : rowX, rowY, BAR, h, CoyHud.color(CoyHud.accent(), t));

            if (t > 0.5) {
                double tx = rowX + side;
                double ty = rowY + (h - textHeight) / 2;
                tx = r.text(module.title, tx, ty, CoyHud.color(CoyHud.accent(), t), shadow, s);
                String extra = extras.get(module);
                if (extra != null) {
                    tx = r.text(" [", tx, ty, CoyHud.color(CoyHud.SUBTLE, t), shadow, s);
                    tx = r.text(extra, tx, ty, CoyHud.color(CoyHud.TEXT, t), shadow, s);
                    r.text("]", tx, ty, CoyHud.color(CoyHud.SUBTLE, t), shadow, s);
                }
            }
            rowY += h;
        }
        if (more != null) {
            double w = r.textWidth(more, shadow, s) + side * 2;
            double rowX = right ? x + getWidth() - w : x;
            if (boxed) r.quad(rowX, rowY, w, rowHeight, CoyHud.color(CoyHud.BACKGROUND | 0xFF000000, alpha));
            r.text(more, rowX + side, rowY + (rowHeight - textHeight) / 2, CoyHud.color(CoyHud.MUTED), shadow, s);
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

    private double rowWidth(HudRenderer r, Module module, String extra, double s, boolean shadow, boolean boxed) {
        double w = r.textWidth(module.title, shadow, s) + side(s, boxed) * 2;
        if (extra != null) w += r.textWidth(" [" + extra + "]", shadow, s);
        return w;
    }
}
