package dev.coyduck.modules;

import dev.coyduck.core.Core;
import dev.coyduck.events.PacketEvent;
import dev.coyduck.events.TickEvent;
import dev.coyduck.events.WorldResetEvent;
import dev.coyduck.feature.BoolSetting;
import dev.coyduck.feature.DoubleSetting;
import dev.coyduck.feature.EnumSetting;
import dev.coyduck.feature.Feature;
import dev.coyduck.feature.Setting;
import dev.coyduck.feature.SettingGroup;
import dev.coyduck.managers.InteractionManager;
import dev.coyduck.managers.InventoryManager;
import dev.coyduck.managers.MiningManager;
import dev.coyduck.utils.Trace;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

public class AntiRegear extends Feature {
    private static final int PRIORITY = 55;
    private static final int SCAN_EVERY = 4;
    private static final int REACH = 6;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Double> nearPlayer = sgGeneral.add(new DoubleSetting.Builder()
        .name("near-player")
        .description("Only breaks containers this close to an enemy.")
        .defaultValue(5)
        .range(1, 16)
        .sliderRange(1, 10)
        .build()
    );

    private final Setting<Boolean> enderChests = sgGeneral.add(new BoolSetting.Builder()
        .name("ender-chests")
        .description("Also breaks ender chests next to enemies.")
        .defaultValue(false)
        .build()
    );

    private final Setting<InventoryManager.SwitchMode> switchMode = sgGeneral.add(new EnumSetting.Builder<InventoryManager.SwitchMode>()
        .name("switch")
        .description("How to switch to the pickaxe.")
        .defaultValue(InventoryManager.SwitchMode.Silent)
        .build()
    );

    private final LongSet ours = new LongOpenHashSet();
    private final BlockPos.Mutable cursor = new BlockPos.Mutable();
    private BlockPos target;
    private int ticks;

    public AntiRegear() {
        super("anti-regear", "Breaks enemy regear shulkers.");
    }

    @Override
    public void onActivate() {
        target = null;
    }

    @Override
    public void onDeactivate() {
        MiningManager.get().release(this);
        target = null;
    }

    @EventHandler
    private void onWorldReset(WorldResetEvent event) {
        ours.clear();
        target = null;
    }

    @EventHandler
    private void onSend(PacketEvent.Send event) {
        if (!(event.packet instanceof PlayerInteractBlockC2SPacket packet) || mc.player == null) return;
        ItemStack stack = mc.player.getStackInHand(packet.getHand() == null ? Hand.MAIN_HAND : packet.getHand());
        if (stack.getItem() instanceof BlockItem item && guarded(item.getBlock())) {
            ours.add(packet.getBlockHitResult().getBlockPos().offset(packet.getBlockHitResult().getSide()).asLong());
        }
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;
        MiningManager mining = MiningManager.get();
        if (target != null && (!mining.owns(this) || !mining.isMining(target) || !guarded(mc.world.getBlockState(target).getBlock()) || !nearEnemy(target))) {
            if (mining.owns(this)) mining.release(this);
            target = null;
        }
        if (target != null || ticks++ % SCAN_EVERY != 0) return;

        BlockPos found = scan();
        if (found == null) return;
        if (mining.mine(this, PRIORITY, found, true, switchMode.get(), InteractionManager.SwingMode.Packet)) {
            target = found;
            Trace.log("anti regear breaks the container at {}", found);
        }
    }

    private BlockPos scan() {
        BlockPos center = mc.player.getBlockPos();
        Vec3d eye = mc.player.getEyePos();
        MiningManager mining = MiningManager.get();
        BlockPos best = null;
        double closest = Double.MAX_VALUE;
        for (int x = -REACH; x <= REACH; x++) {
            for (int y = -REACH; y <= REACH; y++) {
                for (int z = -REACH; z <= REACH; z++) {
                    cursor.set(center.getX() + x, center.getY() + y, center.getZ() + z);
                    BlockState state = mc.world.getBlockState(cursor);
                    if (!guarded(state.getBlock()) || ours.contains(cursor.asLong()) || !mining.inRange(cursor) || !nearEnemy(cursor)) continue;
                    double distance = Vec3d.ofCenter(cursor).squaredDistanceTo(eye);
                    if (distance < closest) {
                        closest = distance;
                        best = cursor.toImmutable();
                    }
                }
            }
        }
        return best;
    }

    private boolean nearEnemy(BlockPos pos) {
        double max = nearPlayer.get() * nearPlayer.get();
        Vec3d center = Vec3d.ofCenter(pos);
        for (PlayerEntity player : mc.world.getPlayers()) {
            if (player == mc.player || Core.host().isFriend(player) || !player.isAlive()) continue;
            if (player.squaredDistanceTo(center) <= max) return true;
        }
        return false;
    }

    private boolean guarded(Block block) {
        return block instanceof ShulkerBoxBlock || enderChests.get() && block == Blocks.ENDER_CHEST;
    }
}
