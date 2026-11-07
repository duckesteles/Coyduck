package dev.coyduck.hud;

import dev.coyduck.modules.ClickGui;
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

    public static final int BACKGROUND = 0xA60F0F14;
    public static final int TEXT = 0xFFEDEDF3;
    public static final int SUBTLE = 0xFF9696A2;
    public static final int MUTED = 0xFF6E6E7A;
    public static final int TRACK = 0x30FFFFFF;
    public static final int GOOD = 0xFF7CCE9A;
    public static final int WARN = 0xFFF4C660;
    public static final int BAD = 0xFFE26C7A;

    private CoyHud() {
    }

    public static final class Background {
        private final Setting<Boolean> enabled;
        private final Setting<Integer> opacity;

        public Background(Settings settings) {
            SettingGroup group = settings.createGroup("Background");
            enabled = group.add(new BoolSetting.Builder()
                .name("background")
                .description("Draws the dark box behind this element.")
                .defaultValue(true)
                .build()
            );
            opacity = group.add(new IntSetting.Builder()
                .name("background-opacity")
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
        round(r, x, y, w, h, 3, color(BACKGROUND | 0xFF000000, alpha));
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

    public static int blend(int from, int to, double t) {
        t = MathHelper.clamp(t, 0, 1);
        int a = (int) Math.round(((from >>> 24) & 0xFF) + (((to >>> 24) & 0xFF) - ((from >>> 24) & 0xFF)) * t);
        int r = (int) Math.round(((from >> 16) & 0xFF) + (((to >> 16) & 0xFF) - ((from >> 16) & 0xFF)) * t);
        int g = (int) Math.round(((from >> 8) & 0xFF) + (((to >> 8) & 0xFF) - ((from >> 8) & 0xFF)) * t);
        int b = (int) Math.round((from & 0xFF) + ((to & 0xFF) - (from & 0xFF)) * t);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    public static int health(double fraction) {
        return fraction > 0.5 ? blend(WARN, GOOD, (fraction - 0.5) * 2) : blend(BAD, WARN, fraction * 2);
    }

    public static double approach(double value, double target, double delta, double speed) {
        return value + (target - value) * Math.min(1, delta * speed);
    }
}
