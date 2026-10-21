package dev.coyduck.modules;

import dev.coyduck.Coyduck;
import dev.coyduck.events.PacketApplyEvent;
import dev.coyduck.managers.InteractionManager;
import dev.coyduck.managers.InventoryManager;
import dev.coyduck.managers.LatencyManager;
import dev.coyduck.managers.MotionTracker;
import dev.coyduck.utils.Targets;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
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
import net.minecraft.util.math.MathHelper;

import java.util.ArrayList;
import java.util.List;
public class AutoWeb extends Module {
    public enum Mode {
        Smart,
        Always
    }

    private static final double SELF_MARGIN = 0.1;
    private static final int MAX_PREDICT_TICKS = 7;

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
        .description("Smart only webs targets that are out of a hole, where a web actually slows them. Always webs them anywhere.")
        .defaultValue(Mode.Smart)
        .build()
    );

    private final Setting<Boolean> predict = sgGeneral.add(new BoolSetting.Builder()
        .name("predict")
        .description("Also webs where the target will be when your packet reaches the server.")
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
        .description("How webs are put in your hand.")
        .defaultValue(InventoryManager.SwitchMode.InvSwap)
        .build()
    );

    private final Setting<Boolean> airplace = sgGeneral.add(new BoolSetting.Builder()
        .name("airplace")
        .description("Places webs without a neighbouring block to click on, for targets in the air.")
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

    public AutoWeb() {
        super(Coyduck.CATEGORY, "auto-web+", "Webs the cells the target stands in and is about to step into.");
    }

    @Override
    public void onActivate() {
        Module meteor = Modules.get().get(meteordevelopment.meteorclient.systems.modules.combat.AutoWeb.class);
        if (meteor != null && meteor.isActive()) {
            meteor.toggle();
            info("Disabled %s, it webs the same target.", meteor.title);
        }

        target = null;
        budget = websPerTick.get();
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
            case BlockUpdateS2CPacket packet when packet.getState().isReplaceable() && packet.getPos().isWithinDistance(target.getBlockPos(), 3) -> pass();
            case ChunkDeltaUpdateS2CPacket ignored -> pass();
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
        target = Targets.find(targetRange.get());
        if (target == null || budget <= 0) return;

        int slot = InventoryManager.get().find(stack -> stack.isOf(Items.COBWEB), 0, InventoryManager.MAIN_END);
        if (slot == -1) return;

        Box box = Targets.box(target);
        LongSet columns = Targets.columns(box);
        if (mode.get() == Mode.Smart && Targets.isHoled(columns)) return;

        LongSet cells = cells(box);
        if (predict.get()) {
            int ticks = Math.min(MathHelper.ceil(LatencyManager.get().rtt() / 50.0) + 1, MAX_PREDICT_TICKS);
            LongSet current = new LongOpenHashSet(cells);
            for (long key : cells(MotionTracker.get().predict(target, ticks))) {
                BlockPos cell = BlockPos.fromLong(key);
                boolean crystalSpot = Targets.isBase(mc.world.getBlockState(cell.down())) && mc.world.getBlockState(cell).isAir();
                if (current.contains(key) || !crystalSpot) cells.add(key);
            }
        }

        Box self = selfBox();
        InteractionManager interaction = InteractionManager.get();
        List<BlockPos> planned = new ArrayList<>();

        for (long key : cells) {
            if (planned.size() >= budget) break;

            BlockPos pos = BlockPos.fromLong(key);
            if (!interaction.canPlace(pos) || mc.world.getBlockState(pos).isOf(Blocks.COBWEB)) continue;
            if (self.intersects(new Box(pos)) || interaction.hit(pos, airplace.get()) == null) continue;
            planned.add(pos);
        }

        if (planned.isEmpty()) return;

        InventoryManager.get().withItem(slot, switchMode.get(), () -> {
            for (BlockPos pos : planned) {
                if (budget <= 0 || !mc.player.getMainHandStack().isOf(Items.COBWEB)) break;

                BlockHitResult hit = interaction.hit(pos, airplace.get());
                if (hit == null) continue;

                interaction.place(pos, hit, swing.get());
                budget--;
            }
        });
    }

    private LongSet cells(Box box) {
        LongSet cells = new LongOpenHashSet();
        int bottom = MathHelper.floor(box.minY);
        int top = MathHelper.floor(box.maxY - 1.0E-4);

        for (int x = MathHelper.floor(box.minX); x <= MathHelper.floor(box.maxX - 1.0E-4); x++) {
            for (int z = MathHelper.floor(box.minZ); z <= MathHelper.floor(box.maxZ - 1.0E-4); z++) {
                for (int y = bottom; y <= top; y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState state = mc.world.getBlockState(pos);
                    if (state.isReplaceable() || state.isOf(Blocks.COBWEB)) {
                        cells.add(pos.asLong());
                        break;
                    }

                    if (Targets.isBurrowedIn(box, pos)) break;
                }
            }
        }

        return cells;
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
