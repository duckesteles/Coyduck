package dev.coyduck.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.coyduck.modules.CustomFov;
import net.minecraft.client.render.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

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
}
