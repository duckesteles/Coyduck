package dev.coyduck.modules;

import dev.coyduck.Coyduck;
import dev.coyduck.events.MineStopEvent;
import dev.coyduck.events.PacketApplyEvent;
import dev.coyduck.managers.CrystalTracker;
import dev.coyduck.managers.InteractionManager;
import dev.coyduck.managers.InventoryManager;
import dev.coyduck.utils.ExplosionDamage;
import dev.coyduck.utils.Targets;
import it.unimi.dsi.fastutil.longs.LongSet;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ChunkDeltaUpdateS2CPacket;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.List;

public class AntiSurround extends Module {
    private static final double PLACE_RANGE = 5.5;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Double> targetRange = sgGeneral.add(new DoubleSetting.Builder()
        .name("target-range")
        .description("Range to look for targets in.")
        .defaultValue(7)
        .range(1, 12)
        .sliderRange(1, 12)
        .build()
    );

    private final Setting<Boolean> keepFilled = sgGeneral.add(new BoolSetting.Builder()
        .name("keep-filled")
        .description("Also fills open gaps every tick while the target is in a hole, not only the moment a block breaks.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> minDamage = sgGeneral.add(new DoubleSetting.Builder()
        .name("min-damage")
        .description("Least damage the gap crystal must deal to the target.")
        .defaultValue(4)
        .range(0, 36)
        .sliderRange(0, 36)
        .build()
    );

    private final Setting<Double> maxSelfDamage = sgGeneral.add(new DoubleSetting.Builder()
        .name("max-self-damage")
        .description("Most damage the gap crystal may deal to you.")
        .defaultValue(8)
        .range(0, 36)
        .sliderRange(0, 36)
        .build()
    );

    private final Setting<InventoryManager.SwitchMode> switchMode = sgGeneral.add(new EnumSetting.Builder<InventoryManager.SwitchMode>()
        .name("switch")
        .description("How crystals are put in your hand.")
        .defaultValue(InventoryManager.SwitchMode.InvSwap)
        .build()
    );

    private final Setting<InteractionManager.SwingMode> swing = sgGeneral.add(new EnumSetting.Builder<InteractionManager.SwingMode>()
        .name("swing")
        .description("How the hand swing is shown.")
        .defaultValue(InteractionManager.SwingMode.Packet)
        .build()
    );

    private PlayerEntity target;

    public AntiSurround() {
        super(Coyduck.CATEGORY, "anti-surround", "Puts a crystal into a gap in the target's surround the moment it opens, so it cannot be closed again.");
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
    private void onMineStop(MineStopEvent event) {
        if (!canAct() || CrystalTracker.get().isReserved(event.pos)) return;
        fill(event.pos);
    }

    @EventHandler
    private void onPacket(PacketApplyEvent.Post event) {
        if (!canAct()) return;

        switch (event.packet) {
            case BlockUpdateS2CPacket packet when packet.getState().isReplaceable() -> fill(packet.getPos());
            case ChunkDeltaUpdateS2CPacket packet -> packet.visitUpdates((pos, state) -> {
                if (state.isReplaceable()) fill(pos.toImmutable());
            });
            default -> {}
        }
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (!canAct()) return;

        target = Targets.find(targetRange.get());
        if (target == null || !keepFilled.get()) return;

        List<BlockPos> ring = ring(target);
        int solid = 0;
        for (BlockPos pos : ring) {
            if (!mc.world.getBlockState(pos).isReplaceable()) solid++;
        }
        if (solid * 2 < ring.size()) return;

        for (BlockPos gap : ring) fill(gap);
    }

    private void fill(BlockPos gap) {
        if (target == null || !Targets.valid(target, targetRange.get())) {
            target = Targets.find(targetRange.get());
            if (target == null) return;
        }

        if (!ring(target).contains(gap) || CrystalTracker.get().isReserved(gap)) return;

        BlockPos base = gap.down();
        if (!mc.world.getBlockState(gap).isAir() || CrystalTracker.get().isPlacing(base)) return;
        if (!Targets.canHoldCrystal(mc.world, base)) return;
        if (new Box(base).squaredMagnitude(mc.player.getEyePos()) > PLACE_RANGE * PLACE_RANGE) return;
        Vec3d center = Targets.crystalCenter(base);
        if (!Targets.safe(center, mc.world, maxSelfDamage.get())) return;

        Box box = Targets.box(target);
        float damage = ExplosionDamage.crystal(target, Targets.feet(box), box, center, mc.world);
        if (damage < minDamage.get() && damage < target.getHealth() + target.getAbsorptionAmount()) return;

        InteractionManager.get().placeCrystal(base, switchMode.get(), swing.get(), null);
    }

    private static List<BlockPos> ring(PlayerEntity target) {
        LongSet columns = Targets.columns(Targets.box(target));
        return Targets.ring(columns, 0);
    }

    private boolean canAct() {
        return mc.player != null && mc.world != null && mc.interactionManager != null && mc.getNetworkHandler() != null
            && mc.player.isAlive() && !mc.player.isSpectator();
    }
}
