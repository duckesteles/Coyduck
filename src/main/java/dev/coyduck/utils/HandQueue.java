package dev.coyduck.utils;

import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.command.RenderCommandQueue;
import net.minecraft.client.render.item.ItemRenderState;
import net.minecraft.client.render.model.BakedQuad;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.ItemDisplayContext;

import java.util.List;

public final class HandQueue extends WallQueue {
    private static final float[][] SHELLS = {{1.0f, 0.42f}, {1.03f, 0.26f}, {1.06f, 0.14f}, {1.09f, 0.07f}};

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

    @Override
    public void submitItem(MatrixStack matrices, ItemDisplayContext context, int light, int overlay, int outlineColor, int[] tints, List<BakedQuad> quads, RenderLayer layer, ItemRenderState.Glint glint) {
        delegate.submitItem(matrices, context, light, overlay, outlineColor, tints, quads, layer, glint);
        if (quads.isEmpty()) return;

        RenderLayer glow = RenderLayers.entityTranslucentEmissive(quads.getFirst().sprite().getAtlasId());
        float r = ((color >> 16) & 0xFF) / 255f;
        float g = ((color >> 8) & 0xFF) / 255f;
        float b = (color & 0xFF) / 255f;
        for (float[] shell : SHELLS) {
            float alpha = shell[1];
            matrices.push();
            matrices.translate(0.5f, 0.5f, 0.5f);
            matrices.scale(shell[0], shell[0], shell[0]);
            matrices.translate(-0.5f, -0.5f, -0.5f);
            delegate.submitCustom(matrices, glow, (entry, consumer) -> {
                for (BakedQuad quad : quads) consumer.quad(entry, quad, r, g, b, alpha, LightmapTextureManager.MAX_LIGHT_COORDINATE, OverlayTexture.DEFAULT_UV);
            });
            matrices.pop();
        }
    }
}
