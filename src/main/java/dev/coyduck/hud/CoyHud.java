package dev.coyduck.hud;

import dev.coyduck.modules.ClickGui;
import dev.coyduck.render.Palette;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.Settings;
import meteordevelopment.meteorclient.systems.hud.HudGroup;
import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import meteordevelopment.meteorclient.utils.render.color.Color;
import net.minecraft.util.math.MathHelper;

public final class CoyHud {
    public static final HudGroup GROUP = new HudGroup("Coyduck");

    private static final double SHADOW_BELOW = 0.35;

    private CoyHud() {
    }

    public static final class Background {
        private final Setting<Boolean> enabled;
        private final Setting<Integer> opacity;

        public Background(Settings settings) {
            SettingGroup group = settings.createGroup("Background");
            enabled = group.add(new BoolSetting.Builder()
                .name("box")
                .description("Draws a dark box behind this element.")
                .defaultValue(false)
                .build()
            );
            opacity = group.add(new IntSetting.Builder()
                .name("box-opacity")
                .description("How see-through the box is, in percent. 100 is fully dark.")
                .defaultValue(65)
                .range(0, 100)
                .sliderRange(0, 100)
                .visible(enabled::get)
                .build()
            );
        }

        public double alpha() {
            return enabled.get() ? opacity.get() / 100.0 : 0;
        }

        public boolean boxed() {
            return alpha() > 0;
        }

        public boolean shadow() {
            return alpha() < SHADOW_BELOW;
        }
    }

    public static int accent() {
        return ClickGui.accentColor();
    }

    public static Color color(int argb) {
        return new Color((argb >> 16) & 0xFF, (argb >> 8) & 0xFF, argb & 0xFF, (argb >>> 24) & 0xFF);
    }

    public static Color color(int argb, double alpha) {
        int a = (int) Math.round(((argb >>> 24) & 0xFF) * MathHelper.clamp(alpha, 0, 1));
        return new Color((argb >> 16) & 0xFF, (argb >> 8) & 0xFF, argb & 0xFF, a);
    }

    public static void panel(HudRenderer r, double x, double y, double w, double h, double alpha) {
        if (alpha <= 0) return;
        round(r, x, y, w, h, 3, color(Palette.BACKGROUND | 0xFF000000, alpha));
    }

    public static void round(HudRenderer r, double x, double y, double w, double h, int radius, Color c) {
        if (w <= 0 || h <= 0) return;
        int rad = (int) Math.min(radius, Math.floor(Math.min(w, h) / 2));
        if (rad <= 0) {
            r.quad(x, y, w, h, c);
            return;
        }
        r.quad(x, y + rad, w, h - rad * 2, c);
        for (int i = 0; i < rad; i++) {
            double dy = rad - i - 0.5;
            double inset = rad - Math.sqrt(rad * rad - dy * dy);
            r.quad(x + inset, y + i, w - inset * 2, 1, c);
            r.quad(x + inset, y + h - 1 - i, w - inset * 2, 1, c);
        }
    }

    public static double approach(double value, double target, double delta, double speed) {
        return value + (target - value) * Math.min(1, delta * speed);
    }
}
