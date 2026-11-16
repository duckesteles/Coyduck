package dev.coyduck.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import dev.coyduck.MeteorFeature;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(value = Modules.class, remap = false)
public abstract class ModulesMixin {
    @ModifyArg(method = "add", at = @At(value = "INVOKE", target = "Ljava/util/Map;put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", ordinal = 0), index = 0)
    private Object coyduck$featureKey(Object key, @Local(argsOnly = true) Module module) {
        return module instanceof MeteorFeature adapter ? adapter.feature().getClass() : key;
    }
}
