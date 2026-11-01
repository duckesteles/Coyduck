package dev.coyduck.modules;

import dev.coyduck.Coyduck;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.render.ESP;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import net.minecraft.entity.EntityType;
import net.minecraft.nbt.NbtCompound;

import java.util.HashSet;
import java.util.Set;

public class Shaders extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<SettingColor> color = sgGeneral.add(new ColorSetting.Builder()
        .name("color")
        .description("Color of the shader on players, crystals and held items, and of every Coyduck render.")
        .defaultValue(new SettingColor(165, 120, 255, 255))
        .onChanged(value -> refresh())
        .build()
    );

    private final Setting<Boolean> players = sgGeneral.add(new BoolSetting.Builder()
        .name("players")
        .description("Puts the shader on other players.")
        .defaultValue(true)
        .onChanged(value -> refresh())
        .build()
    );

    private final Setting<Boolean> crystals = sgGeneral.add(new BoolSetting.Builder()
        .name("crystals")
        .description("Puts the shader on end crystals.")
        .defaultValue(true)
        .onChanged(value -> refresh())
        .build()
    );

    private final Setting<Boolean> hands = sgGeneral.add(new BoolSetting.Builder()
        .name("hands")
        .description("Glows the items you hold in first person.")
        .defaultValue(true)
        .onChanged(value -> refresh())
        .build()
    );

    private final Setting<Boolean> allRenders = sgGeneral.add(new BoolSetting.Builder()
        .name("all-renders")
        .description("Uses the color for hole, break, crystal and block renders too. The red to green mining box keeps its colors.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> fill = sgGeneral.add(new DoubleSetting.Builder()
        .name("fill")
        .description("How strongly the color fills the model.")
        .defaultValue(0.35)
        .range(0, 1)
        .sliderRange(0, 1)
        .onChanged(value -> refresh())
        .build()
    );

    private final Setting<Integer> width = sgGeneral.add(new IntSetting.Builder()
        .name("glow-width")
        .description("How far the glow reaches around the model.")
        .defaultValue(3)
        .range(1, 10)
        .sliderRange(1, 8)
        .onChanged(value -> refresh())
        .build()
    );

    private final Setting<Double> glow = sgGeneral.add(new DoubleSetting.Builder()
        .name("glow")
        .description("How bright the glow is.")
        .defaultValue(2.5)
        .range(0, 10)
        .sliderRange(0, 6)
        .onChanged(value -> refresh())
        .build()
    );

    private NbtCompound savedEsp;
    private boolean espWasOn;

    public Shaders() {
        super(Coyduck.CATEGORY, "shaders+", "One color for everything: a glow on players, crystals and held items, and all Coyduck renders.");
    }

    private static Shaders active() {
        Shaders module = Modules.get().get(Shaders.class);
        return module != null && module.isActive() ? module : null;
    }

    public static boolean handsOn() {
        Shaders module = active();
        return module != null && module.hands.get();
    }

    public static int colorValue() {
        Shaders module = active();
        return module == null ? -1 : module.color.get().getPacked();
    }

    public static Color tint(Color original) {
        Shaders module = active();
        if (module == null || !module.allRenders.get()) return original;
        Color global = module.color.get();
        return new Color(global.r, global.g, global.b, original.a);
    }

    @Override
    public void onActivate() {
        mc.execute(() -> {
            if (!isActive()) return;
            ESP esp = Modules.get().get(ESP.class);
            savedEsp = esp.settings.toTag();
            espWasOn = esp.isActive();
            refresh();
        });
    }

    @Override
    public void onDeactivate() {
        mc.execute(() -> {
            ESP esp = Modules.get().get(ESP.class);
            if (savedEsp != null) esp.settings.fromTag(savedEsp);
            if (esp.isActive() != espWasOn) esp.toggle();
            savedEsp = null;
        });
    }

    @SuppressWarnings("unchecked")
    private void refresh() {
        if (!isActive() || savedEsp == null) return;

        ESP esp = Modules.get().get(ESP.class);
        esp.settings.get("mode").parse("Shader");
        esp.settings.get("shape-mode").parse("Both");
        esp.settings.get("color-mode").parse("EntityType");
        ((Setting<Integer>) esp.settings.get("outline-width")).set(width.get());
        ((Setting<Double>) esp.settings.get("glow-multiplier")).set(glow.get());
        ((Setting<Double>) esp.settings.get("fill-opacity")).set(fill.get());
        ((Setting<SettingColor>) esp.settings.get("players-color")).set(new SettingColor(color.get()));
        ((Setting<SettingColor>) esp.settings.get("misc-color")).set(new SettingColor(color.get()));
        ((Setting<SettingColor>) esp.settings.get("non-living-entity-color")).set(new SettingColor(color.get()));

        Set<EntityType<?>> types = new HashSet<>();
        if (players.get()) types.add(EntityType.PLAYER);
        if (crystals.get()) types.add(EntityType.END_CRYSTAL);
        ((Setting<Set<EntityType<?>>>) esp.settings.get("entities")).set(types);
        boolean wantEsp = !types.isEmpty();
        if (esp.isActive() != wantEsp) esp.toggle();
    }
}
