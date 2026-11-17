package dev.coyduck.hud;

import dev.coyduck.core.Core;
import dev.coyduck.feature.BoolSetting;
import dev.coyduck.feature.IntSetting;
import dev.coyduck.feature.Setting;
import dev.coyduck.feature.SettingGroup;
import dev.coyduck.feature.Settings;
import dev.coyduck.modules.HudStyle;
import dev.coyduck.render.Argb;
import dev.coyduck.render.Palette;
import dev.coyduck.render.Panel;

public final class CoyHud {
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
        return Core.host().accent();
    }

    public static int color(double y) {
        return HudStyle.color(y);
    }

    public static double eased(double t) {
        double rest = 1 - t;
        return 1 - rest * rest * rest * rest;
    }

    public static double toward(double value, boolean up, double delta, double perSecond) {
        return Math.clamp(value + (up ? delta : -delta) * perSecond, 0, 1);
    }

    public static void panel(Panel r, double x, double y, double w, double h, double alpha) {
        if (alpha <= 0) return;
        round(r, x, y, w, h, 3, Argb.fade(Palette.BACKGROUND | 0xFF000000, alpha));
    }

    public static void round(Panel r, double x, double y, double w, double h, int radius, int argb) {
        if (w <= 0 || h <= 0) return;
        int rad = (int) Math.min(radius, Math.floor(Math.min(w, h) / 2));
        if (rad <= 0) {
            r.quad(x, y, w, h, argb);
            return;
        }
        r.quad(x, y + rad, w, h - rad * 2, argb);
        for (int i = 0; i < rad; i++) {
            double dy = rad - i - 0.5;
            double inset = rad - Math.sqrt(rad * rad - dy * dy);
            r.quad(x + inset, y + i, w - inset * 2, 1, argb);
            r.quad(x + inset, y + h - 1 - i, w - inset * 2, 1, argb);
        }
    }

    public static double approach(double value, double target, double delta, double speed) {
        return value + (target - value) * Math.min(1, delta * speed);
    }
}
