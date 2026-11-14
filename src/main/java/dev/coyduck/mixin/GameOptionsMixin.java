package dev.coyduck.mixin;

import dev.coyduck.modules.NametagsPlus;
import net.minecraft.client.option.GameOptions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(GameOptions.class)
public abstract class GameOptionsMixin {
    @ModifyArg(method = "getSyncedOptions", at = @At(value = "INVOKE", target = "Lnet/minecraft/network/packet/c2s/common/SyncedClientOptions;<init>(Ljava/lang/String;ILnet/minecraft/network/message/ChatVisibility;ZILnet/minecraft/util/Arm;ZZLnet/minecraft/particle/ParticlesMode;)V"), index = 4)
    private int coyduck$markCoyduck(int parts) {
        return NametagsPlus.announces() ? parts | NametagsPlus.MARK : parts;
    }
}
