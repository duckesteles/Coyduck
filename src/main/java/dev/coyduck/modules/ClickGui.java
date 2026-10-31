package dev.coyduck.modules;

import dev.coyduck.Coyduck;
import dev.coyduck.gui.CoyGuiScreen;
import dev.coyduck.gui.CoyduckTheme;
import meteordevelopment.meteorclient.events.game.OpenScreenEvent;
import meteordevelopment.meteorclient.gui.GuiThemes;
import meteordevelopment.meteorclient.gui.screens.ModulesScreen;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.systems.config.Config;
import meteordevelopment.meteorclient.systems.hud.Hud;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;

public class ClickGui extends Module {
    private static final int DEFAULT_ACCENT = 0xFF8D7EF8;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    public final Setting<SettingColor> accent = sgGeneral.add(new ColorSetting.Builder()
        .name("accent")
        .description("Color of enabled modules, sliders, checkboxes and the Coyduck HUD.")
        .defaultValue(new SettingColor(141, 126, 248, 255))
        .build()
    );

    public final Setting<Boolean> dim = sgGeneral.add(new BoolSetting.Builder()
        .name("dim")
        .description("Darkens the game behind the menu.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> matchScreens = sgGeneral.add(new BoolSetting.Builder()
        .name("match-other-screens")
        .description("Gives settings, config, HUD and the other tabs the same look while this is on.")
        .defaultValue(true)
        .onChanged(value -> {
            if (isActive()) apply();
        })
        .build()
    );

    private final Setting<Boolean> minecraftFont = sgGeneral.add(new BoolSetting.Builder()
        .name("minecraft-font")
        .description("Uses the Minecraft font in every menu while this is on. The HUD keeps its own font.")
        .defaultValue(true)
        .onChanged(value -> {
            if (isActive()) apply();
        })
        .build()
    );

    public final Setting<String> layout = sgGeneral.add(new StringSetting.Builder()
        .name("layout")
        .description("Saved panel positions.")
        .defaultValue("")
        .visible(() -> false)
        .build()
    );

    private final Setting<String> previousTheme = sgGeneral.add(new StringSetting.Builder()
        .name("previous-theme")
        .description("Theme to go back to when this is turned off.")
        .defaultValue("")
        .visible(() -> false)
        .build()
    );

    private final Setting<String> previousFont = sgGeneral.add(new StringSetting.Builder()
        .name("previous-font")
        .description("Custom font state to go back to when this is turned off.")
        .defaultValue("")
        .visible(() -> false)
        .build()
    );

    private final Setting<String> previousHudFont = sgGeneral.add(new StringSetting.Builder()
        .name("previous-hud-font")
        .description("HUD custom font state to go back to when this is turned off.")
        .defaultValue("")
        .visible(() -> false)
        .build()
    );

    public ClickGui() {
        super(Coyduck.CATEGORY, "click-gui+", "Replaces the module menu with clean see-through columns and gives the other menus the same look.");
    }

    public static int accentColor() {
        ClickGui module = Modules.get().get(ClickGui.class);
        return module == null ? DEFAULT_ACCENT : module.accent.get().getPacked() | 0xFF000000;
    }

    @Override
    public void onActivate() {
        mc.execute(this::apply);
    }

    @Override
    public void onDeactivate() {
        mc.execute(this::restore);
    }

    private void apply() {
        String current = GuiThemes.get().name;
        if (matchScreens.get()) {
            if (!current.equals(CoyduckTheme.NAME)) {
                previousTheme.set(current);
                GuiThemes.select(CoyduckTheme.NAME);
            }
        } else if (current.equals(CoyduckTheme.NAME)) {
            GuiThemes.select(previousTheme.get().isEmpty() ? "Meteor" : previousTheme.get());
        }

        font(Config.get().customFont, previousFont);
        restoreHudFont();
    }

    private void restoreHudFont() {
        if (previousHudFont.get().equals("custom") && Hud.get().settings.get("custom-font") instanceof BoolSetting hudFont) {
            hudFont.set(true);
            previousHudFont.set("");
        }
    }

    private void font(Setting<Boolean> custom, Setting<String> previous) {
        if (minecraftFont.get()) {
            if (custom.get()) {
                previous.set("custom");
                custom.set(false);
            }
        } else if (previous.get().equals("custom") && !custom.get()) {
            custom.set(true);
            previous.set("");
        }
    }

    private void restore() {
        if (GuiThemes.get().name.equals(CoyduckTheme.NAME)) {
            GuiThemes.select(previousTheme.get().isEmpty() ? "Meteor" : previousTheme.get());
        }
        if (previousFont.get().equals("custom")) {
            Config.get().customFont.set(true);
            previousFont.set("");
        }
        restoreHudFont();
    }

    @EventHandler
    private void onOpenScreen(OpenScreenEvent event) {
        if (!(event.screen instanceof ModulesScreen)) return;
        event.cancel();
        mc.execute(() -> mc.setScreen(new CoyGuiScreen(this)));
    }
}
