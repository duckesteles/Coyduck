package dev.coyduck.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.coyduck.modules.CrystalChams;
import dev.coyduck.utils.WallQueue;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.entity.EndCrystalEntityRenderer;
import net.minecraft.client.render.entity.state.EndCrystalEntityRenderState;
import net.minecraft.client.render.state.CameraRenderState;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(EndCrystalEntityRenderer.class)
public abstract class EndCrystalEntityRendererMixin {
    @WrapMethod(method = "render(Lnet/minecraft/client/render/entity/state/EndCrystalEntityRenderState;Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/command/OrderedRenderCommandQueue;Lnet/minecraft/client/render/state/CameraRenderState;)V")
    private void coyduck$chams(EndCrystalEntityRenderState state, MatrixStack matrices, OrderedRenderCommandQueue queue, CameraRenderState camera, Operation<Void> original) {
        int tint = CrystalChams.tint();
        if (tint == -1 && !CrystalChams.walls()) {
            original.call(state, matrices, queue, camera);
            return;
        }
        original.call(state, matrices, new WallQueue(queue, CrystalChams.walls(), tint, CrystalChams.light(WallQueue.KEEP)), camera);
    }
}
