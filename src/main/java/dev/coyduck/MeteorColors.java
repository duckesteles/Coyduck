package dev.coyduck;

import dev.coyduck.feature.Rgba;
import dev.coyduck.feature.Setting;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;

public final class MeteorColors {
    private MeteorColors() {}

    public static SettingColor of(Setting<Rgba> setting) {
        if (setting.handle() instanceof SettingColor color) return color;
        Rgba value = setting.get();
        return new SettingColor(value.r(), value.g(), value.b(), value.a(), value.rainbow());
    }
}
