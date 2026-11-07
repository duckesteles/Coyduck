package dev.coyduck.hud;

import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.hud.HudElement;
import meteordevelopment.meteorclient.systems.hud.HudElementInfo;
import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import net.fabricmc.loader.api.FabricLoader;

public class Watermark extends HudElement {
    public static final HudElementInfo<Watermark> INFO = new HudElementInfo<>(CoyHud.GROUP, "coy-watermark", "Coyduck name and version.", Watermark::new);

    private static final String VERSION = FabricLoader.getInstance().getModContainer("coyduck")
        .map(container -> container.getMetadata().getVersion().getFriendlyString()).orElse("");

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final CoyHud.Background background = new CoyHud.Background(settings);

    private final Setting<Boolean> version = sgGeneral.add(new BoolSetting.Builder()
        .name("version")
        .description("Shows the version next to the name.")
        .defaultValue(true)
        .build()
    );

    public Watermark() {
        super(INFO);
    }

    @Override
    public void render(HudRenderer r) {
        double pad = 5;
        String name = "Coyduck";
        String tail = version.get() && !VERSION.isEmpty() ? " " + VERSION : "";
        double w = r.textWidth(name) + r.textWidth(tail) + pad * 2 + 3;
        double h = r.textHeight() + 8;
        setSize(w, h);
        int x = box.getRenderX();
        int y = box.getRenderY();

        CoyHud.panel(r, x, y, w, h, background.alpha());
        CoyHud.round(r, x, y + 3, 2, h - 6, 1, CoyHud.color(CoyHud.accent()));
        double tx = x + pad + 3;
        double ty = y + (h - r.textHeight()) / 2;
        r.text(name, tx, ty, CoyHud.color(CoyHud.TEXT), false);
        tx += r.textWidth(name);
        if (!tail.isEmpty()) r.text(tail, tx, ty, CoyHud.color(CoyHud.SUBTLE), false);
    }
}
