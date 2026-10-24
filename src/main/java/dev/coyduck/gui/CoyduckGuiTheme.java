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
        Map.entry("accent", new SettingColor(255, 152, 0)),
        Map.entry("checkbox", new SettingColor(255, 152, 0)),
        Map.entry("plus", new SettingColor(251, 192, 45)),
        Map.entry("minus", new SettingColor(255, 82, 82)),
        Map.entry("favorite", new SettingColor(251, 192, 45)),
        Map.entry("text", new SettingColor(255, 255, 255)),
        Map.entry("text-secondary-text", new SettingColor(200, 180, 225)),
        Map.entry("text-highlight", new SettingColor(255, 152, 0, 90)),
        Map.entry("title-text", new SettingColor(251, 192, 45)),
        Map.entry("logged-in-text", new SettingColor(251, 192, 45)),
        Map.entry("background", new SettingColor(30, 14, 50, 225)),
        Map.entry("hovered-background", new SettingColor(48, 22, 78, 225)),
        Map.entry("pressed-background", new SettingColor(66, 30, 106, 225)),
        Map.entry("module-background", new SettingColor(110, 45, 200, 185)),
        Map.entry("outline", new SettingColor(110, 45, 200)),
        Map.entry("hovered-outline", new SettingColor(185, 74, 230)),
        Map.entry("pressed-outline", new SettingColor(255, 152, 0)),
        Map.entry("separator-text", new SettingColor(251, 192, 45)),
        Map.entry("separator-center", new SettingColor(255, 152, 0)),
        Map.entry("separator-edges", new SettingColor(185, 74, 230, 170)),
        Map.entry("scrollbar", new SettingColor(110, 45, 200, 210)),
        Map.entry("hovered-scrollbar", new SettingColor(185, 74, 230, 210)),
        Map.entry("pressed-scrollbar", new SettingColor(255, 152, 0, 210)),
        Map.entry("slider-handle", new SettingColor(251, 192, 45)),
        Map.entry("hovered-slider-handle", new SettingColor(255, 210, 90)),
        Map.entry("pressed-slider-handle", new SettingColor(255, 230, 140)),
        Map.entry("slider-left", new SettingColor(255, 152, 0)),
        Map.entry("slider-right", new SettingColor(70, 35, 110))
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
