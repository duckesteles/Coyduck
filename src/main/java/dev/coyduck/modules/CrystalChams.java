package dev.coyduck.modules;

import dev.coyduck.MeteorColors;
import dev.coyduck.feature.BoolSetting;
import dev.coyduck.feature.ColorSetting;
import dev.coyduck.feature.EnumSetting;
import dev.coyduck.feature.Feature;
import dev.coyduck.feature.Features;
import dev.coyduck.feature.Rgba;
import dev.coyduck.feature.Setting;
import dev.coyduck.feature.SettingGroup;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.renderer.Renderer3D;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.render.entity.EndCrystalEntityRenderer;
import net.minecraft.client.render.entity.model.EndCrystalEntityModel;
import net.minecraft.client.render.entity.state.EndCrystalEntityRenderState;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.EndCrystalEntity;
import org.joml.Matrix4f;
import org.joml.Vector3f;

public class CrystalChams extends Feature {
    private static final int[][] FACES = {
        {0, 2, 6, 4}, {1, 3, 7, 5},
        {0, 1, 5, 4}, {2, 3, 7, 6},
        {0, 1, 3, 2}, {4, 5, 7, 6}
    };

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<ShapeMode> shapeMode = sgGeneral.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .description("Draws the edges, the faces or both.")
        .defaultValue(ShapeMode.Both)
        .build()
    );

    private final Setting<Rgba> lineColor = sgGeneral.add(new ColorSetting.Builder()
        .name("line-color")
        .description("Colour of the cube edges.")
        .defaultValue(new Rgba(255, 255, 255, 255))
        .build()
    );

    private final Setting<Rgba> sideColor = sgGeneral.add(new ColorSetting.Builder()
        .name("side-color")
        .description("Colour of the cube faces.")
        .defaultValue(new Rgba(255, 255, 255, 30))
        .build()
    );

    private final Setting<Boolean> throughWalls = sgGeneral.add(new BoolSetting.Builder()
        .name("through-walls")
        .description("Shows crystals through the blocks that hide them.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> hideModel = sgGeneral.add(new BoolSetting.Builder()
        .name("hide-model")
        .description("Hides the textured crystal so only the chams are left.")
        .defaultValue(true)
        .build()
    );

    private static final double MAX_DISTANCE = 64;

    private final Vector3f[] corners = new Vector3f[8];
    private final EndCrystalEntityRenderState state = new EndCrystalEntityRenderState();
    private final MatrixStack matrices = new MatrixStack();
    private Color side;
    private Color line;
    private boolean sides;
    private boolean lines;

    public CrystalChams() {
        super("crystal-chams", "Wireframe end crystals.");
        for (int i = 0; i < corners.length; i++) corners[i] = new Vector3f();
    }

    public interface ModelHolder {
        EndCrystalEntityModel coyduck$model();
    }

    public static boolean hidesModel() {
        CrystalChams module = Features.get(CrystalChams.class);
        return module != null && module.isActive() && module.hideModel.get();
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (mc.world == null) return;
        Renderer3D renderer = throughWalls.get() ? event.renderer : event.depthRenderer;
        side = Shaders.tint(MeteorColors.of(sideColor));
        line = Shaders.lightTint(MeteorColors.of(lineColor));
        sides = shapeMode.get().sides();
        lines = shapeMode.get().lines();
        double range = MAX_DISTANCE * MAX_DISTANCE;
        for (Entity entity : mc.world.getEntities()) {
            if (entity instanceof EndCrystalEntity crystal && (mc.player == null || mc.player.squaredDistanceTo(crystal) <= range)) draw(crystal, event.tickDelta, renderer);
        }
    }

    private void draw(EndCrystalEntity crystal, float tickDelta, Renderer3D renderer) {
        if (!(mc.getEntityRenderDispatcher().getRenderer(crystal) instanceof EndCrystalEntityRenderer crystalRenderer)) return;
        EndCrystalEntityModel model = ((ModelHolder) crystalRenderer).coyduck$model();
        state.age = crystal.endCrystalAge + tickDelta;
        state.baseVisible = crystal.shouldShowBottom();
        model.setAngles(state);

        double x = crystal.getX();
        double y = crystal.getY();
        double z = crystal.getZ();
        matrices.loadIdentity();
        matrices.scale(2, 2, 2);
        matrices.translate(0, -0.5f, 0);
        model.getRootPart().forEachCuboid(matrices, (entry, path, index, cuboid) -> {
            if (!model.base.visible && path.contains("base")) return;
            cube(x, y, z, entry.getPositionMatrix(), cuboid, renderer);
        });
    }

    private void cube(double px, double py, double pz, Matrix4f matrix, ModelPart.Cuboid cuboid, Renderer3D renderer) {
        for (int i = 0; i < 8; i++) {
            float x = ((i & 1) == 0 ? cuboid.minX : cuboid.maxX) / 16;
            float y = ((i & 2) == 0 ? cuboid.minY : cuboid.maxY) / 16;
            float z = ((i & 4) == 0 ? cuboid.minZ : cuboid.maxZ) / 16;
            matrix.transformPosition(x, y, z, corners[i]);
        }

        if (sides) {
            for (int[] face : FACES) {
                Vector3f a = corners[face[0]], b = corners[face[1]], c = corners[face[2]], d = corners[face[3]];
                renderer.quad(px + a.x, py + a.y, pz + a.z, px + b.x, py + b.y, pz + b.z,
                    px + c.x, py + c.y, pz + c.z, px + d.x, py + d.y, pz + d.z, side);
            }
        }

        if (lines) {
            for (int i = 0; i < 8; i++) {
                for (int bit = 1; bit < 8; bit <<= 1) {
                    if ((i & bit) != 0) continue;
                    Vector3f a = corners[i], b = corners[i | bit];
                    renderer.line(px + a.x, py + a.y, pz + a.z, px + b.x, py + b.y, pz + b.z, line);
                }
            }
        }
    }
}
