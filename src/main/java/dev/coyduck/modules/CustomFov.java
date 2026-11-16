package dev.coyduck.modules;

import dev.coyduck.feature.BoolSetting;
import dev.coyduck.feature.Feature;
import dev.coyduck.feature.Features;
import dev.coyduck.feature.IntSetting;
import dev.coyduck.feature.Setting;
import dev.coyduck.feature.SettingGroup;

public class CustomFov extends Feature {
    private static final float MAX_FOV = 179.0f;

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
        .description("Keeps vanilla fov changes from sprinting and speed.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> hand = sgGeneral.add(new BoolSetting.Builder()
        .name("hand")
        .description("Also changes the held item field of view.")
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
        super("custom-fov", "Custom field of view.");
    }

    private static CustomFov active() {
        CustomFov module = Features.get(CustomFov.class);
        return module != null && module.isActive() ? module : null;
    }

    public static Integer world(Integer original) {
        CustomFov module = active();
        return module == null ? original : module.fov.get();
    }

    public static float multiplier(float original) {
        CustomFov module = active();
        if (module == null) return original;
        return module.effects.get() ? Math.min(original, MAX_FOV / module.fov.get()) : Math.min(original, 1.0f);
    }

    public static float hand(float original) {
        CustomFov module = active();
        return module == null || !module.hand.get() ? original : module.handFov.get();
    }
}
