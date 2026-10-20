package dev.coyduck.mixin;

import dev.coyduck.managers.InventoryManager;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPlayerInteractionManager.class)
public abstract class ClientPlayerInteractionManagerMixin {
    @Shadow
    @Final
    private MinecraftClient client;

    @Shadow
    private int lastSelectedSlot;

    @Inject(method = "syncSelectedSlot", at = @At("HEAD"))
    private void coyduck$onSyncSelectedSlot(CallbackInfo ci) {
        if (client.player != null && client.player.getInventory().getSelectedSlot() != lastSelectedSlot) InventoryManager.get().noteSelectChange();
    }
}
