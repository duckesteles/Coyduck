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

    private final Setting<SettingColor> color = sgGeneral.add(new ColorSetting.Builder()
        .name("color")
        .description("Color of the shader on players, crystals and held items, and of every Coyduck render.")
        .defaultValue(new SettingColor(165, 120, 255, 255))
        .build()
    );

    private final Setting<Boolean> players = sgGeneral.add(new BoolSetting.Builder()
        .name("players")
        .description("Puts the shader on other players.")
        .defaultValue(true)
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
        .defaultValue(0.35)
        .range(0, 1)
        .sliderRange(0, 1)
        .build()
    );

    private final Setting<Integer> width = sgGeneral.add(new IntSetting.Builder()
        .name("glow-width")
        .description("How far the glow reaches around the model.")
        .defaultValue(3)
        .range(1, 10)
        .sliderRange(1, 8)
        .build()
    );

    private final Setting<Double> glow = sgGeneral.add(new DoubleSetting.Builder()
        .name("glow")
        .description("How bright the glow is.")
        .defaultValue(2.5)
        .range(0, 10)
        .sliderRange(0, 6)
        .build()
    );

    public Shaders() {
        super(Coyduck.CATEGORY, "shaders+", "One color for everything: a glow on players, crystals and held items, and all Coyduck renders.");
    }

    public static Shaders active() {
        Shaders module = Modules.get().get(Shaders.class);
        return module != null && module.isActive() ? module : null;
    }

    public static Color tint(Color original) {
        Shaders module = active();
        if (module == null || !module.allRenders.get()) return original;
        Color global = module.color.get();
        return new Color(global.r, global.g, global.b, original.a);
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
