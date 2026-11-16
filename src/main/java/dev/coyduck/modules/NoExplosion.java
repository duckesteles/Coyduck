package dev.coyduck.modules;

import dev.coyduck.events.ParticleEvent;
import dev.coyduck.feature.BoolSetting;
import dev.coyduck.feature.Feature;
import dev.coyduck.feature.Features;
import dev.coyduck.feature.Setting;
import dev.coyduck.feature.SettingGroup;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.particle.ParticleType;
import net.minecraft.particle.ParticleTypes;

public class NoExplosion extends Feature {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> blast = sgGeneral.add(new BoolSetting.Builder()
        .name("blast")
        .description("Hides the big explosion puff of crystals and anchors.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> smoke = sgGeneral.add(new BoolSetting.Builder()
        .name("smoke")
        .description("Hides the smoke and dust an explosion throws around.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> totem = sgGeneral.add(new BoolSetting.Builder()
        .name("totem-particles")
        .description("Hides the green and yellow particles of a totem pop.")
        .defaultValue(false)
        .build()
    );

    public NoExplosion() {
        super("no-explosion", "Hides explosion particles.");
    }

    public static boolean hidesSmoke() {
        NoExplosion module = Features.get(NoExplosion.class);
        return module != null && module.isActive() && module.smoke.get();
    }

    @EventHandler
    private void onParticle(ParticleEvent event) {
        ParticleType<?> type = event.particle.getType();
        if (blast.get() && (type == ParticleTypes.EXPLOSION || type == ParticleTypes.EXPLOSION_EMITTER)) event.cancel();
        else if (totem.get() && type == ParticleTypes.TOTEM_OF_UNDYING) event.cancel();
    }
}
