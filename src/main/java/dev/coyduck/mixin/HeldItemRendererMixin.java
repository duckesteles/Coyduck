package dev.coyduck.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.coyduck.modules.Shaders;
import dev.coyduck.utils.HandQueue;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.item.HeldItemRenderer;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(HeldItemRenderer.class)
public abstract class HeldItemRendererMixin {
    @WrapMethod(method = "renderItem(FLnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/command/OrderedRenderCommandQueue;Lnet/minecraft/client/network/ClientPlayerEntity;I)V")
    private void coyduck$glow(float tickDelta, MatrixStack matrices, OrderedRenderCommandQueue queue, ClientPlayerEntity player, int light, Operation<Void> original) {
        Shaders module = Shaders.active();
        boolean glow = module != null && module.handsOn();
        original.call(tickDelta, matrices, glow ? new HandQueue(queue, module.maskColor().getPacked()) : queue, player, light);
    }
}
