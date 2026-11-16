package dev.coyduck.modules;

import dev.coyduck.events.PacketApplyEvent;
import dev.coyduck.events.TickEvent;
import dev.coyduck.events.WorldResetEvent;
import dev.coyduck.feature.BoolSetting;
import dev.coyduck.feature.DoubleSetting;
import dev.coyduck.feature.EnumSetting;
import dev.coyduck.feature.Feature;
import dev.coyduck.feature.IntSetting;
import dev.coyduck.feature.Setting;
import dev.coyduck.feature.SettingGroup;
import dev.coyduck.managers.InteractionManager;
import dev.coyduck.managers.InventoryManager;
import dev.coyduck.managers.LatencyManager;
import dev.coyduck.managers.MotionTracker;
import dev.coyduck.utils.BlockRules;
import dev.coyduck.utils.Targets;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Items;
import net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ChunkDeltaUpdateS2CPacket;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;
public class AutoWeb extends Feature {
    public enum Mode {
        Smart,
        Always
    }

    private static final double SELF_MARGIN = 0.1;
    private static final int MAX_PREDICT_TICKS = 7;
    private static final double MOVING_SPEED = 0.1;
    private static final double FLYING_SPEED = 0.8;
    private static final long REWEB_MS = 600;
    private static final double REACH_MARGIN = 0.05;

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
        .description("Smart webs only targets out of a hole.")
        .defaultValue(Mode.Always)
        .build()
    );

    private final Setting<Boolean> predict = sgGeneral.add(new BoolSetting.Builder()
        .name("predict")
        .description("Also webs the target's predicted position.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> websPerTick = sgGeneral.add(new IntSetting.Builder()
        .name("webs-per-tick")
        .description("Most webs placed in one tick.")
        .defaultValue(2)
        .range(1, 8)
        .sliderRange(1, 8)
        .build()
    );

    private final Setting<InventoryManager.SwitchMode> switchMode = sgGeneral.add(new EnumSetting.Builder<InventoryManager.SwitchMode>()
        .name("switch")
        .description("How the web gets into your hand.")
        .defaultValue(InventoryManager.SwitchMode.Silent)
        .build()
    );

    private final Setting<Boolean> airplace = sgGeneral.add(new BoolSetting.Builder()
        .name("airplace")
        .description("Places webs without a block to click on.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> rotate = sgGeneral.add(new BoolSetting.Builder()
        .name("rotate")
        .description("Faces each block before placing it.")
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
    private int budget;
    private boolean running;
    private LongSet lastCells = new LongOpenHashSet();

    public AutoWeb() {
        super("auto-web+", "Webs enemies.");
    }

    @Override
    public void onActivate() {
        Module meteor = Modules.get().get(meteordevelopment.meteorclient.systems.modules.combat.AutoWeb.class);
        if (meteor != null && meteor.isActive()) {
            meteor.toggle();
            info("Disabled %s, it webs the same target.", meteor.title);
        }

        reset();
        budget = websPerTick.get();
    }

    @Override
    public void onDeactivate() {
        reset();
    }

    @EventHandler
    private void onWorldReset(WorldResetEvent event) {
        reset();
    }

    private void reset() {
        target = null;
        lastCells = new LongOpenHashSet();
    }

    @Override
    public String getInfoString() {
        return target == null ? null : target.getName().getString();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        budget = websPerTick.get();
        pass();
    }

    @EventHandler
    private void onPacket(PacketApplyEvent.Post event) {
        if (target == null) return;

        switch (event.packet) {
            case BlockUpdateS2CPacket packet when packet.getState().isReplaceable() && lastCells.contains(packet.getPos().asLong()) -> pass();
            case ChunkDeltaUpdateS2CPacket packet -> {
                boolean[] found = {false};
                packet.visitUpdates((pos, state) -> found[0] |= state.isReplaceable() && lastCells.contains(pos.asLong()));
                if (found[0]) pass();
            }
            default -> {}
        }
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
        if (target != null && target.getEntityWorld() != mc.world) target = null;
        target = Targets.find(targetRange.get());
        if (target == null || budget <= 0) return;
        if (target.isGliding() || MotionTracker.get().velocity(target).horizontalLengthSquared() > FLYING_SPEED * FLYING_SPEED) return;

        int slot = InventoryManager.get().find(stack -> stack.isOf(Items.COBWEB), 0, InventoryManager.MAIN_END);
        if (slot == -1) return;

        Box box = Targets.box(target);
        LongSet columns = Targets.columns(box);
        boolean holed = Targets.isHoled(columns);
        if (mode.get() == Mode.Smart && holed) return;

        long feet = BlockPos.ofFloored(Targets.feet(box)).asLong();
        LongSet current = new LongLinkedOpenHashSet();
        for (long key : cells(box, holed)) {
            BlockPos cell = BlockPos.fromLong(key);
            if (BlockPos.unpackLongX(key) == BlockPos.unpackLongX(feet) && BlockPos.unpackLongZ(key) == BlockPos.unpackLongZ(feet) || !crystalSpot(cell)) current.add(key);
        }

        LongSet cells = new LongLinkedOpenHashSet();
        if (predict.get()) {
            int ticks = Math.min(MathHelper.ceil(LatencyManager.get().rtt() / 50.0) + 1, MAX_PREDICT_TICKS);
            Box ahead = MotionTracker.get().predict(target, ticks);
            boolean same = Targets.columns(ahead).equals(columns);
            LongSet predicted = new LongLinkedOpenHashSet();
            for (long key : cells(ahead, holed && same)) {
                if (current.contains(key) || !crystalSpot(BlockPos.fromLong(key))) predicted.add(key);
            }
            if (!Targets.isStill(target, MOVING_SPEED)) cells.addAll(predicted);
            cells.addAll(current);
            cells.addAll(predicted);
        } else {
            cells.addAll(current);
        }
        lastCells = cells;

        Box self = selfBox();
        Box body = mc.player.getBoundingBox();
        int headY = MathHelper.floor(body.maxY - 1.0E-4);
        InteractionManager interaction = InteractionManager.get();
        List<BlockPos> planned = new ArrayList<>();
        List<BlockHitResult> hits = new ArrayList<>();
        Surround surround = Modules.get().get(Surround.class);
        SelfTrap selfTrap = Modules.get().get(SelfTrap.class);
        boolean walled = surround != null && surround.isActive() || selfTrap != null && selfTrap.isActive();
        List<BlockPos> ownRing = walled ? Targets.ownRing() : List.of();

        for (long key : cells) {
            if (planned.size() >= budget) break;

            BlockPos pos = BlockPos.fromLong(key);
            if (!interaction.canPlace(pos) || mc.world.getBlockState(pos).isOf(Blocks.COBWEB) || ownRing.contains(pos)) continue;
            if (interaction.wasRejected(pos) || interaction.placedByUs(pos, REWEB_MS)) continue;
            if (pos.getY() > headY && pos.getX() + 1 > body.minX && pos.getX() < body.maxX && pos.getZ() + 1 > body.minZ && pos.getZ() < body.maxZ) continue;
            if (self.intersects(new Box(pos))) continue;
            BlockHitResult hit = webHit(pos);
            if (hit == null) continue;
            planned.add(pos);
            hits.add(hit);
        }

        if (planned.isEmpty() || !interaction.ready(planned.getFirst(), airplace.get(), rotate.get(), InteractionManager.TRAP_PRIORITY)) return;

        InventoryManager.get().withItem(slot, switchMode.get(), () -> {
            for (int i = 0; i < planned.size(); i++) {
                if (budget <= 0 || !mc.player.getMainHandStack().isOf(Items.COBWEB)) break;

                BlockHitResult hit = i == 0 ? hits.get(i) : webHit(planned.get(i));
                if (hit == null) continue;

                if (!interaction.place(planned.get(i), hit, swing.get(), rotate.get(), InteractionManager.TRAP_PRIORITY)) break;
                budget--;
            }
        });
    }

    private boolean crystalSpot(BlockPos cell) {
        return Targets.isBase(mc.world.getBlockState(cell.down())) && mc.world.getBlockState(cell).isAir();
    }

    private BlockHitResult webHit(BlockPos pos) {
        BlockPos below = pos.down();
        BlockState state = mc.world.getBlockState(below);
        if (!state.isReplaceable() && !BlockRules.isInteractable(state.getBlock()) && InteractionManager.centerReach(below)) {
            double reach = mc.player.getBlockInteractionRange() - REACH_MARGIN;
            Vec3d eye = mc.player.getEyePos();
            if (new Box(below).squaredMagnitude(eye) < reach * reach) {
                Vec3d point = InteractionManager.get().visiblePoint(below, Direction.UP, eye, reach * reach);
                if (point != null) return new BlockHitResult(point, Direction.UP, below, false);
            }
        }
        return InteractionManager.get().hit(pos, airplace.get());
    }

    private LongSet cells(Box box, boolean head) {
        LongSet cells = new LongLinkedOpenHashSet();
        int bottom = MathHelper.floor(box.minY);
        int top = MathHelper.floor(box.maxY - 1.0E-4);
        if (head) bottom = top;

        InteractionManager interaction = InteractionManager.get();
        for (int x = MathHelper.floor(box.minX); x <= MathHelper.floor(box.maxX - 1.0E-4); x++) {
            for (int z = MathHelper.floor(box.minZ); z <= MathHelper.floor(box.maxZ - 1.0E-4); z++) {
                for (int y = bottom; y <= top; y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState state = mc.world.getBlockState(pos);
                    if (state.isReplaceable() || state.isOf(Blocks.COBWEB)) {
                        if (!state.isOf(Blocks.COBWEB) && (washed(pos) || interaction.wasRejected(pos))) continue;
                        cells.add(pos.asLong());
                        break;
                    }

                    if (Targets.isBurrowedIn(box, pos)) break;
                }
            }
        }

        return cells;
    }

    private boolean washed(BlockPos pos) {
        if (!mc.world.getFluidState(pos).isEmpty() || !mc.world.getFluidState(pos.up()).isEmpty()) return true;
        for (Direction direction : Direction.Type.HORIZONTAL) {
            if (!mc.world.getFluidState(pos.offset(direction)).isEmpty()) return true;
        }
        return false;
    }

    private Box selfBox() {
        Box box = mc.player.getBoundingBox();
        return box.union(box.offset(mc.player.getVelocity().multiply(2))).expand(SELF_MARGIN);
    }

    private boolean canAct() {
        return mc.player != null && mc.world != null && mc.interactionManager != null && mc.getNetworkHandler() != null
            && mc.player.isAlive() && !mc.player.isSpectator();
    }
}
