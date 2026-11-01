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

        color("accent", 141, 126, 248, 255);
        color("checkbox", 141, 126, 248, 255);
        color("plus", 124, 206, 154, 255);
        color("minus", 226, 108, 122, 255);
        color("favorite", 244, 198, 96, 255);

        color("text", 196, 196, 206, 255);
        color("text-secondary-text", 150, 150, 162, 255);
        color("text-highlight", 141, 126, 248, 100);
        color("title-text", 237, 237, 243, 255);
        color("logged-in-text", 124, 206, 154, 255);
        color("placeholder", 110, 110, 122, 255);

        states("background", 15, 15, 20, 166, 255, 255, 255, 22, 255, 255, 255, 34);
        color("module-background", 141, 126, 248, 40);
        states("outline", 255, 255, 255, 30, 255, 255, 255, 52, 141, 126, 248, 160);

        color("separator-text", 150, 150, 162, 255);
        color("separator-center", 255, 255, 255, 40);
        color("separator-edges", 255, 255, 255, 14);

        states("scrollbar", 255, 255, 255, 50, 255, 255, 255, 80, 141, 126, 248, 200);
        states("slider-handle", 236, 236, 242, 255, 255, 255, 255, 255, 141, 126, 248, 255);
        color("slider-left", 141, 126, 248, 255);
        color("slider-right", 255, 255, 255, 48);
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
