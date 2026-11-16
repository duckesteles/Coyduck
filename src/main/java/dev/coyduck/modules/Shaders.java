package dev.coyduck.modules;

import dev.coyduck.feature.BoolSetting;
import dev.coyduck.feature.ColorSetting;
import dev.coyduck.feature.DoubleSetting;
import dev.coyduck.feature.Feature;
import dev.coyduck.feature.Features;
import dev.coyduck.feature.IntSetting;
import dev.coyduck.feature.Rgba;
import dev.coyduck.feature.Setting;
import dev.coyduck.feature.SettingGroup;
import dev.coyduck.render.Argb;
import dev.coyduck.render.Glow;
import net.minecraft.client.render.entity.state.EndCrystalEntityRenderState;
import net.minecraft.client.render.entity.state.EntityRenderState;
import net.minecraft.client.render.entity.state.PlayerEntityRenderState;
import net.minecraft.entity.EntityType;

public class Shaders extends Feature implements Glow {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgAtmosphere = settings.createGroup("Atmosphere");

    private final ColorSetting color = sgGeneral.add(new ColorSetting.Builder()
        .name("color")
        .description("Shader color, also used by Coyduck renders.")
        .defaultValue(new Rgba(130, 202, 255, 255))
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

    private final Setting<Boolean> pearls = sgGeneral.add(new BoolSetting.Builder()
        .name("pearls")
        .description("Puts the shader on flying ender pearls.")
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
        .description("Uses the color for other Coyduck renders too.")
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
        .defaultValue(false)
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
        .description("Time of day in ticks, 6000 is noon.")
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
        super("shaders+", "Glow on players and crystals.");
    }

    public static Shaders active() {
        Shaders module = Features.get(Shaders.class);
        return module != null && module.isActive() ? module : null;
    }

    public static int tint(int argb) {
        Shaders module = active();
        if (module == null || !module.allRenders.get()) return argb;
        return Argb.alpha(module.color.argb(), Argb.a(argb));
    }

    public static int lightTint(int argb) {
        Shaders module = active();
        if (module == null || !module.allRenders.get()) return argb;
        int global = module.color.argb();
        return Argb.of((Argb.r(global) + 255) / 2, (Argb.g(global) + 255) / 2, (Argb.b(global) + 255) / 2, Argb.a(argb));
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

    @Override
    public boolean glowing() {
        return isActive();
    }

    @Override
    public boolean shows(EntityRenderState state) {
        if (state instanceof PlayerEntityRenderState player) return players.get() && (mc.player == null || player.id != mc.player.getId());
        if (state.entityType == EntityType.ENDER_PEARL) return pearls.get();
        return state instanceof EndCrystalEntityRenderState && crystals.get();
    }

    @Override
    public int maskColor() {
        return color.argb() | 0xFF000000;
    }

    @Override
    public int glowWidth() {
        return width.get();
    }

    @Override
    public float fillOpacity() {
        return fill.get().floatValue();
    }

    @Override
    public float glowStrength() {
        return glow.get().floatValue();
    }
}
