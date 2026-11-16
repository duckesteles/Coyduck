package dev.coyduck.mixin;

import dev.coyduck.core.Bus;
import dev.coyduck.core.Clock;
import dev.coyduck.events.GameLeftEvent;
import dev.coyduck.events.TickEvent;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.world.ClientWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftClient.class)
public abstract class MinecraftClientMixin {
    @Shadow
    public ClientWorld world;

    @Inject(method = "tick", at = @At("HEAD"))
    private void coyduck$tickPre(CallbackInfo ci) {
        Clock.advance();
        Bus.post(TickEvent.Pre.INSTANCE);
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void coyduck$tickPost(CallbackInfo ci) {
        Bus.post(TickEvent.Post.INSTANCE);
    }

    @Inject(method = "disconnect(Lnet/minecraft/client/gui/screen/Screen;ZZ)V", at = @At("HEAD"))
    private void coyduck$disconnect(Screen screen, boolean transferring, boolean stopSound, CallbackInfo ci) {
        if (world != null) Bus.post(GameLeftEvent.INSTANCE);
    }
}
