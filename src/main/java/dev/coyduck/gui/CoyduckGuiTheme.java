package dev.coyduck.gui;

import dev.coyduck.mixin.GuiThemeAccessor;
import meteordevelopment.meteorclient.gui.themes.meteor.MeteorGuiTheme;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;

import java.util.Map;

public class CoyduckGuiTheme extends MeteorGuiTheme {
    public static final String NAME = "Coyduck";

    private static final Map<String, SettingColor> PALETTE = Map.ofEntries(
        Map.entry("accent", new SettingColor(185, 74, 230)),
        Map.entry("checkbox", new SettingColor(185, 74, 230)),
        Map.entry("plus", new SettingColor(255, 152, 0)),
        Map.entry("minus", new SettingColor(255, 82, 82)),
        Map.entry("favorite", new SettingColor(251, 192, 45)),
        Map.entry("text", new SettingColor(255, 255, 255)),
        Map.entry("text-secondary-text", new SettingColor(175, 155, 200)),
        Map.entry("text-highlight", new SettingColor(185, 74, 230, 100)),
        Map.entry("title-text", new SettingColor(255, 255, 255)),
        Map.entry("logged-in-text", new SettingColor(251, 192, 45)),
        Map.entry("background", new SettingColor(22, 14, 32, 215)),
        Map.entry("hovered-background", new SettingColor(34, 22, 50, 215)),
        Map.entry("pressed-background", new SettingColor(46, 30, 68, 215)),
        Map.entry("module-background", new SettingColor(110, 45, 200, 110)),
        Map.entry("outline", new SettingColor(60, 38, 88)),
        Map.entry("hovered-outline", new SettingColor(110, 45, 200)),
        Map.entry("pressed-outline", new SettingColor(185, 74, 230)),
        Map.entry("separator-text", new SettingColor(255, 255, 255)),
        Map.entry("separator-center", new SettingColor(185, 74, 230)),
        Map.entry("separator-edges", new SettingColor(110, 45, 200, 150)),
        Map.entry("scrollbar", new SettingColor(110, 45, 200, 200)),
        Map.entry("hovered-scrollbar", new SettingColor(140, 60, 215, 200)),
        Map.entry("pressed-scrollbar", new SettingColor(185, 74, 230, 200)),
        Map.entry("slider-handle", new SettingColor(185, 74, 230)),
        Map.entry("hovered-slider-handle", new SettingColor(205, 110, 240)),
        Map.entry("pressed-slider-handle", new SettingColor(225, 150, 250)),
        Map.entry("slider-left", new SettingColor(185, 74, 230)),
        Map.entry("slider-right", new SettingColor(60, 38, 88))
    );

    public CoyduckGuiTheme() {
        ((GuiThemeAccessor) this).coyduck$setName(NAME);

        for (SettingGroup group : settings) {
            for (Setting<?> setting : group) {
                SettingColor color = PALETTE.get(setting.name.toLowerCase());
                if (color == null || !(setting.get() instanceof SettingColor)) continue;

                @SuppressWarnings("unchecked")
                Setting<SettingColor> colorSetting = (Setting<SettingColor>) setting;
                colorSetting.getDefaultValue().set(color);
                colorSetting.set(new SettingColor(color));
            }
        }
    }
}
