package dev.coyduck.mixin;

import dev.coyduck.modules.AirMine;
import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

import static meteordevelopment.meteorclient.MeteorClient.mc;

@Mixin(PlayerEntity.class)
public abstract class PlayerEntityMixin {
    @ModifyConstant(method = "getBlockBreakingSpeed", constant = @Constant(floatValue = 5.0f))
    private float coyduck$airMine(float original) {
        return (Object) this == mc.player && AirMine.spoofing() ? 1.0f : original;
    }
}
