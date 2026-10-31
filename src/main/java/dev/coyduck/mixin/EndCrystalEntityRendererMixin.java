package dev.coyduck.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.coyduck.modules.CrystalChams;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.entity.EndCrystalEntityRenderer;
import net.minecraft.client.render.entity.model.EndCrystalEntityModel;
import net.minecraft.client.render.entity.state.EndCrystalEntityRenderState;
import net.minecraft.client.render.state.CameraRenderState;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(EndCrystalEntityRenderer.class)
public abstract class EndCrystalEntityRendererMixin implements CrystalChams.ModelHolder {
    @Shadow
    @Final
    private EndCrystalEntityModel model;

    @Override
    public EndCrystalEntityModel coyduck$model() {
        return model;
    }

    @WrapMethod(method = "render(Lnet/minecraft/client/render/entity/state/EndCrystalEntityRenderState;Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/command/OrderedRenderCommandQueue;Lnet/minecraft/client/render/state/CameraRenderState;)V")
    private void coyduck$chams(EndCrystalEntityRenderState state, MatrixStack matrices, OrderedRenderCommandQueue queue, CameraRenderState camera, Operation<Void> original) {
        if (!CrystalChams.hidesModel()) original.call(state, matrices, queue, camera);
    }
}
