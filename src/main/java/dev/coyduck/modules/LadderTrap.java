package dev.coyduck.modules;

import dev.coyduck.Coyduck;
import dev.coyduck.events.PacketApplyEvent;
import dev.coyduck.managers.InteractionManager;
import dev.coyduck.managers.InventoryManager;
import dev.coyduck.managers.MiningManager;
import dev.coyduck.managers.MiningTracker;
import dev.coyduck.utils.BlockRules;
import dev.coyduck.utils.Targets;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongSet;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.LadderBlock;
import net.minecraft.block.ScaffoldingBlock;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Items;
import net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ChunkDeltaUpdateS2CPacket;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.util.Util;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

public class LadderTrap extends Module {
    public enum Mode {
        Smart,
        Always
    }

    public enum Item {
        Auto,
        Ladder,
        Scaffolding
    }

    private static final long BROKEN_TIME = 2_000;
    private static final long REJECT_TIME = 1_500;
    private static final long CONFIRM_TIME = 700;
    // The target's box on the server is a little off the one we track while they move, and a ladder is only 3/16 thick.
    private static final double BOX_MARGIN = 0.15;
    private static final double MOVING = 0.03;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Double> targetRange = sgGeneral.add(new DoubleSetting.Builder()
        .name("target-range")
        .description("Range to look for targets in.")
        .defaultValue(5.5)
        .range(1, 8)
        .sliderRange(1, 8)
        .build()
    );

    private final Setting<Mode> mode = sgGeneral.add(new EnumSetting.Builder<Mode>()
        .name("mode")
        .description("Smart only traps targets that are in a hole. Always traps them anywhere.")
        .defaultValue(Mode.Smart)
        .build()
    );

    private final Setting<Item> item = sgGeneral.add(new EnumSetting.Builder<Item>()
        .name("item")
        .description("Auto uses a ladder when one fits beside the target, otherwise scaffolding. Walking into a ladder climbs, so a target trying to step out rises out of the hole.")
        .defaultValue(Item.Auto)
        .build()
    );

    private final Setting<InventoryManager.SwitchMode> switchMode = sgGeneral.add(new EnumSetting.Builder<InventoryManager.SwitchMode>()
        .name("switch")
        .description("Silent selects the item's hotbar slot for a moment and sends no inventory clicks; an item in your inventory is first moved into an empty hotbar slot once. InvSwap swaps the item into your hand and back with inventory clicks, which some servers answer by resending the whole inventory. While you eat, InvSwap is always used. Your offhand totem is never touched.")
        .defaultValue(InventoryManager.SwitchMode.Silent)
        .build()
    );

    private final Setting<Boolean> rotate = sgGeneral.add(new BoolSetting.Builder()
        .name("rotate")
        .description("Turns your head toward each block before placing it and places only once the server sees you looking at it. Slower, but for servers whose anticheat checks where you look. Visible faces are always preferred.")
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
    private boolean running;
    private final Long2LongOpenHashMap placedAt = new Long2LongOpenHashMap();
    private final Long2LongOpenHashMap rejectedAt = new Long2LongOpenHashMap();

    public LadderTrap() {
        super(Coyduck.CATEGORY, "ladder-trap", "Puts a ladder or scaffolding in the cell the target stands in, so they climb instead of moving and float up out of their hole.");
    }

    @Override
    public void onDeactivate() {
        target = null;
    }

    @Override
    public String getInfoString() {
        return target == null ? null : target.getName().getString();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        pass();
    }

    @EventHandler
    private void onPacket(PacketApplyEvent.Post event) {
        if (event.packet instanceof BlockUpdateS2CPacket packet && packet.getState().isAir()) noteReverted(packet.getPos());
        if (target == null) return;

        boolean relevant = switch (event.packet) {
            case BlockUpdateS2CPacket packet -> packet.getPos().isWithinDistance(target.getBlockPos(), 2);
            case ChunkDeltaUpdateS2CPacket packet -> Targets.touches(packet, target.getBlockPos(), 2);
            default -> false;
        };

        if (relevant) pass();
    }

    /** A ladder we just placed that the server turns back into air was refused; leave that cell alone for a while. */
    private void noteReverted(BlockPos pos) {
        long key = pos.asLong();
        long now = Util.getMeasuringTimeMs();
        if (now - placedAt.getOrDefault(key, 0L) < CONFIRM_TIME) rejectedAt.put(key, now);
    }

    private void pass() {
        if (running || !canAct()) return;

        running = true;
        try {
            run();
        } finally {
            running = false;
        }
    }

    private void run() {
        if (InventoryManager.get().isOffhandEating()) return;
        target = Targets.find(targetRange.get());
        if (target == null) return;

        Box box = Targets.box(target);
        LongSet columns = Targets.columns(box);
        if (mode.get() == Mode.Smart && !Targets.isHoled(columns)) return;

        InteractionManager interaction = InteractionManager.get();

        for (long key : columns) {
            BlockPos cell = BlockPos.fromLong(key);
            BlockState state = mc.world.getBlockState(cell);
            if (state.isIn(BlockTags.CLIMBABLE)) continue;
            if (!interaction.canPlace(cell) || mc.player.getBoundingBox().intersects(new Box(cell))) continue;
            if (Util.getMeasuringTimeMs() - rejectedAt.getOrDefault(cell.asLong(), 0L) < REJECT_TIME) continue;

            if (item.get() != Item.Scaffolding && placeLadder(cell, box)) continue;
            if (item.get() != Item.Ladder) placeScaffolding(cell);
        }
    }

    private boolean placeLadder(BlockPos cell, Box box) {
        int slot = InventoryManager.get().find(stack -> stack.isOf(Items.LADDER), 0, InventoryManager.MAIN_END);
        if (slot == -1) return false;

        Direction best = null;
        double bestDistance = -1;
        Vec3d eye = mc.player.getEyePos();

        for (Direction side : Direction.Type.HORIZONTAL) {
            BlockPos wall = cell.offset(side.getOpposite());
            if (!usableWall(wall, side)) continue;

            BlockState ladder = Blocks.LADDER.getDefaultState().with(LadderBlock.FACING, side);
            if (blocked(ladder, cell, box)) continue;
            if (new Box(wall).squaredMagnitude(eye) >= reach() * reach()) continue;
            Vec3d face = Vec3d.ofCenter(wall).add(side.getOffsetX() * 0.5, 0, side.getOffsetZ() * 0.5);
            if (eye.subtract(face).dotProduct(Vec3d.of(side.getVector())) <= 0) continue;

            double distance = eye.squaredDistanceTo(Vec3d.ofCenter(wall));
            if (distance > bestDistance) {
                best = side;
                bestDistance = distance;
            }
        }

        if (best == null) return false;

        BlockPos wall = cell.offset(best.getOpposite());
        Vec3d point = Vec3d.ofCenter(wall).add(best.getOffsetX() * 0.5, 0, best.getOffsetZ() * 0.5);
        BlockHitResult hit = new BlockHitResult(point, best, wall, false);
        if (rotate.get() && !InteractionManager.get().aimed(point, new Box(wall), InteractionManager.TRAP_PRIORITY)) return true;

        InventoryManager.get().withItem(slot, switchMode.get(), () -> InteractionManager.get().place(cell, hit, swing.get(), rotate.get(), InteractionManager.TRAP_PRIORITY));
        long now = Util.getMeasuringTimeMs();
        placedAt.long2LongEntrySet().removeIf(entry -> now - entry.getLongValue() > CONFIRM_TIME);
        rejectedAt.long2LongEntrySet().removeIf(entry -> now - entry.getLongValue() > REJECT_TIME);
        placedAt.put(cell.asLong(), now);
        return mc.world.getBlockState(cell).isOf(Blocks.LADDER);
    }

    private void placeScaffolding(BlockPos cell) {
        int slot = InventoryManager.get().find(stack -> stack.isOf(Items.SCAFFOLDING), 0, InventoryManager.MAIN_END);
        if (slot == -1 || !InteractionManager.get().canPlace(cell) || mc.player.getEyeY() <= cell.getY()) return;

        BlockPos floor = cell.down();
        BlockState floorState = mc.world.getBlockState(floor);
        if (floorState.isReplaceable() || floorState.isOf(Blocks.SCAFFOLDING) || BlockRules.isInteractable(floorState.getBlock())) return;
        if (ScaffoldingBlock.calculateDistance(mc.world, cell) >= ScaffoldingBlock.MAX_DISTANCE) return;
        if (mc.player.getY() > cell.getY() + 1 - 1.0E-5 && !mc.player.isSneaking()) return;
        if (new Box(floor).squaredMagnitude(mc.player.getEyePos()) >= reach() * reach()) return;

        BlockHitResult hit = new BlockHitResult(Vec3d.ofCenter(floor).add(0, 0.5, 0), Direction.UP, floor, false);
        if (rotate.get() && !InteractionManager.get().aimed(hit.getPos(), new Box(floor), InteractionManager.TRAP_PRIORITY)) return;
        InventoryManager.get().withItem(slot, switchMode.get(), () -> InteractionManager.get().place(cell, hit, swing.get(), rotate.get(), InteractionManager.TRAP_PRIORITY));
    }

    private boolean usableWall(BlockPos wall, Direction side) {
        BlockState state = mc.world.getBlockState(wall);
        if (!state.isSideSolidFullSquare(mc.world, wall, side) || BlockRules.isInteractable(state.getBlock())) return false;
        if (MiningManager.get().isMining(wall) || wall.equals(MiningManager.get().background())) return false;
        return MiningTracker.get().mining(wall) == null && !MiningTracker.get().wasBroken(wall, BROKEN_TIME);
    }

    private boolean blocked(BlockState state, BlockPos cell, Box box) {
        Box shape = state.getCollisionShape(mc.world, cell).offset(cell.getX(), cell.getY(), cell.getZ()).getBoundingBox();
        // The margin covers a moving target's lag, but a centred target leaves only 1/80 of a block beside a ladder.
        double margin = target.getEntityPos().subtract(target.getLastRenderPos()).horizontalLengthSquared() > MOVING * MOVING ? BOX_MARGIN : 0;
        if (shape.intersects(box.expand(margin, 0, margin))) return true;
        return !mc.world.getOtherEntities(target, shape, entity -> !entity.isRemoved() && entity.intersectionChecked).isEmpty();
    }

    private double reach() {
        return mc.player.getBlockInteractionRange() + 1.0 - 0.05;
    }

    private boolean canAct() {
        return mc.player != null && mc.world != null && mc.interactionManager != null && mc.getNetworkHandler() != null
            && mc.player.isAlive() && !mc.player.isSpectator();
    }
}
