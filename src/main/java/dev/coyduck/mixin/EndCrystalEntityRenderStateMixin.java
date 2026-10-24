package dev.coyduck.mixin;

import dev.coyduck.utils.CrystalRenderState;
import net.minecraft.client.render.entity.state.EndCrystalEntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(EndCrystalEntityRenderState.class)
public abstract class EndCrystalEntityRenderStateMixin implements CrystalRenderState {
    @Unique
    private int coyduck$color;

    @Override
    public int coyduck$color() {
        return coyduck$color;
    }

    @Override
    public void coyduck$setColor(int color) {
        coyduck$color = color;
    }
}
