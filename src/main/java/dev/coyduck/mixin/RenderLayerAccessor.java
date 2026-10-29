package dev.coyduck.mixin;

import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderSetup;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(RenderLayer.class)
public interface RenderLayerAccessor {
    @Accessor("renderSetup")
    RenderSetup coyduck$getRenderSetup();

    @Accessor("name")
    String coyduck$getName();

    @Invoker("of")
    static RenderLayer coyduck$of(String name, RenderSetup setup) {
        throw new AssertionError();
    }
}
