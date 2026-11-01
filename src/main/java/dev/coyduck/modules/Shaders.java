package dev.coyduck.modules;

import dev.coyduck.Coyduck;
import dev.coyduck.utils.ShaderGlow;
import meteordevelopment.meteorclient.events.render.Render2DEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.render.entity.state.EndCrystalEntityRenderState;
import net.minecraft.client.render.entity.state.EntityRenderState;
import net.minecraft.client.render.entity.state.PlayerEntityRenderState;

public class Shaders extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgAtmosphere = settings.createGroup("Atmosphere");

    private final Setting<SettingColor> color = sgGeneral.add(new ColorSetting.Builder()
        .name("color")
        .description("Color of the shader on players, crystals and held items, and of every Coyduck render.")
        .defaultValue(new SettingColor(130, 202, 255, 255))
        .build()
    );

    private final Setting<Boolean> players = sgGeneral.add(new BoolSetting.Builder()
        .name("players")
        .description("Puts the shader on other players.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> crystals = sgGeneral.add(new BoolSetting.Builder()
        .name("crystals")
        .description("Puts the shader on end crystals.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> hands = sgGeneral.add(new BoolSetting.Builder()
        .name("hands")
        .description("Glows the items you hold in first person.")
        .defaultValue(true)
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
        .defaultValue(0.31)
        .range(0, 1)
        .sliderRange(0, 1)
        .build()
    );

    private final Setting<Integer> width = sgGeneral.add(new IntSetting.Builder()
        .name("glow-width")
        .description("How far the glow reaches around the model.")
        .defaultValue(2)
        .range(1, 10)
        .sliderRange(1, 8)
        .build()
    );

    private final Setting<Double> glow = sgGeneral.add(new DoubleSetting.Builder()
        .name("glow")
        .description("How bright the glow is.")
        .defaultValue(1.0)
        .range(0, 10)
        .sliderRange(0, 6)
        .build()
    );

    private final Setting<Boolean> atmosphere = sgAtmosphere.add(new BoolSetting.Builder()
        .name("atmosphere")
        .description("Paints the sky and fog with the color and pulls the fog in.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> changeTime = sgAtmosphere.add(new BoolSetting.Builder()
        .name("change-time")
        .description("Shows a fixed time of day on your screen only.")
        .defaultValue(true)
        .visible(atmosphere::get)
        .build()
    );

    private final Setting<Integer> time = sgAtmosphere.add(new IntSetting.Builder()
        .name("time")
        .description("The time of day to show. 6000 is noon and 18000 is midnight.")
        .defaultValue(20000)
        .range(0, 24000)
        .sliderRange(0, 24000)
        .visible(() -> atmosphere.get() && changeTime.get())
        .build()
    );

    private final Setting<Integer> fogStart = sgAtmosphere.add(new IntSetting.Builder()
        .name("fog-start")
        .description("How many blocks away the fog begins.")
        .defaultValue(50)
        .range(0, 512)
        .sliderRange(0, 128)
        .visible(atmosphere::get)
        .build()
    );

    private final Setting<Integer> fogEnd = sgAtmosphere.add(new IntSetting.Builder()
        .name("fog-end")
        .description("How many blocks away the fog is fully solid.")
        .defaultValue(150)
        .range(1, 512)
        .sliderRange(1, 256)
        .visible(atmosphere::get)
        .build()
    );

    public Shaders() {
        super(Coyduck.CATEGORY, "shaders+", "One color for everything: a glow on players, crystals and held items, and all Coyduck renders.");
    }

    public static Shaders active() {
        Modules modules = Modules.get();
        if (modules == null) return null;
        Shaders module = modules.get(Shaders.class);
        return module != null && module.isActive() ? module : null;
    }

    public static Color tint(Color original) {
        Shaders module = active();
        if (module == null || !module.allRenders.get()) return original;
        Color global = module.color.get();
        return new Color(global.r, global.g, global.b, original.a);
    }

    public static Color lightTint(Color original) {
        Shaders module = active();
        if (module == null || !module.allRenders.get()) return original;
        Color global = module.color.get();
        return new Color((global.r + 255) / 2, (global.g + 255) / 2, (global.b + 255) / 2, original.a);
    }

    public static Shaders atmosphere() {
        Shaders module = active();
        return module != null && module.atmosphere.get() ? module : null;
    }

    public boolean changesTime() {
        return changeTime.get();
    }

    public long timeOfDay() {
        return time.get();
    }

    public float fogStart() {
        return fogStart.get();
    }

    public float fogEnd() {
        return fogEnd.get();
    }

    public boolean handsOn() {
        return hands.get();
    }

    public boolean shows(EntityRenderState state) {
        if (state instanceof PlayerEntityRenderState player) return players.get() && (mc.player == null || player.id != mc.player.getId());
        return state instanceof EndCrystalEntityRenderState && crystals.get();
    }

    public Color maskColor() {
        Color global = color.get();
        return new Color(global.r, global.g, global.b, 255);
    }

    public int glowWidth() {
        return width.get();
    }

    public float fillOpacity() {
        return fill.get().floatValue();
    }

    public float glowStrength() {
        return glow.get().floatValue();
    }

    @EventHandler
    private void onRender2D(Render2DEvent event) {
        ShaderGlow.get().render();
    }
}
