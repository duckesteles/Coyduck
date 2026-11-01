package dev.coyduck.utils;

import dev.coyduck.modules.Shaders;
import meteordevelopment.meteorclient.mixininterface.IWorldRenderer;
import meteordevelopment.meteorclient.renderer.MeshRenderer;
import meteordevelopment.meteorclient.renderer.MeteorRenderPipelines;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.utils.OutlineRenderCommandQueue;
import meteordevelopment.meteorclient.utils.render.NoopImmediateVertexConsumerProvider;
import meteordevelopment.meteorclient.utils.render.NoopOutlineVertexConsumerProvider;
import meteordevelopment.meteorclient.utils.render.WrapperImmediateVertexConsumerProvider;
import meteordevelopment.meteorclient.utils.render.postprocess.EntityShader;
import meteordevelopment.meteorclient.utils.render.postprocess.OutlineUniforms;
import net.minecraft.client.render.command.RenderDispatcher;
import net.minecraft.client.render.entity.EntityRenderManager;
import net.minecraft.client.render.entity.state.EntityRenderState;
import net.minecraft.client.render.state.WorldRenderState;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.Vec3d;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public final class ShaderGlow extends EntityShader {
    private static ShaderGlow instance;

    private final OutlineRenderCommandQueue queue = new OutlineRenderCommandQueue();
    private RenderDispatcher dispatcher;

    private ShaderGlow() {
        super(MeteorRenderPipelines.POST_OUTLINE);
    }

    public static ShaderGlow get() {
        if (instance == null) instance = new ShaderGlow();
        return instance;
    }

    public static void resize(int width, int height) {
        if (instance != null) instance.onResized(width, height);
    }

    @Override
    protected boolean shouldDraw() {
        return Shaders.active() != null;
    }

    @Override
    public boolean shouldDraw(Entity entity) {
        return shouldDraw();
    }

    @Override
    protected void setupPass(MeshRenderer renderer) {
        Shaders module = Shaders.active();
        renderer.uniform("OutlineData", OutlineUniforms.write(module.glowWidth(), module.fillOpacity(), ShapeMode.Both.ordinal(), module.glowStrength()));
    }

    public void drawEntities(WorldRenderState world, MatrixStack matrices, Shaders module) {
        if (dispatcher == null) {
            dispatcher = new RenderDispatcher(queue, mc.getBlockRenderManager(), new WrapperImmediateVertexConsumerProvider(() -> vertexConsumerProvider), mc.getAtlasManager(),
                NoopOutlineVertexConsumerProvider.INSTANCE, NoopImmediateVertexConsumerProvider.INSTANCE, mc.textRenderer);
        }

        queue.setColor(module.maskColor());
        EntityRenderManager manager = mc.getEntityRenderDispatcher();
        Vec3d camera = world.cameraRenderState.pos;
        boolean empty = true;

        for (EntityRenderState state : world.entityRenderStates) {
            if (!module.shows(state)) continue;

            var renderer = manager.getRenderer(state);
            Vec3d offset = renderer.getPositionOffset(state);
            matrices.push();
            matrices.translate(state.x - camera.x + offset.x, state.y - camera.y + offset.y, state.z - camera.z + offset.z);
            renderer.render(state, matrices, queue, world.cameraRenderState);
            matrices.pop();
            empty = false;
        }

        if (empty) return;

        IWorldRenderer target = (IWorldRenderer) mc.worldRenderer;
        target.meteor$pushEntityOutlineFramebuffer(framebuffer);
        dispatcher.render();
        queue.onNextFrame();
        target.meteor$popEntityOutlineFramebuffer();
    }
}
