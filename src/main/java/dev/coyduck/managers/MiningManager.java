package dev.coyduck.managers;

import dev.coyduck.events.MineStopEvent;
import dev.coyduck.events.PacketApplyEvent;
import dev.coyduck.events.WorldResetEvent;
import dev.coyduck.mixin.ClientPlayerInteractionManagerAccessor;
import dev.coyduck.modules.AirMine;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.utils.world.TickRate;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffectUtil;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ChunkDeltaUpdateS2CPacket;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.Util;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public final class MiningManager {
    private static final MiningManager INSTANCE = new MiningManager();

    // 6b6t's anti-cheat cancels a finish before full progress, though vanilla accepts 0.7.
    private static final float SERVER_THRESHOLD = 1.0f;
    private static final double REACH_MARGIN = 0.05;

    private static final float BACKGROUND_THRESHOLD = 1.0f;
    private static final int HOLD_TICKS = 2;
    private static final int MAX_HOLDS = 4;
    private static final float HOLD_MIN_HEALTH = 10;
    private static final int MAX_STOPS = 2;

    private BlockPos background;
    private int backgroundStart;
    private long backgroundStartTime;
    private int holdTicks;
    private int holdSource = -1;
    private int holdHotbar = -1;
    private int holdAttempts;
    private long nextHold;
    private long blockedSince;

    private BlockPos pos;
    private Direction side;
    private Object owner;
    private int priority;
    private boolean autoStop;
    private InventoryManager.SwitchMode switchMode = InventoryManager.SwitchMode.InvSwap;
    private InteractionManager.SwingMode swing = InteractionManager.SwingMode.Packet;

    private int ticks;
    private int startTick;
    private long startTime;
    private long lastStop;
    private int lastStopTick = -1;
    private boolean brokenSinceStop;
    private boolean broken;
    private int stops;
    private boolean sending;

    private MiningManager() {}

    public static MiningManager get() {
        return INSTANCE;
    }

    public BlockPos pos() {
        return pos;
    }

    public boolean owns(Object owner) {
        return pos != null && this.owner == owner;
    }

    public boolean wasBroken() {
        return broken;
    }

    public boolean isMining(BlockPos target) {
        return pos != null && pos.equals(target);
    }

    public boolean mine(Object owner, int priority, BlockPos target, boolean autoStop, InventoryManager.SwitchMode switchMode, InteractionManager.SwingMode swing) {
        if (pos != null && this.owner != owner && priority < this.priority) return false;
        if (mc.interactionManager.isBreakingBlock()) return false;

        boolean same = target.equals(pos);
        if (!same && (!inRange(target) || mc.world.getBlockState(target).getHardness(mc.world, target) < 0)) return false;

        this.owner = owner;
        this.priority = priority;
        this.autoStop = autoStop;
        this.switchMode = switchMode;
        this.swing = swing;

        if (same) return true;
        if (pos != null && !broken) send(PlayerActionC2SPacket.Action.ABORT_DESTROY_BLOCK);

        pos = target.toImmutable();
        side = facing(pos);
        startTick = ticks;
        startTime = Util.getMeasuringTimeMs();
        lastStop = 0;
        lastStopTick = -1;
        brokenSinceStop = false;
        broken = false;
        stops = 0;

        send(PlayerActionC2SPacket.Action.START_DESTROY_BLOCK);
        InteractionManager.get().swing(swing);
        return true;
    }

    public BlockPos background() {
        return background;
    }

    public boolean isHolding() {
        return holdTicks > 0;
    }

    public boolean mineTwo(Object owner, int priority, BlockPos first, BlockPos second, boolean autoStop, InventoryManager.SwitchMode switchMode, InteractionManager.SwingMode swing) {
        if (background != null || first.equals(second)) return mine(owner, priority, first, autoStop, switchMode, swing);
        if (!mine(owner, priority, second, autoStop, switchMode, swing)) return false;
        if (broken || mc.world.getBlockState(second).isReplaceable()) return mine(owner, priority, first, autoStop, switchMode, swing);

        send(PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK, pos, side, false);
        background = pos;
        backgroundStart = startTick;
        backgroundStartTime = startTime;
        holdAttempts = 0;
        nextHold = 0;

        pos = null;
        broken = false;
        brokenSinceStop = false;
        return mine(owner, priority, first, autoStop, switchMode, swing);
    }

    public void release(Object owner) {
        if (!owns(owner)) return;

        if (!broken && mc.getNetworkHandler() != null) send(PlayerActionC2SPacket.Action.ABORT_DESTROY_BLOCK);
        reset();
    }

    public float progress() {
        if (pos == null || mc.world == null) return 0;

        BlockState state = mc.world.getBlockState(pos);
        if (state.isReplaceable()) return broken ? 1 : 0;
        return Math.min(bestDelta(state) * elapsed() / SERVER_THRESHOLD, 1);
    }

    public int remainingTicks() {
        if (pos == null || mc.world == null) return Integer.MAX_VALUE;
        return remainingTicks(mc.world.getBlockState(pos));
    }

    public int remainingTicks(BlockState state) {
        if (pos == null || mc.world == null || state.isReplaceable()) return Integer.MAX_VALUE;

        float delta = bestDelta(state);
        if (delta <= 0) return Integer.MAX_VALUE;

        float margin = (float) Math.ceil(LatencyManager.get().jitter() / 50.0);
        return Math.max(0, (int) Math.ceil(SERVER_THRESHOLD / delta - elapsed() + margin));
    }

    public boolean isReady() {
        return pos != null && isReady(mc.world.getBlockState(pos));
    }

    public boolean stop(Object owner) {
        return pos != null && stop(owner, mc.world.getBlockState(pos));
    }

    private boolean isReady(BlockState state) {
        if (state.isReplaceable()) return false;

        float margin = (float) Math.ceil(LatencyManager.get().jitter() / 50.0);
        return bestDelta(state) * (elapsed() - margin) >= SERVER_THRESHOLD;
    }

    private boolean stop(Object owner, BlockState state) {
        if (!owns(owner) || !isReady(state) || state.isOf(Blocks.COBWEB) || lastStopTick == ticks || InventoryManager.get().isOffhandEating()) return false;

        // Grim rates the break by the best tool it saw held on a movement or swing packet, so swing while the tool is
        // in hand. Without it, a tool switched in only for the finish looks like breaking obsidian by hand.
        int tool = bestTool(state);
        Runnable action = () -> {
            InteractionManager.get().swing(swing == InteractionManager.SwingMode.None ? InteractionManager.SwingMode.Packet : swing);
            send(PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK);
        };

        if (tool == -1) action.run();
        else if (!InventoryManager.get().withItem(tool, switchMode, action)) return false;

        lastStop = Util.getMeasuringTimeMs();
        lastStopTick = ticks;
        brokenSinceStop = false;
        stops++;

        MeteorClient.EVENT_BUS.post(MineStopEvent.get(pos));
        return true;
    }

    public float delta(BlockState state, BlockPos at, ItemStack tool) {
        return delta(state, at, tool, mc.player.isOnGround() || AirMine.spoofing());
    }

    private float delta(BlockState state, BlockPos at, ItemStack tool, boolean ground) {
        float hardness = state.getHardness(mc.world, at);
        if (hardness < 0) return 0;
        if (hardness == 0) return Float.MAX_VALUE;

        float speed = tool.getMiningSpeedMultiplier(state);
        if (speed > 1) {
            int efficiency = enchantmentLevel(tool);
            if (efficiency > 0) speed += efficiency * efficiency + 1;
        }

        if (StatusEffectUtil.hasHaste(mc.player)) speed *= 1 + (StatusEffectUtil.getHasteAmplifier(mc.player) + 1) * 0.2f;

        StatusEffectInstance fatigue = mc.player.getStatusEffect(StatusEffects.MINING_FATIGUE);
        if (fatigue != null) {
            speed *= switch (fatigue.getAmplifier()) {
                case 0 -> 0.3f;
                case 1 -> 0.09f;
                case 2 -> 0.0027f;
                default -> 8.1E-4f;
            };
        }

        speed *= (float) mc.player.getAttributeValue(EntityAttributes.BLOCK_BREAK_SPEED);
        if (mc.player.isSubmergedIn(FluidTags.WATER)) speed *= (float) mc.player.getAttributeValue(EntityAttributes.SUBMERGED_MINING_SPEED);
        if (!ground) speed /= 5.0f;

        boolean harvest = !state.isToolRequired() || tool.isSuitableFor(state);
        return speed / hardness / (harvest ? 30 : 100);
    }

    public int bestTool(BlockState state) {
        return bestTool(state, pos);
    }

    private int bestTool(BlockState state, BlockPos at) {
        int best = -1;
        float bestDelta = delta(state, at, mc.player.getMainHandStack());

        for (int i = 0; i < InventoryManager.MAIN_END; i++) {
            float delta = delta(state, at, InventoryManager.get().stack(i));
            if (delta > bestDelta) {
                bestDelta = delta;
                best = i;
            }
        }

        return best;
    }

    public boolean inRange(BlockPos target) {
        double reach = mc.player.getBlockInteractionRange() + 1.0 - REACH_MARGIN;
        return new Box(target).squaredMagnitude(mc.player.getEyePos()) < reach * reach;
    }

    @EventHandler(priority = EventPriority.HIGH)
    private void onTick(TickEvent.Pre event) {
        ticks++;
        if (mc.player == null || mc.world == null || !mc.player.isAlive()) return;
        if (InventoryManager.get().isOffhandEating()) return;

        tickBackground();
        if (pos == null) return;

        if (!inRange(pos)) {
            reset();
            return;
        }

        boolean waited = Util.getMeasuringTimeMs() - lastStop > LatencyManager.get().timeout();
        if (stops >= MAX_STOPS && waited && !mc.world.getBlockState(pos).isReplaceable()) {
            restart();
            return;
        }
        if (autoStop && (brokenSinceStop || waited)) stop(owner);
    }

    private void restart() {
        side = facing(pos);
        startTick = ticks;
        startTime = Util.getMeasuringTimeMs();
        lastStop = 0;
        lastStopTick = -1;
        brokenSinceStop = false;
        stops = 0;

        send(PlayerActionC2SPacket.Action.START_DESTROY_BLOCK);
        InteractionManager.get().swing(swing);
    }

    @EventHandler(priority = EventPriority.HIGH)
    private void onApply(PacketApplyEvent.Post event) {
        if ((pos == null && background == null) || mc.world == null) return;

        switch (event.packet) {
            case BlockUpdateS2CPacket packet when packet.getPos().equals(pos) -> update(packet.getState());
            case BlockUpdateS2CPacket packet when packet.getPos().equals(background) && packet.getState().isReplaceable() -> clearBackground();
            case ChunkDeltaUpdateS2CPacket packet -> packet.visitUpdates((at, state) -> {
                if (at.equals(pos)) update(state);
                else if (at.equals(background) && state.isReplaceable()) clearBackground();
            });
            default -> {}
        }
    }

    @EventHandler
    private void onSend(PacketEvent.Send event) {
        if (sending || pos == null) return;
        if (event.packet instanceof PlayerActionC2SPacket packet && packet.getAction() == PlayerActionC2SPacket.Action.START_DESTROY_BLOCK) reset();
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        reset();
    }

    @EventHandler
    private void onWorldReset(WorldResetEvent event) {
        reset();
    }

    private void tickBackground() {
        if (holdTicks > 0 && --holdTicks == 0) unhold();
        if (background == null) return;

        BlockState state = mc.world.getBlockState(background);
        if (state.isReplaceable()) {
            clearBackground();
            return;
        }

        if (holdTicks > 0) return;

        if (Util.getMeasuringTimeMs() < nextHold || !backgroundReady(state)) return;
        if (InventoryManager.get().wantsToEat() || mc.player.getHealth() + mc.player.getAbsorptionAmount() <= HOLD_MIN_HEALTH
            || mc.player.getMainHandStack().contains(DataComponentTypes.DEATH_PROTECTION)) {
            long now = Util.getMeasuringTimeMs();
            if (blockedSince == 0) blockedSince = now;
            else if (!InventoryManager.get().isEating() && now - blockedSince > LatencyManager.get().timeout()) clearBackground();
            return;
        }
        blockedSince = 0;

        if (++holdAttempts > MAX_HOLDS) {
            clearBackground();
            return;
        }
        hold(state);
    }

    private boolean backgroundReady(BlockState state) {
        int tool = bestTool(state, background);
        float delta = delta(state, background, tool == -1 ? mc.player.getMainHandStack() : InventoryManager.get().stack(tool), mc.player.isOnGround());
        float margin = (float) Math.ceil(LatencyManager.get().jitter() / 50.0);
        float rate = Math.min(TickRate.INSTANCE.getTickRate() / 20.0f, 1.0f);
        return delta * ((Util.getMeasuringTimeMs() - backgroundStartTime) / 50.0f * rate - margin) >= BACKGROUND_THRESHOLD;
    }

    private void hold(BlockState state) {
        int tool = bestTool(state, background);
        if (tool != -1) {
            int hotbar = InventoryManager.get().selectedSlot();
            if (!InventoryManager.get().swapWithHotbar(tool, hotbar)) return;
            holdSource = tool;
            holdHotbar = hotbar;
        }

        holdTicks = HOLD_TICKS;
        nextHold = Util.getMeasuringTimeMs() + LatencyManager.get().timeout();
    }

    private void unhold() {
        if (holdSource != -1 && mc.player != null && InventoryManager.get().isEating()) {
            holdTicks = 1;
            return;
        }
        if (holdSource != -1) InventoryManager.get().swapWithHotbar(holdSource, holdHotbar);
        holdSource = -1;
        holdHotbar = -1;
        holdTicks = 0;
    }

    private void clearBackground() {
        unhold();
        background = null;
        holdAttempts = 0;
        blockedSince = 0;
    }

    private void update(BlockState state) {
        if (state.isReplaceable()) {
            broken = true;
            brokenSinceStop = true;
            stops = 0;
        } else if (autoStop && brokenSinceStop) {
            stop(owner, state);
        }
    }

    private void send(PlayerActionC2SPacket.Action action) {
        Direction face = switch (action) {
            case ABORT_DESTROY_BLOCK -> Direction.DOWN;
            case STOP_DESTROY_BLOCK -> facing(pos);
            default -> side;
        };
        send(action, pos, face, action == PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK);
    }

    private void send(PlayerActionC2SPacket.Action action, BlockPos target, Direction face, boolean predict) {
        sending = true;
        try {
            ((ClientPlayerInteractionManagerAccessor) mc.interactionManager).coyduck$sendSequencedPacket(mc.world, sequence -> {
                if (predict) {
                    mc.world.setBlockState(target, mc.world.getFluidState(target).getBlockState(), Block.NOTIFY_ALL_AND_REDRAW);
                }
                return new PlayerActionC2SPacket(action, target, face, sequence);
            });
        } finally {
            sending = false;
        }
    }

    private float bestDelta(BlockState state) {
        int tool = bestTool(state);
        return delta(state, pos, tool == -1 ? mc.player.getMainHandStack() : InventoryManager.get().stack(tool));
    }

    // Server ticks since START, from the wall clock: a client timer (HoleSnap) runs client ticks faster than the server.
    private float elapsed() {
        float rate = Math.min(TickRate.INSTANCE.getTickRate() / 20.0f, 1.0f);
        return (Util.getMeasuringTimeMs() - startTime) / 50.0f * rate;
    }

    private void reset() {
        clearBackground();
        pos = null;
        owner = null;
        priority = 0;
        broken = false;
        brokenSinceStop = false;
    }

    private static Direction facing(BlockPos target) {
        Vec3d offset = mc.player.getEyePos().subtract(Vec3d.ofCenter(target));
        return Direction.getFacing(offset.x, offset.y, offset.z);
    }

    private static int enchantmentLevel(ItemStack stack) {
        for (var entry : stack.getEnchantments().getEnchantmentEntries()) {
            if (entry.getKey().matchesKey(Enchantments.EFFICIENCY)) return entry.getIntValue();
        }
        return 0;
    }
}
