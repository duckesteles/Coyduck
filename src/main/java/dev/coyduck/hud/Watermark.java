package dev.coyduck.hud;

import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.systems.hud.HudElement;
import meteordevelopment.meteorclient.systems.hud.HudElementInfo;
import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import meteordevelopment.meteorclient.utils.render.color.Color;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.util.Identifier;

public class Watermark extends HudElement {
    public static final HudElementInfo<Watermark> INFO = new HudElementInfo<>(CoyHud.GROUP, "coy-watermark", "Coyduck name and mascot.", Watermark::new);

    private static final Identifier MASCOT = Identifier.of("coyduck", "textures/mascot.png");
    private static final String VERSION = FabricLoader.getInstance().getModContainer("coyduck")
        .map(container -> container.getMetadata().getVersion().getFriendlyString()).orElse("");
    private static final double LOGO_RATIO = 1.5;

    public enum Logo {
        Left,
        Right,
        Off
    }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<String> text = sgGeneral.add(new StringSetting.Builder()
        .name("text")
        .description("The name shown.")
        .defaultValue("Coyduck")
        .build()
    );

    private final Setting<Boolean> version = sgGeneral.add(new BoolSetting.Builder()
        .name("version")
        .description("Shows the version after the name.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> whiteVersion = sgGeneral.add(new BoolSetting.Builder()
        .name("white-version")
        .description("Draws the version in white. Off draws it grey.")
        .defaultValue(true)
        .visible(version::get)
        .build()
    );

    private final Setting<Logo> logo = sgGeneral.add(new EnumSetting.Builder<Logo>()
        .name("mascot")
        .description("Which side of the text the Coyduck mascot sits on.")
        .defaultValue(Logo.Left)
        .build()
    );

    private final Setting<Double> scale = sgGeneral.add(new DoubleSetting.Builder()
        .name("scale")
        .description("Size of the watermark. Whole numbers stay the sharpest.")
        .defaultValue(1)
        .range(0.5, 4)
        .sliderRange(0.5, 3)
        .build()
    );

    private final CoyHud.Background background = new CoyHud.Background(settings);

    public Watermark() {
        super(INFO);
    }

    @Override
    public void render(HudRenderer r) {
        double s = scale.get();
        boolean shadow = background.shadow();
        boolean boxed = background.boxed();
        String name = text.get().trim().isEmpty() ? "Coyduck" : text.get().trim();
        String tail = version.get() && !VERSION.isEmpty() ? " v" + VERSION : "";

        double textHeight = r.textHeight(shadow, s);
        double nameWidth = r.textWidth(name, shadow, s);
        double textWidth = nameWidth + (tail.isEmpty() ? 0 : r.textWidth(tail, shadow, s));
        double logoSize = logo.get() == Logo.Off ? 0 : Math.round(textHeight * LOGO_RATIO);
        double gap = logoSize > 0 ? Math.round(3 * s) : 0;
        double pad = boxed ? Math.round(4 * s) : 0;
        double contentHeight = Math.max(textHeight, logoSize);
        double w = pad * 2 + logoSize + gap + textWidth;
        double h = pad * 2 + contentHeight;
        setSize(w, h);

        int x = box.getRenderX();
        int y = box.getRenderY();
        CoyHud.panel(r, x, y, w, h, background.alpha());

        double logoX = logo.get() == Logo.Right ? x + pad + textWidth + gap : x + pad;
        double textX = logo.get() == Logo.Left ? x + pad + logoSize + gap : x + pad;
        if (logoSize > 0) r.texture(MASCOT, logoX, y + pad + (contentHeight - logoSize) / 2, logoSize, logoSize, Color.WHITE);

        double textY = y + pad + (contentHeight - textHeight) / 2;
        r.text(name, textX, textY, CoyHud.color(CoyHud.accent()), shadow, s);
        if (!tail.isEmpty()) r.text(tail, textX + nameWidth, textY, CoyHud.color(whiteVersion.get() ? CoyHud.TEXT : CoyHud.SUBTLE), shadow, s);
    }
}
