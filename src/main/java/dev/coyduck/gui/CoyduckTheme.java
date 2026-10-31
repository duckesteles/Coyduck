package dev.coyduck.gui;

import dev.coyduck.mixin.GuiThemeAccessor;
import meteordevelopment.meteorclient.gui.themes.meteor.MeteorGuiTheme;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;

public class CoyduckTheme extends MeteorGuiTheme {
    public CoyduckTheme() {
        ((GuiThemeAccessor) this).coyduck$setName("Coyduck");

        color("accent", 139, 124, 246, 255);
        color("checkbox", 139, 124, 246, 255);
        color("plus", 120, 205, 150, 255);
        color("minus", 226, 104, 118, 255);
        color("favorite", 244, 196, 92, 255);

        color("text", 232, 232, 240, 255);
        color("text-secondary-text", 146, 146, 160, 255);
        color("text-highlight", 139, 124, 246, 110);
        color("title-text", 244, 244, 250, 255);
        color("logged-in-text", 120, 205, 150, 255);
        color("placeholder", 108, 108, 122, 255);

        states("background", 17, 17, 23, 228, 24, 24, 32, 236, 30, 30, 40, 240);
        color("module-background", 139, 124, 246, 46);

        states("outline", 30, 30, 40, 255, 48, 48, 62, 255, 66, 66, 84, 255);

        color("separator-text", 146, 146, 160, 255);
        color("separator-center", 58, 58, 72, 255);
        color("separator-edges", 36, 36, 46, 160);

        states("scrollbar", 56, 56, 70, 200, 76, 76, 94, 220, 96, 96, 118, 235);
        states("slider-handle", 139, 124, 246, 255, 160, 147, 255, 255, 178, 166, 255, 255);
        color("slider-left", 139, 124, 246, 255);
        color("slider-right", 40, 40, 52, 255);
    }

    private void states(String name, int r, int g, int b, int a, int hr, int hg, int hb, int ha, int pr, int pg, int pb, int pa) {
        color(name, r, g, b, a);
        color("hovered-" + name, hr, hg, hb, ha);
        color("pressed-" + name, pr, pg, pb, pa);
    }

    private void color(String name, int r, int g, int b, int a) {
        Setting<?> setting = settings.get(name);
        if (setting instanceof ColorSetting color) color.set(new SettingColor(r, g, b, a));
    }
}
