package dev.coyduck.modules;

import dev.coyduck.Coyduck;
import dev.coyduck.utils.DuckRender;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.shape.VoxelShape;

public class PhaseEsp extends Module {
    private static final double SAFE_RESISTANCE = 600;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> whileInside = sgGeneral.add(new BoolSetting.Builder()
        .name("while-inside")
        .description("Keeps showing walls while you are already inside a block.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> whileCrawling = sgGeneral.add(new BoolSetting.Builder()
        .name("while-crawling")
        .description("Keeps showing walls while you crawl.")
        .defaultValue(true)
        .build()
    );

    private final Setting<ShapeMode> shapeMode = sgGeneral.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .description("How the walls are drawn.")
        .defaultValue(ShapeMode.Both)
        .build()
    );

    private final Setting<SettingColor> safeColor = sgGeneral.add(new ColorSetting.Builder()
        .name("bedrock-color")
        .description("Bedrock with bedrock under it, nothing can break you out.")
        .defaultValue(new SettingColor(90, 220, 120, 45))
        .build()
    );

    private final Setting<SettingColor> semiColor = sgGeneral.add(new ColorSetting.Builder()
        .name("obsidian-color")
        .description("Blast proof but minable, like obsidian, on a blast proof floor.")
        .defaultValue(new SettingColor(240, 200, 80, 45))
        .build()
    );

    private final Setting<SettingColor> unsafeColor = sgGeneral.add(new ColorSetting.Builder()
        .name("unsafe-color")
        .description("Blast proof wall over a weak or missing floor.")
        .defaultValue(new SettingColor(230, 80, 90, 45))
        .build()
    );

    private final BlockPos.Mutable cursor = new BlockPos.Mutable();
    private final Color line = new Color();

    public PhaseEsp() {
        super(Coyduck.CATEGORY, "phase-esp", "Shows walls you can phase into.");
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (mc.player == null || mc.world == null) return;
        Box player = mc.player.getBoundingBox();
        if (!whileCrawling.get() && player.getLengthY() < 1) return;
        if (!whileInside.get() && inside(player)) return;

        int y = (int) Math.round(player.minY);
        Box search = new Box(player.minX - 0.01, y, player.minZ - 0.01, player.maxX + 0.01, y + 0.5, player.maxZ + 0.01);
        for (int x = MathHelper.floor(search.minX); x <= MathHelper.floor(search.maxX); x++) {
            for (int z = MathHelper.floor(search.minZ); z <= MathHelper.floor(search.maxZ); z++) {
                cursor.set(x, y, z);
                BlockState state = mc.world.getBlockState(cursor);
                if (state.isReplaceable() || state.getBlock().getBlastResistance() < SAFE_RESISTANCE) continue;
                VoxelShape shape = state.getCollisionShape(mc.world, cursor);
                if (shape.isEmpty()) continue;
                Box box = shape.getBoundingBox().offset(cursor);
                if (player.intersects(box.contract(0.05))) continue;

                SettingColor color = color(cursor, state);
                line.set(color.r, color.g, color.b, Math.min(255, color.a * 4));
                DuckRender.box(event.renderer, box, color, line, shapeMode.get(), 1);
            }
        }
    }

    private SettingColor color(BlockPos pos, BlockState state) {
        BlockState floor = mc.world.getBlockState(pos.down());
        if (floor.getCollisionShape(mc.world, pos.down()).isEmpty() || floor.getBlock().getBlastResistance() < SAFE_RESISTANCE) return unsafeColor.get();
        return state.isOf(Blocks.BEDROCK) && floor.isOf(Blocks.BEDROCK) ? safeColor.get() : semiColor.get();
    }

    private boolean inside(Box player) {
        BlockPos feet = BlockPos.ofFloored(player.getCenter().x, player.minY + 0.01, player.getCenter().z);
        return !mc.world.getBlockState(feet).getCollisionShape(mc.world, feet).isEmpty();
    }
}
