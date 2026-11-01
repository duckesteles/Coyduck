package dev.coyduck.mixin;

import dev.coyduck.modules.Step;
import net.minecraft.client.render.Camera;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Camera.class)
public abstract class CameraMixin {
    @Shadow
    protected abstract void setPos(double x, double y, double z);

    @Shadow
    public abstract Vec3d getCameraPos();

    @Inject(method = "update", at = @At("TAIL"))
    private void coyduck$smoothStep(CallbackInfo info) {
        Vec3d pos = getCameraPos();
        double y = Step.cameraY(pos.y);
        if (y != pos.y) setPos(pos.x, y, pos.z);
    }
}
