package dev.coyduck.mixin;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.minecraft.client.gl.RenderPipelines;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(RenderPipelines.class)
public interface RenderPipelinesAccessor {
    @Accessor("ENTITY_EMISSIVE_SNIPPET")
    static RenderPipeline.Snippet coyduck$entityEmissive() {
        throw new AssertionError();
    }
}
