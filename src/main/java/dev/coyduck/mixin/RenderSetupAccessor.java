package dev.coyduck.mixin;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.minecraft.client.render.LayeringTransform;
import net.minecraft.client.render.OutputTarget;
import net.minecraft.client.render.RenderSetup;
import net.minecraft.client.render.TextureTransform;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.Map;

@SuppressWarnings("rawtypes")
@Mixin(RenderSetup.class)
public interface RenderSetupAccessor {
    @Accessor("pipeline")
    RenderPipeline coyduck$getPipeline();

    // Map<String, RenderSetup.TextureSpec>, but TextureSpec is not visible from here.
    @Accessor("textures")
    Map coyduck$getTextures();

    @Accessor("useLightmap")
    boolean coyduck$getUseLightmap();

    @Accessor("useOverlay")
    boolean coyduck$getUseOverlay();

    @Accessor("layeringTransform")
    LayeringTransform coyduck$getLayeringTransform();

    @Accessor("outputTarget")
    OutputTarget coyduck$getOutputTarget();

    @Accessor("textureTransform")
    TextureTransform coyduck$getTextureTransform();

    @Accessor("outlineMode")
    RenderSetup.OutlineMode coyduck$getOutlineMode();

    @Accessor("hasCrumbling")
    boolean coyduck$getHasCrumbling();

    @Accessor("translucent")
    boolean coyduck$getTranslucent();

    @Accessor("expectedBufferSize")
    int coyduck$getExpectedBufferSize();

    @Invoker("<init>")
    static RenderSetup coyduck$create(RenderPipeline pipeline, Map textures, boolean useLightmap, boolean useOverlay, LayeringTransform layeringTransform,
                                      OutputTarget outputTarget, TextureTransform textureTransform, RenderSetup.OutlineMode outlineMode,
                                      boolean hasCrumbling, boolean translucent, int expectedBufferSize) {
        throw new AssertionError();
    }
}
