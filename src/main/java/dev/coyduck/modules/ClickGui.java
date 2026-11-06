package dev.coyduck.modules;

import dev.coyduck.Coyduck;
import dev.coyduck.gui.CoyGuiScreen;
import dev.coyduck.gui.CoyduckTheme;
import meteordevelopment.meteorclient.events.game.OpenScreenEvent;
import meteordevelopment.meteorclient.gui.GuiThemes;
import meteordevelopment.meteorclient.gui.screens.ModulesScreen;
import meteordevelopment.meteorclient.gui.tabs.TabScreen;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.StringListSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.renderer.Fonts;
import meteordevelopment.meteorclient.renderer.text.FontFace;
import meteordevelopment.meteorclient.renderer.text.FontFamily;
import meteordevelopment.meteorclient.renderer.text.FontInfo;
import meteordevelopment.meteorclient.systems.config.Config;
import meteordevelopment.meteorclient.systems.hud.Hud;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.util.Identifier;

import java.util.List;

public class ClickGui extends Module {
    public enum GuiFont {
        NotoSans("Noto Sans", "gui_noto_sans"),
        DejaVuSans("DejaVu Sans", "gui_dejavu_sans"),
        Minecraft(null, null);

        private final String family;
        private final String id;

        GuiFont(String family, String id) {
            this.family = family;
            this.id = id;
        }
    }

    private static final int DEFAULT_ACCENT = 0xFF7B62AA;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgPages = settings.createGroup("Pages");

    public final Setting<SettingColor> accent = sgGeneral.add(new ColorSetting.Builder()
        .name("accent")
        .description("Color of enabled modules, sliders, checkboxes and the Coyduck HUD.")
        .defaultValue(new SettingColor(123, 98, 170, 255))
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

    private final Setting<Boolean> settingsWindow = sgGeneral.add(new BoolSetting.Builder()
        .name("settings-window")
        .description("Right clicking a module opens its settings in a window instead of inside the panel.")
        .defaultValue(false)
        .build()
    );

    private final Setting<GuiFont> font = sgGeneral.add(new EnumSetting.Builder<GuiFont>()
        .name("font")
        .description("Font of the module menu and the other menus. The HUD keeps its own font.")
        .defaultValue(GuiFont.NotoSans)
        .onChanged(value -> {
            if (isActive()) apply();
        })
        .build()
    );

    private final Setting<Boolean> splitCoyduck = sgGeneral.add(new BoolSetting.Builder()
        .name("split-coyduck")
        .description("Sorts Coyduck modules into Combat, Player, Movement, Render, World and Misc panels like Meteor's own.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> pages = sgPages.add(new BoolSetting.Builder()
        .name("pages")
        .description("Gives every addon its own page. Switch pages with the left and right arrow keys or A and D.")
        .defaultValue(false)
        .build()
    );

    private final Setting<List<String>> pageOrder = sgPages.add(new StringListSetting.Builder()
        .name("page-order")
        .description("Addon names in page order. Addons left out come after these.")
        .defaultValue(List.of("Meteor Client", "Coyduck"))
        .visible(pages::get)
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

    public boolean opensSettingsWindow() {
        return settingsWindow.get();
    }

    public Identifier fontId() {
        return font.get().id == null ? null : Identifier.of("coyduck", font.get().id);
    }

    public boolean splitsCoyduck() {
        return splitCoyduck.get();
    }

    public boolean usesPages() {
        return pages.get();
    }

    public List<String> pageOrder() {
        return pageOrder.get();
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

        applyFont();
        restoreHudFont();
    }

    private void restoreHudFont() {
        if (previousHudFont.get().equals("custom") && Hud.get().settings.get("custom-font") instanceof BoolSetting hudFont) {
            hudFont.set(true);
            previousHudFont.set("");
        }
    }

    private void applyFont() {
        Setting<Boolean> custom = Config.get().customFont;
        GuiFont choice = font.get();
        if (choice.family == null) {
            if (custom.get()) {
                if (previousFont.get().isEmpty()) previousFont.set("custom");
                custom.set(false);
            }
            return;
        }

        FontFamily family = Fonts.getFamily(choice.family);
        FontFace face = family == null ? null : family.get(FontInfo.Type.Regular);
        if (face == null) return;
        if (!custom.get()) {
            if (previousFont.get().isEmpty()) previousFont.set("vanilla");
            custom.set(true);
        }
        if (Config.get().font.get() != face) Config.get().font.set(face);
    }

    private void restore() {
        if (GuiThemes.get().name.equals(CoyduckTheme.NAME)) {
            GuiThemes.select(previousTheme.get().isEmpty() ? "Meteor" : previousTheme.get());
        }
        if (previousFont.get().equals("custom")) Config.get().customFont.set(true);
        else if (previousFont.get().equals("vanilla")) Config.get().customFont.set(false);
        previousFont.set("");
        restoreHudFont();
    }

    @EventHandler
    private void onOpenScreen(OpenScreenEvent event) {
        if (CoyGuiScreen.passingThrough()) return;
        CoyGuiScreen.View view = null;
        if (event.screen instanceof ModulesScreen) view = CoyGuiScreen.View.Modules;
        else if (event.screen instanceof TabScreen screen) view = CoyGuiScreen.viewOf(screen.tab.name);
        if (view == null) return;
        event.cancel();
        CoyGuiScreen.View open = view;
        mc.execute(() -> mc.setScreen(new CoyGuiScreen(this, open)));
    }
}
