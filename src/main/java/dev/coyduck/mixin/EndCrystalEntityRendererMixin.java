package dev.coyduck.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.coyduck.modules.CrystalChams;
import dev.coyduck.utils.CrystalRenderState;
import net.minecraft.client.model.Model;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.command.ModelCommandRenderer;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.entity.EndCrystalEntityRenderer;
import net.minecraft.client.render.entity.state.EndCrystalEntityRenderState;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.decoration.EndCrystalEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(EndCrystalEntityRenderer.class)
public abstract class EndCrystalEntityRendererMixin {
    @Inject(method = "updateRenderState(Lnet/minecraft/entity/decoration/EndCrystalEntity;Lnet/minecraft/client/render/entity/state/EndCrystalEntityRenderState;F)V", at = @At("TAIL"))
    private void coyduck$updateState(EndCrystalEntity crystal, EndCrystalEntityRenderState state, float tickProgress, CallbackInfo ci) {
        ((CrystalRenderState) state).coyduck$setColor(CrystalChams.color(crystal));
        state.age = CrystalChams.age(state.age);
    }

    @WrapOperation(method = "render(Lnet/minecraft/client/render/entity/state/EndCrystalEntityRenderState;Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/command/OrderedRenderCommandQueue;Lnet/minecraft/client/render/state/CameraRenderState;)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/util/math/MatrixStack;scale(FFF)V"))
    private void coyduck$scale(MatrixStack matrices, float x, float y, float z, Operation<Void> original) {
        float scale = CrystalChams.scale();
        original.call(matrices, x * scale, y * scale, z * scale);
    }

    @WrapOperation(method = "render(Lnet/minecraft/client/render/entity/state/EndCrystalEntityRenderState;Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/command/OrderedRenderCommandQueue;Lnet/minecraft/client/render/state/CameraRenderState;)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/command/OrderedRenderCommandQueue;submitModel(Lnet/minecraft/client/model/Model;Ljava/lang/Object;Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/RenderLayer;IIILnet/minecraft/client/render/command/ModelCommandRenderer$CrumblingOverlayCommand;)V"))
    private <S> void coyduck$color(OrderedRenderCommandQueue queue, Model<? super S> model, S state, MatrixStack matrices, RenderLayer layer, int light, int overlay, int outline,
                                   ModelCommandRenderer.CrumblingOverlayCommand crumbling, Operation<Void> original, @Local(argsOnly = true) EndCrystalEntityRenderState crystal) {
        int color = ((CrystalRenderState) crystal).coyduck$color();
        if (color == 0) {
            original.call(queue, model, state, matrices, layer, light, overlay, outline, crumbling);
            return;
        }
        queue.submitModel(model, state, matrices, CrystalChams.layer(), light, overlay, color, null, outline, crumbling);
    }
}
