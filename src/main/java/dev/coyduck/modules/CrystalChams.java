package dev.coyduck.modules;

import dev.coyduck.Coyduck;
import dev.coyduck.utils.Culling;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.util.math.ColorHelper;

/**
 * Colours end crystals, lights them fully and keeps them visible through blocks, with the game's own glow outline
 * around them. It only changes how the crystal model is drawn, so it costs next to nothing.
 */
public class CrystalChams extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<SettingColor> color = sgGeneral.add(new ColorSetting.Builder()
        .name("color")
        .description("Colour of the crystals and their glow.")
        .defaultValue(new SettingColor(251, 192, 45, 255))
        .build()
    );

    private final Setting<Boolean> throughWalls = sgGeneral.add(new BoolSetting.Builder()
        .name("through-walls")
        .description("Draws crystals in front of the blocks that hide them.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> glow = sgGeneral.add(new BoolSetting.Builder()
        .name("glow")
        .description("Puts a glowing outline in the same colour around each crystal, seen through blocks too.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> bright = sgGeneral.add(new BoolSetting.Builder()
        .name("bright")
        .description("Draws crystals at full brightness, so dark holes do not dim them.")
        .defaultValue(true)
        .build()
    );

    public CrystalChams() {
        super(Coyduck.CATEGORY, "crystal-chams", "Colours end crystals and shows them through walls with a glow.");
    }

    private static CrystalChams active() {
        CrystalChams module = Modules.get().get(CrystalChams.class);
        return module != null && module.isActive() ? module : null;
    }

    public static boolean affects(Entity entity) {
        return entity instanceof EndCrystalEntity && active() != null;
    }

    public static boolean throughWalls(Entity entity) {
        CrystalChams module = active();
        return module != null && entity instanceof EndCrystalEntity && module.throughWalls.get();
    }

    public static boolean walls() {
        CrystalChams module = active();
        return module != null && module.throughWalls.get();
    }

    /** ARGB tint for the crystal model. */
    public static int tint() {
        CrystalChams module = active();
        return module == null ? -1 : module.color.get().getPacked();
    }

    public static int light(int own) {
        CrystalChams module = active();
        return module != null && module.bright.get() ? LightmapTextureManager.MAX_LIGHT_COORDINATE : own;
    }

    /** Outline colour for the glow, or 0 for none. */
    public static int glow(Entity entity) {
        CrystalChams module = active();
        if (module == null || !module.glow.get() || !(entity instanceof EndCrystalEntity)) return 0;
        return ColorHelper.fullAlpha(module.color.get().getPacked());
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.world == null || !throughWalls.get()) return;
        for (Entity entity : mc.world.getEntities()) {
            if (entity instanceof EndCrystalEntity) Culling.keep(entity);
        }
    }
}
