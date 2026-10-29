package dev.coyduck.utils;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import dev.coyduck.mixin.RenderLayerAccessor;
import dev.coyduck.mixin.RenderSetupAccessor;
import net.minecraft.block.BlockState;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderSetup;
import net.minecraft.client.render.block.MovingBlockRenderState;
import net.minecraft.client.render.command.ModelCommandRenderer;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.command.RenderCommandQueue;
import net.minecraft.client.render.entity.state.EntityRenderState;
import net.minecraft.client.render.item.ItemRenderState;
import net.minecraft.client.render.model.BakedQuad;
import net.minecraft.client.render.model.BlockStateModel;
import net.minecraft.client.render.state.CameraRenderState;
import net.minecraft.client.texture.Sprite;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.ItemDisplayContext;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;
import org.joml.Quaternionf;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Passes a player's render commands on with every layer swapped for a copy whose depth is pulled far toward the
 * camera. The whole model then wins against walls, but its own parts still sort against each other, so it looks like
 * the real player standing in front of the blocks, not a flat silhouette.
 */
public final class WallQueue implements OrderedRenderCommandQueue {
    // GL polygon offset units; enough to clear any wall in render distance while staying above the near plane.
    private static final float BIAS_SCALE = 1.0f;
    private static final float BIAS_CONSTANT = -1_100_000.0f;

    // Render thread only.
    private static final Map<RenderLayer, RenderLayer> LAYERS = new IdentityHashMap<>();
    private static final Map<RenderPipeline, RenderPipeline> PIPELINES = new IdentityHashMap<>();

    public static final int KEEP = Integer.MIN_VALUE;

    private final RenderCommandQueue delegate;
    private final boolean walls;
    private final int color;
    private final int light;

    public WallQueue(RenderCommandQueue delegate) {
        this(delegate, true, KEEP, KEEP);
    }

    /** color is an ARGB tint and light a packed light value; KEEP leaves the model's own. */
    public WallQueue(RenderCommandQueue delegate, boolean walls, int color, int light) {
        this.delegate = delegate;
        this.walls = walls;
        this.color = color;
        this.light = light;
    }

    private RenderLayer through(RenderLayer layer) {
        return layer == null || !walls ? layer : LAYERS.computeIfAbsent(layer, WallQueue::copy);
    }

    private int color(int own) {
        return color == KEEP ? own : color;
    }

    private int light(int own) {
        return light == KEEP ? own : light;
    }

    private static RenderLayer copy(RenderLayer layer) {
        RenderLayerAccessor access = (RenderLayerAccessor) layer;
        RenderSetupAccessor setup = (RenderSetupAccessor) (Object) access.coyduck$getRenderSetup();
        RenderPipeline pipeline = PIPELINES.computeIfAbsent(setup.coyduck$getPipeline(), WallQueue::biased);
        RenderSetup copy = RenderSetupAccessor.coyduck$create(pipeline, setup.coyduck$getTextures(), setup.coyduck$getUseLightmap(), setup.coyduck$getUseOverlay(),
            setup.coyduck$getLayeringTransform(), setup.coyduck$getOutputTarget(), setup.coyduck$getTextureTransform(), setup.coyduck$getOutlineMode(),
            setup.coyduck$getHasCrumbling(), setup.coyduck$getTranslucent(), setup.coyduck$getExpectedBufferSize());
        return RenderLayerAccessor.coyduck$of(access.coyduck$getName() + "_coyduck_walls", copy);
    }

    private static RenderPipeline biased(RenderPipeline p) {
        Identifier location = Identifier.of("coyduck", "walls/" + p.getLocation().getNamespace() + "/" + p.getLocation().getPath());
        return new RenderPipeline(location, p.getVertexShader(), p.getFragmentShader(), p.getShaderDefines(), p.getSamplers(), p.getUniforms(), p.getBlendFunction(),
            p.getDepthTestFunction(), p.getPolygonMode(), p.isCull(), p.isWriteColor(), p.isWriteAlpha(), p.isWriteDepth(), p.getColorLogic(),
            p.getVertexFormat(), p.getVertexFormatMode(), BIAS_SCALE, BIAS_CONSTANT, p.getSortKey()) {};
    }

    @Override
    public RenderCommandQueue getBatchingQueue(int order) {
        return delegate instanceof OrderedRenderCommandQueue ordered ? new WallQueue(ordered.getBatchingQueue(order), walls, color, light) : this;
    }

    @Override
    public void submitShadowPieces(MatrixStack matrices, float radius, List<EntityRenderState.ShadowPiece> pieces) {
        delegate.submitShadowPieces(matrices, radius, pieces);
    }

    @Override
    public void submitLabel(MatrixStack matrices, Vec3d pos, int y, Text text, boolean notSneaking, int light, double distance, CameraRenderState camera) {
        delegate.submitLabel(matrices, pos, y, text, notSneaking, light, distance, camera);
    }

    @Override
    public void submitText(MatrixStack matrices, float x, float y, OrderedText text, boolean shadow, TextRenderer.TextLayerType layerType, int light, int color, int background, int outline) {
        delegate.submitText(matrices, x, y, text, shadow, layerType, light, color, background, outline);
    }

    @Override
    public void submitFire(MatrixStack matrices, EntityRenderState state, Quaternionf rotation) {
        delegate.submitFire(matrices, state, rotation);
    }

    @Override
    public void submitLeash(MatrixStack matrices, EntityRenderState.LeashData leash) {
        delegate.submitLeash(matrices, leash);
    }

    @Override
    public <S> void submitModel(Model<? super S> model, S state, MatrixStack matrices, RenderLayer layer, int light, int overlay, int color, Sprite sprite, int outlineColor, ModelCommandRenderer.CrumblingOverlayCommand crumbling) {
        delegate.submitModel(model, state, matrices, through(layer), light(light), overlay, color(color), sprite, outlineColor, crumbling);
    }

    @Override
    public void submitModelPart(ModelPart part, MatrixStack matrices, RenderLayer layer, int light, int overlay, Sprite sprite, boolean sheeted, boolean hasGlint, int color, ModelCommandRenderer.CrumblingOverlayCommand crumbling, int outlineColor) {
        delegate.submitModelPart(part, matrices, through(layer), light(light), overlay, sprite, sheeted, hasGlint, color(color), crumbling, outlineColor);
    }

    @Override
    public void submitBlock(MatrixStack matrices, BlockState state, int light, int overlay, int outlineColor) {
        delegate.submitBlock(matrices, state, light, overlay, outlineColor);
    }

    @Override
    public void submitMovingBlock(MatrixStack matrices, MovingBlockRenderState state) {
        delegate.submitMovingBlock(matrices, state);
    }

    @Override
    public void submitBlockStateModel(MatrixStack matrices, RenderLayer layer, BlockStateModel model, float r, float g, float b, int light, int overlay, int outlineColor) {
        delegate.submitBlockStateModel(matrices, through(layer), model, r, g, b, light, overlay, outlineColor);
    }

    @Override
    public void submitItem(MatrixStack matrices, ItemDisplayContext context, int light, int overlay, int outlineColor, int[] tints, List<BakedQuad> quads, RenderLayer layer, ItemRenderState.Glint glint) {
        delegate.submitItem(matrices, context, light, overlay, outlineColor, tints, quads, through(layer), glint);
    }

    @Override
    public void submitCustom(MatrixStack matrices, RenderLayer layer, OrderedRenderCommandQueue.Custom custom) {
        delegate.submitCustom(matrices, through(layer), custom);
    }

    @Override
    public void submitCustom(OrderedRenderCommandQueue.LayeredCustom custom) {
        delegate.submitCustom(custom);
    }
}
