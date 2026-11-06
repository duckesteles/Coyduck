package dev.coyduck.modules;

import dev.coyduck.Coyduck;
import dev.coyduck.managers.InteractionManager;
import dev.coyduck.managers.InventoryManager;
import dev.coyduck.managers.LatencyManager;
import dev.coyduck.managers.MotionTracker;
import dev.coyduck.utils.Targets;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BlockListSetting;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class HoleFill extends Module {
    private static final float STRONG = 600;
    private static final int MAX_PREDICT_TICKS = 8;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Double> targetRange = sgGeneral.add(new DoubleSetting.Builder()
        .name("target-range")
        .description("Range to look for targets in.")
        .defaultValue(7)
        .range(1, 12)
        .sliderRange(1, 12)
        .build()
    );

    private final Setting<Double> fillRange = sgGeneral.add(new DoubleSetting.Builder()
        .name("fill-range")
        .description("How close a hole must be to where the target is heading to get filled.")
        .defaultValue(2.5)
        .range(0.5, 5)
        .sliderRange(0.5, 5)
        .build()
    );

    private final Setting<List<Block>> blocks = sgGeneral.add(new BlockListSetting.Builder()
        .name("blocks")
        .description("Blocks used to fill holes, in order of preference.")
        .defaultValue(Blocks.OBSIDIAN, Blocks.CRYING_OBSIDIAN)
        .build()
    );

    private final Setting<Integer> perTick = sgGeneral.add(new IntSetting.Builder()
        .name("blocks-per-tick")
        .description("Most holes filled in one tick.")
        .defaultValue(2)
        .range(1, 6)
        .sliderRange(1, 6)
        .build()
    );

    private final Setting<Boolean> doubles = sgGeneral.add(new BoolSetting.Builder()
        .name("doubles")
        .description("Also fills both cells of two-wide holes.")
        .defaultValue(true)
        .build()
    );

    private final Setting<InventoryManager.SwitchMode> switchMode = sgGeneral.add(new EnumSetting.Builder<InventoryManager.SwitchMode>()
        .name("switch")
        .description("How the block is switched to. Your offhand totem is never touched.")
        .defaultValue(InventoryManager.SwitchMode.Silent)
        .build()
    );

    private final Setting<Boolean> rotate = sgGeneral.add(new BoolSetting.Builder()
        .name("rotate")
        .description("Turns your head toward each block before placing it.")
        .defaultValue(true)
        .build()
    );

    private final Setting<InteractionManager.SwingMode> swing = sgGeneral.add(new EnumSetting.Builder<InteractionManager.SwingMode>()
        .name("swing")
        .description("How the hand swing is shown.")
        .defaultValue(InteractionManager.SwingMode.Packet)
        .build()
    );

    private PlayerEntity target;

    public HoleFill() {
        super(Coyduck.CATEGORY, "hole-fill", "Fills the holes near where an enemy out in the open is heading, so they have nowhere safe to go.");
    }

    @Override
    public String getInfoString() {
        return target == null ? null : target.getName().getString();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null || !mc.player.isAlive()) return;
        if (InventoryManager.get().isOffhandEating()) return;

        target = Targets.find(targetRange.get());
        if (target == null || Targets.isHoled(Targets.columns(Targets.box(target)))) return;

        int slot = blockSlot();
        if (slot == -1) return;

        int ticks = Math.min(MathHelper.ceil(LatencyManager.get().rtt() / 50.0) + 2, MAX_PREDICT_TICKS);
        Box predicted = MotionTracker.get().predict(target, ticks);
        Vec3d heading = new Vec3d((predicted.minX + predicted.maxX) / 2, predicted.minY, (predicted.minZ + predicted.maxZ) / 2);

        InteractionManager interaction = InteractionManager.get();
        List<BlockPos> holes = holes(heading);
        List<BlockPos> planned = new ArrayList<>();
        for (BlockPos hole : holes) {
            if (planned.size() >= perTick.get()) break;
            if (!interaction.canPlace(hole) || !interaction.obstructions(hole).isEmpty()) continue;
            if (interaction.hit(hole, false) == null) continue;
            planned.add(hole);
        }
        if (planned.isEmpty() || !interaction.ready(planned.get(0), false, rotate.get(), InteractionManager.TRAP_PRIORITY)) return;

        InventoryManager.get().withItem(slot, switchMode.get(), () -> {
            for (BlockPos pos : planned) {
                if (!isBlock(mc.player.getMainHandStack().getItem() instanceof BlockItem item ? item.getBlock() : null)) break;
                BlockHitResult hit = interaction.hit(pos, false);
                if (hit == null) continue;
                if (!interaction.place(pos, hit, swing.get(), rotate.get(), InteractionManager.TRAP_PRIORITY)) break;
            }
        });
    }

    private List<BlockPos> holes(Vec3d heading) {
        List<BlockPos> holes = new ArrayList<>();
        int radius = MathHelper.ceil(fillRange.get());
        BlockPos center = BlockPos.ofFloored(heading);
        Box self = mc.player.getBoundingBox().expand(0.1);
        Box enemy = target.getBoundingBox();
        double range = fillRange.get() * fillRange.get();

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = -1; dy <= 1; dy++) {
                    BlockPos pos = center.add(dx, dy, dz);
                    if (Vec3d.ofBottomCenter(pos).squaredDistanceTo(heading) > range) continue;
                    if (self.intersects(new Box(pos)) || enemy.intersects(new Box(pos))) continue;
                    if (isHole(pos, null) || doubles.get() && isDouble(pos)) holes.add(pos);
                }
            }
        }

        holes.sort(Comparator.comparingDouble(pos -> Vec3d.ofBottomCenter(pos).squaredDistanceTo(heading)));
        return holes;
    }

    private boolean isHole(BlockPos pos, Direction open) {
        if (!air(pos) || !air(pos.up()) || !air(pos.up(2)) || !strong(pos.down())) return false;
        for (Direction direction : Direction.Type.HORIZONTAL) {
            if (direction == open) continue;
            if (!strong(pos.offset(direction))) return false;
        }
        return true;
    }

    private boolean isDouble(BlockPos pos) {
        for (Direction direction : Direction.Type.HORIZONTAL) {
            BlockPos other = pos.offset(direction);
            if (isHole(pos, direction) && isHole(other, direction.getOpposite())) return true;
        }
        return false;
    }

    private boolean air(BlockPos pos) {
        return mc.world.getBlockState(pos).isReplaceable();
    }

    private boolean strong(BlockPos pos) {
        BlockState state = mc.world.getBlockState(pos);
        return !state.isReplaceable() && state.getBlock().getBlastResistance() >= STRONG;
    }

    private int blockSlot() {
        for (Block block : blocks.get()) {
            int slot = InventoryManager.get().find(stack -> stack.getItem() instanceof BlockItem item && item.getBlock() == block, 0, InventoryManager.MAIN_END);
            if (slot != -1) return slot;
        }
        return -1;
    }

    private boolean isBlock(Block block) {
        return block != null && blocks.get().contains(block);
    }
}
