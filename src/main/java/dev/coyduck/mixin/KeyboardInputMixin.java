package dev.coyduck.mixin;

import dev.coyduck.managers.MoveInput;
import dev.coyduck.managers.RotationManager;
import net.minecraft.client.input.Input;
import net.minecraft.client.input.KeyboardInput;
import net.minecraft.util.PlayerInput;
import net.minecraft.util.math.Vec2f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(KeyboardInput.class)
public abstract class KeyboardInputMixin extends Input {
    @Inject(method = "tick", at = @At("TAIL"))
    private void coyduck$fixInput(CallbackInfo ci) {
        PlayerInput forced = MoveInput.input();
        if (forced != null) {
            playerInput = forced;
            movementVector = MoveInput.vector(forced);
        }
        Vec2f[] movement = {movementVector};
        playerInput = RotationManager.get().fixInput(playerInput, movement);
        movementVector = movement[0];
    }
}
