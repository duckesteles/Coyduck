package dev.coyduck.utils;

import net.minecraft.client.model.Model;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.command.ModelCommandRenderer;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.command.RenderCommandQueue;
import net.minecraft.client.render.item.ItemRenderState;
import net.minecraft.client.render.model.BakedQuad;
import net.minecraft.client.texture.Sprite;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.ItemDisplayContext;

import java.util.List;

public final class HandQueue extends WallQueue {
    private final int color;

    public HandQueue(RenderCommandQueue delegate, int color) {
        super(delegate);
        this.color = color;
    }

    @Override
    protected RenderLayer convert(RenderLayer layer) {
        return layer;
    }

    @Override
    public RenderCommandQueue getBatchingQueue(int order) {
        return delegate instanceof OrderedRenderCommandQueue ordered ? new HandQueue(ordered.getBatchingQueue(order), color) : this;
    }

    private VertexConsumer mask(RenderLayer layer, Sprite sprite) {
        VertexConsumer consumer = ShaderGlow.get().vertexConsumerProvider.getBuffer(layer);
        return sprite != null ? sprite.getTextureSpecificVertexConsumer(consumer) : consumer;
    }

    @Override
    public <S> void submitModel(Model<? super S> model, S state, MatrixStack matrices, RenderLayer layer, int light, int overlay, int tint, Sprite sprite, int outlineColor, ModelCommandRenderer.CrumblingOverlayCommand crumbling) {
        super.submitModel(model, state, matrices, layer, light, overlay, tint, sprite, outlineColor, crumbling);
        model.setAngles(state);
        model.render(matrices, mask(layer, sprite), light, overlay, color);
    }

    @Override
    public void submitModelPart(ModelPart part, MatrixStack matrices, RenderLayer layer, int light, int overlay, Sprite sprite, boolean sheeted, boolean hasGlint, int tint, ModelCommandRenderer.CrumblingOverlayCommand crumbling, int outlineColor) {
        super.submitModelPart(part, matrices, layer, light, overlay, sprite, sheeted, hasGlint, tint, crumbling, outlineColor);
        part.render(matrices, mask(layer, sprite), light, overlay, color);
    }

    @Override
    public void submitItem(MatrixStack matrices, ItemDisplayContext context, int light, int overlay, int outlineColor, int[] tints, List<BakedQuad> quads, RenderLayer layer, ItemRenderState.Glint glint) {
        super.submitItem(matrices, context, light, overlay, outlineColor, tints, quads, layer, glint);
        VertexConsumer consumer = mask(layer, null);
        float r = ((color >> 16) & 0xFF) / 255f;
        float g = ((color >> 8) & 0xFF) / 255f;
        float b = (color & 0xFF) / 255f;
        for (BakedQuad quad : quads) consumer.quad(matrices.peek(), quad, r, g, b, 1f, light, overlay);
    }
}
