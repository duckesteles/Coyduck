package dev.coyduck.render;

import net.minecraft.client.render.entity.state.EntityRenderState;

public interface Glow {
    boolean glowing();

    boolean shows(EntityRenderState state);

    int maskColor();

    default int color(EntityRenderState state) {
        return maskColor();
    }

    int glowWidth();

    float fillOpacity();

    float glowStrength();
}
