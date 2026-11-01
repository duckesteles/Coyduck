package dev.coyduck.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.coyduck.modules.CustomFov;
import dev.coyduck.modules.Shaders;
import dev.coyduck.utils.ShaderGlow;
import net.minecraft.client.render.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    @ModifyExpressionValue(method = "getFov", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/option/SimpleOption;getValue()Ljava/lang/Object;", ordinal = 0))
    private Object coyduck$worldFov(Object original) {
        return original instanceof Integer value ? CustomFov.world(value) : original;
    }

    @ModifyExpressionValue(method = "getFov", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/math/MathHelper;lerp(FFF)F", ordinal = 0))
    private float coyduck$fovEffects(float original) {
        return CustomFov.multiplier(original);
    }

    @ModifyConstant(method = "getFov", constant = @Constant(floatValue = 70.0f))
    private float coyduck$handFov(float original) {
        return CustomFov.hand(original);
    }

    @Inject(method = "renderHand", at = @At("TAIL"))
    private void coyduck$submitHandGlow(CallbackInfo info) {
        Shaders module = Shaders.active();
        if (module != null && module.handsOn()) ShaderGlow.get().submitVertices();
    }
}
