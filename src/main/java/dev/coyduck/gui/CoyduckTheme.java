package dev.coyduck.gui;

import dev.coyduck.mixin.GuiThemeAccessor;
import meteordevelopment.meteorclient.gui.renderer.packer.GuiTexture;
import meteordevelopment.meteorclient.gui.themes.meteor.MeteorGuiTheme;
import meteordevelopment.meteorclient.gui.widgets.WHorizontalSeparator;
import meteordevelopment.meteorclient.gui.widgets.WTopBar;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.gui.widgets.containers.WSection;
import meteordevelopment.meteorclient.gui.widgets.containers.WWindow;
import meteordevelopment.meteorclient.gui.widgets.input.WSlider;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.gui.widgets.pressable.WCheckbox;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;

public class CoyduckTheme extends MeteorGuiTheme {
    public static final String NAME = "Coyduck";

    public CoyduckTheme() {
        ((GuiThemeAccessor) this).coyduck$setName(NAME);

        color("accent", 123, 98, 170, 255);
        color("checkbox", 123, 98, 170, 255);
        color("plus", 124, 206, 154, 255);
        color("minus", 226, 108, 122, 255);
        color("favorite", 244, 198, 96, 255);

        color("text", 255, 255, 255, 255);
        color("text-secondary-text", 180, 176, 190, 255);
        color("text-highlight", 123, 98, 170, 120);
        color("title-text", 255, 255, 255, 255);
        color("logged-in-text", 124, 206, 154, 255);
        color("placeholder", 130, 126, 140, 255);

        states("background", 42, 37, 51, 180, 53, 47, 64, 200, 89, 71, 122, 220);
        color("module-background", 89, 71, 122, 220);
        states("outline", 12, 10, 16, 255, 12, 10, 16, 255, 123, 98, 170, 255);

        color("separator-text", 180, 176, 190, 255);
        color("separator-center", 255, 255, 255, 64);
        color("separator-edges", 255, 255, 255, 24);

        states("scrollbar", 42, 37, 51, 200, 53, 47, 64, 220, 123, 98, 170, 255);
        states("slider-handle", 255, 255, 255, 255, 255, 255, 255, 255, 123, 98, 170, 255);
        color("slider-left", 89, 71, 122, 220);
        color("slider-right", 42, 37, 51, 180);
    }

    @Override
    public WWindow window(WWidget icon, String title) {
        return w(new CoyWidgets.Window(icon, title));
    }

    @Override
    public WTopBar topBar() {
        return w(new CoyWidgets.TopBar());
    }

    @Override
    public WCheckbox checkbox(boolean checked) {
        return w(new CoyWidgets.Checkbox(checked));
    }

    @Override
    public WSlider slider(double value, double min, double max) {
        return w(new CoyWidgets.Slider(value, min, max));
    }

    @Override
    public WSection section(String title, boolean expanded, WWidget headerWidget) {
        return w(new CoyWidgets.Section(title, expanded, headerWidget));
    }

    @Override
    protected WButton button(String text, GuiTexture texture) {
        return w(new CoyWidgets.Button(text, texture));
    }

    @Override
    public WWidget module(Module module, String title) {
        return w(new CoyWidgets.ModuleRow(module, title));
    }

    @Override
    public WHorizontalSeparator horizontalSeparator(String text) {
        return w(new CoyWidgets.Separator(text));
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
