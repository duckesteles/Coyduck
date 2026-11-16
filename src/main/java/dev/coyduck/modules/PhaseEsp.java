package dev.coyduck.modules;

import dev.coyduck.events.RenderWorldEvent;
import dev.coyduck.feature.BoolSetting;
import dev.coyduck.feature.ColorSetting;
import dev.coyduck.feature.EnumSetting;
import dev.coyduck.feature.Feature;
import dev.coyduck.feature.Rgba;
import dev.coyduck.feature.Setting;
import dev.coyduck.feature.SettingGroup;
import dev.coyduck.render.Argb;
import dev.coyduck.render.DuckRender;
import dev.coyduck.render.Shape;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.shape.VoxelShape;

public class PhaseEsp extends Feature {
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

    private final Setting<Shape> shapeMode = sgGeneral.add(new EnumSetting.Builder<Shape>()
        .name("shape-mode")
        .description("How the walls are drawn.")
        .defaultValue(Shape.Both)
        .build()
    );

    private final ColorSetting safeColor = sgGeneral.add(new ColorSetting.Builder()
        .name("bedrock-color")
        .description("Bedrock with bedrock under it, nothing can break you out.")
        .defaultValue(new Rgba(90, 220, 120, 45))
        .build()
    );

    private final ColorSetting semiColor = sgGeneral.add(new ColorSetting.Builder()
        .name("obsidian-color")
        .description("Minable blast proof wall on a blast proof floor.")
        .defaultValue(new Rgba(240, 200, 80, 45))
        .build()
    );

    private final ColorSetting unsafeColor = sgGeneral.add(new ColorSetting.Builder()
        .name("unsafe-color")
        .description("Blast proof wall over a weak or missing floor.")
        .defaultValue(new Rgba(230, 80, 90, 45))
        .build()
    );

    private final BlockPos.Mutable cursor = new BlockPos.Mutable();

    public PhaseEsp() {
        super("phase-esp", "Shows walls you can phase into.");
    }

    @EventHandler
    private void onRender(RenderWorldEvent event) {
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

                int color = color(cursor, state);
                DuckRender.box(event.canvas, box, color, Argb.alpha(color, Argb.a(color) * 4), shapeMode.get(), 1);
            }
        }
    }

    private int color(BlockPos pos, BlockState state) {
        BlockState floor = mc.world.getBlockState(pos.down());
        if (floor.getCollisionShape(mc.world, pos.down()).isEmpty() || floor.getBlock().getBlastResistance() < SAFE_RESISTANCE) return unsafeColor.argb();
        return state.isOf(Blocks.BEDROCK) && floor.isOf(Blocks.BEDROCK) ? safeColor.argb() : semiColor.argb();
    }

    private boolean inside(Box player) {
        BlockPos feet = BlockPos.ofFloored(player.getCenter().x, player.minY + 0.01, player.getCenter().z);
        return !mc.world.getBlockState(feet).getCollisionShape(mc.world, feet).isEmpty();
    }
}
