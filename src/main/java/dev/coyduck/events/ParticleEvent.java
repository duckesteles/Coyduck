package dev.coyduck.events;

import net.minecraft.particle.ParticleEffect;

public final class ParticleEvent extends Cancellable {
    private static final ParticleEvent INSTANCE = new ParticleEvent();

    public ParticleEffect particle;

    private ParticleEvent() {}

    public static ParticleEvent get(ParticleEffect particle) {
        INSTANCE.setCancelled(false);
        INSTANCE.particle = particle;
        return INSTANCE;
    }
}
