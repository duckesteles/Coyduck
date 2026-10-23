package dev.coyduck.modules;

import dev.coyduck.Coyduck;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;

public class CustomFov extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Integer> fov = sgGeneral.add(new IntSetting.Builder()
        .name("fov")
        .description("Field of view, past the vanilla 30 to 110 limit.")
        .defaultValue(110)
        .range(1, 170)
        .sliderRange(30, 170)
        .build()
    );

    private final Setting<Boolean> effects = sgGeneral.add(new BoolSetting.Builder()
        .name("effects")
        .description("Keeps the vanilla field of view changes from sprinting, speed and drawing a bow.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> hand = sgGeneral.add(new BoolSetting.Builder()
        .name("hand")
        .description("Also changes the field of view your held items are drawn with.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> handFov = sgGeneral.add(new IntSetting.Builder()
        .name("hand-fov")
        .description("Field of view for your held items. Vanilla uses 70.")
        .defaultValue(70)
        .range(1, 170)
        .sliderRange(30, 170)
        .visible(hand::get)
        .build()
    );

    public CustomFov() {
        super(Coyduck.CATEGORY, "custom-fov", "Sets any field of view, separately for the world and your hand.");
    }

    private static CustomFov active() {
        CustomFov module = Modules.get().get(CustomFov.class);
        return module != null && module.isActive() ? module : null;
    }

    public static Integer world(Integer original) {
        CustomFov module = active();
        return module == null ? original : module.fov.get();
    }

    public static float multiplier(float original) {
        CustomFov module = active();
        return module == null || module.effects.get() ? original : 1.0f;
    }

    public static float hand(float original) {
        CustomFov module = active();
        return module == null || !module.hand.get() ? original : module.handFov.get();
    }
}
