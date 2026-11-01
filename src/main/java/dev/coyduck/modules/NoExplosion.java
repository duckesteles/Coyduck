package dev.coyduck.modules;

import dev.coyduck.Coyduck;
import meteordevelopment.meteorclient.events.world.ParticleEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.particle.ParticleType;
import net.minecraft.particle.ParticleTypes;

public class NoExplosion extends Module {
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
        super(Coyduck.CATEGORY, "no-explosion", "Hides crystal explosion particles without a resource pack. The sound and knockback stay.");
    }

    public static boolean hidesSmoke() {
        Modules modules = Modules.get();
        if (modules == null) return false;
        NoExplosion module = modules.get(NoExplosion.class);
        return module != null && module.isActive() && module.smoke.get();
    }

    @EventHandler
    private void onParticle(ParticleEvent event) {
        ParticleType<?> type = event.particle.getType();
        if (blast.get() && (type == ParticleTypes.EXPLOSION || type == ParticleTypes.EXPLOSION_EMITTER)) event.cancel();
        else if (totem.get() && type == ParticleTypes.TOTEM_OF_UNDYING) event.cancel();
    }
}
