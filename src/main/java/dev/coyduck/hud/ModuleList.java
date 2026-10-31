package dev.coyduck.hud;

import dev.coyduck.Coyduck;
import meteordevelopment.meteorclient.settings.BoolSetting;
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
    public static final HudElementInfo<ModuleList> INFO = new HudElementInfo<>(CoyHud.GROUP, "coy-modules", "Enabled modules, sliding in and out.", ModuleList::new);

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> onlyCoyduck = sgGeneral.add(new BoolSetting.Builder()
        .name("only-coyduck")
        .description("Lists only Coyduck modules.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> info = sgGeneral.add(new BoolSetting.Builder()
        .name("info")
        .description("Shows each module's extra info next to its name.")
        .defaultValue(true)
        .build()
    );

    private final Map<Module, Double> shown = new HashMap<>();
    private long last;

    public ModuleList() {
        super(INFO);
    }

    @Override
    public void render(HudRenderer r) {
        long now = Util.getMeasuringTimeMs();
        double delta = last == 0 ? 0 : Math.min((now - last) / 1000.0, 0.1);
        last = now;

        for (Module module : Modules.get().getAll()) {
            boolean wanted = module.isActive() && !Config.get().hiddenModules.get().contains(module)
                && (!onlyCoyduck.get() || module.category == Coyduck.CATEGORY);
            double value = shown.getOrDefault(module, 0.0);
            value = CoyHud.approach(value, wanted ? 1 : 0, delta, 12);
            if (!wanted && value < 0.01) shown.remove(module);
            else shown.put(module, value);
        }

        List<Module> list = new ArrayList<>(shown.keySet());
        list.sort((a, b) -> Double.compare(rowWidth(r, b), rowWidth(r, a)));

        double rowHeight = r.textHeight() + 5;
        double widest = 0;
        double total = 0;
        for (Module module : list) {
            widest = Math.max(widest, rowWidth(r, module));
            total += rowHeight * shown.get(module);
        }
        if (list.isEmpty() && isInEditor()) {
            widest = r.textWidth("Modules") + 14;
            total = rowHeight;
        }
        setSize(Math.max(widest, 40), Math.max(total, rowHeight));

        boolean right = getX() + getWidth() / 2.0 > Utils.getWindowWidth() / 2.0;
        double rowY = y;
        if (list.isEmpty() && isInEditor()) {
            CoyHud.panel(r, x, y, getWidth(), rowHeight, 1);
            r.text("Modules", x + 7, y + 3, CoyHud.color(CoyHud.SUBTLE), false);
            return;
        }

        for (Module module : list) {
            double t = shown.get(module);
            double w = rowWidth(r, module);
            double h = rowHeight * t;
            double slide = (1 - t) * (w * 0.6);
            double rowX = right ? x + getWidth() - w + slide : x - slide;

            r.quad(rowX, rowY, w, h, CoyHud.color(CoyHud.BACKGROUND, t));
            double barX = right ? rowX + w - 2 : rowX;
            r.quad(barX, rowY, 2, h, CoyHud.color(CoyHud.accent(), t));

            if (t > 0.5) {
                double tx = rowX + (right ? 5 : 7);
                double ty = rowY + (h - r.textHeight()) / 2;
                r.text(module.title, tx, ty, CoyHud.color(CoyHud.TEXT, t), false);
                String extra = info.get() ? module.getInfoString() : null;
                if (extra != null && !extra.isEmpty()) {
                    r.text(extra, tx + r.textWidth(module.title) + 4, ty, CoyHud.color(CoyHud.SUBTLE, t), false);
                }
            }
            rowY += h;
        }
    }

    private double rowWidth(HudRenderer r, Module module) {
        double w = r.textWidth(module.title) + 12;
        String extra = info.get() ? module.getInfoString() : null;
        if (extra != null && !extra.isEmpty()) w += r.textWidth(extra) + 4;
        return w;
    }
}
