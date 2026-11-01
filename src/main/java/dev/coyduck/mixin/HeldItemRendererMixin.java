package dev.coyduck.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.coyduck.modules.Shaders;
import dev.coyduck.utils.HandQueue;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.item.HeldItemRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.ItemDisplayContext;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(HeldItemRenderer.class)
public abstract class HeldItemRendererMixin {
    @WrapMethod(method = "renderItem(Lnet/minecraft/entity/LivingEntity;Lnet/minecraft/item/ItemStack;Lnet/minecraft/item/ItemDisplayContext;Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/command/OrderedRenderCommandQueue;I)V")
    private void coyduck$glow(LivingEntity entity, ItemStack stack, ItemDisplayContext context, MatrixStack matrices, OrderedRenderCommandQueue queue, int light, Operation<Void> original) {
        boolean glow = context.isFirstPerson() && Shaders.handsOn();
        original.call(entity, stack, context, matrices, glow ? new HandQueue(queue, Shaders.colorValue()) : queue, light);
    }
}
