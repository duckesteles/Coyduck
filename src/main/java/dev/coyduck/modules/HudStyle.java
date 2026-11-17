package dev.coyduck.modules;

import dev.coyduck.core.Core;
import dev.coyduck.feature.ColorSetting;
import dev.coyduck.feature.DoubleSetting;
import dev.coyduck.feature.EnumSetting;
import dev.coyduck.feature.Feature;
import dev.coyduck.feature.Features;
import dev.coyduck.feature.Rgba;
import dev.coyduck.feature.Setting;
import dev.coyduck.feature.SettingGroup;
import dev.coyduck.render.Argb;
import net.minecraft.util.Util;

import java.awt.Color;

public class HudStyle extends Feature {
    public enum Mode { Static, Pulse, Wave, Rainbow }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Mode> mode = sgGeneral.add(new EnumSetting.Builder<Mode>()
        .name("mode")
        .description("How HUD text is coloured.")
        .defaultValue(Mode.Wave)
        .build()
    );

    private final ColorSetting second = sgGeneral.add(new ColorSetting.Builder()
        .name("second-color")
        .description("Second colour for pulse and wave.")
        .defaultValue(new Rgba(105, 86, 143, 255))
        .visible(() -> mode.get() == Mode.Pulse || mode.get() == Mode.Wave)
        .build()
    );

    private final Setting<Double> period = sgGeneral.add(new DoubleSetting.Builder()
        .name("period")
        .description("Seconds for one full colour cycle.")
        .defaultValue(2)
        .range(0.2, 20)
        .sliderRange(0.5, 6)
        .visible(() -> mode.get() != Mode.Static)
        .build()
    );

    private final Setting<Double> spread = sgGeneral.add(new DoubleSetting.Builder()
        .name("spread")
        .description("How much the colour shifts per row down the screen.")
        .defaultValue(5)
        .range(0, 30)
        .sliderRange(0, 15)
        .visible(() -> mode.get() == Mode.Wave || mode.get() == Mode.Rainbow)
        .build()
    );

    public HudStyle() {
        super("hud-style", "Colours for Coyduck HUD text.");
    }

    public static int color(double y) {
        int accent = Core.host().accent();
        HudStyle style = Features.get(HudStyle.class);
        return style == null || !style.isActive() ? accent : style.at(y, accent);
    }

    private int at(double y, int accent) {
        double cycle = Util.getMeasuringTimeMs() / (period.get() * 1000.0);
        double shift = y * spread.get() / 1000.0;
        return switch (mode.get()) {
            case Static -> accent;
            case Pulse -> Argb.lerp(accent, second.argb(), wave(cycle));
            case Wave -> Argb.lerp(accent, second.argb(), wave(cycle - shift));
            case Rainbow -> rainbow(accent, cycle - shift);
        };
    }

    private static double wave(double cycle) {
        return (Math.sin(cycle * Math.PI * 2) + 1) / 2;
    }

    private static int rainbow(int accent, double cycle) {
        float[] hsb = Color.RGBtoHSB(Argb.r(accent), Argb.g(accent), Argb.b(accent), null);
        float hue = (float) (cycle - Math.floor(cycle));
        return Color.HSBtoRGB(hue, Math.max(hsb[1], 0.5f), Math.max(hsb[2], 0.7f)) & 0x00FFFFFF | Argb.a(accent) << 24;
    }
}
