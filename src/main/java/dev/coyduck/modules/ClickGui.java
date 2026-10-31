package dev.coyduck.modules;

import dev.coyduck.Coyduck;
import dev.coyduck.gui.CoyGuiScreen;
import meteordevelopment.meteorclient.events.game.OpenScreenEvent;
import meteordevelopment.meteorclient.gui.screens.ModulesScreen;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;

public class ClickGui extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    public final Setting<SettingColor> accent = sgGeneral.add(new ColorSetting.Builder()
        .name("accent")
        .description("Color of enabled modules, sliders and checkboxes.")
        .defaultValue(new SettingColor(141, 126, 248, 255))
        .build()
    );

    public final Setting<Boolean> dim = sgGeneral.add(new BoolSetting.Builder()
        .name("dim")
        .description("Darkens the game behind the menu.")
        .defaultValue(true)
        .build()
    );

    public final Setting<String> layout = sgGeneral.add(new StringSetting.Builder()
        .name("layout")
        .description("Saved panel positions.")
        .defaultValue("")
        .visible(() -> false)
        .build()
    );

    public ClickGui() {
        super(Coyduck.CATEGORY, "click-gui+", "Replaces the module menu with a cleaner column layout.");
    }

    @EventHandler
    private void onOpenScreen(OpenScreenEvent event) {
        if (!(event.screen instanceof ModulesScreen)) return;
        event.cancel();
        mc.execute(() -> mc.setScreen(new CoyGuiScreen(this)));
    }
}
