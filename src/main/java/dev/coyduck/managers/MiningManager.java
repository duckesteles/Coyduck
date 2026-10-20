package dev.coyduck.managers;

import dev.coyduck.events.MineStopEvent;
import dev.coyduck.events.PacketApplyEvent;
import dev.coyduck.events.WorldResetEvent;
import dev.coyduck.mixin.ClientPlayerInteractionManagerAccessor;
import dev.coyduck.modules.AutoTotem;
import dev.coyduck.utils.Targets;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.utils.world.TickRate;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffectUtil;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ChunkDeltaUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerActionResponseS2CPacket;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.Util;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public final class MiningManager {
    private static final MiningManager INSTANCE = new MiningManager();

    private static final float SERVER_THRESHOLD = 0.7f;
    private static final double REACH_MARGIN = 0.05;

    private static final float BACKGROUND_THRESHOLD = 1.0f;
    private static final int SETTLE_TICKS = 2;
    private static final int MAX_HOLDS = 4;
    private static final int PRIMARY_HOLD_SLACK = 10;
    private static final int PRIMARY_COOLDOWN = 20;
    private static final long BACKGROUND_FORGET = 10_000;
    private static final float HOLD_MIN_HEALTH = 10;

    private BlockPos background;
    private int backgroundStart;
    private boolean backgroundActive;
    private int holdAttempts;
    private long nextHold;
    private long blockedSince;
    private long backgroundSince;
    private boolean totemSwap;
    private int primaryCooldown;

    private boolean holding;
    private boolean primaryHold;
    private int holdEnd;
    private int holdSource = -1;
    private int holdHotbar = -1;

    private Item mainItem;
    private int mainEfficiency;
    private int mainSince;

    private BlockPos pos;
    private Direction side;
    private Object owner;
    private int priority;
    private boolean autoStop;
    private InventoryManager.SwitchMode switchMode = InventoryManager.SwitchMode.InvSwap;
    private InteractionManager.SwingMode swing = InteractionManager.SwingMode.Packet;

    private int ticks;
    private int startTick;
    private long lastStop;
    private int lastStopTick = -1;
    private int lastSequence;
    private int stopSequence = -1;
    private boolean brokenSinceStop;
    private boolean broken;
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
        if (primaryHold) endHold();

        pos = target.toImmutable();
        side = facing(pos);
        startTick = ticks;
        lastStop = 0;
        lastStopTick = -1;
        stopSequence = -1;
        brokenSinceStop = false;
        broken = false;

        send(PlayerActionC2SPacket.Action.START_DESTROY_BLOCK);
        InteractionManager.get().swing(swing);
        return true;
    }

    public BlockPos background() {
        return backgroundActive ? background : null;
    }

    public boolean canMineTwo() {
        return background == null;
    }

    public boolean isHolding() {
        return holding;
    }

    public void allowTotemSwap(boolean allow) {
        totemSwap = allow;
    }

    public boolean mineTwo(Object owner, int priority, BlockPos first, BlockPos second, boolean autoStop, InventoryManager.SwitchMode switchMode, InteractionManager.SwingMode swing) {
        if (background != null || first.equals(second)) return mine(owner, priority, first, autoStop, switchMode, swing);
        if (!mine(owner, priority, second, autoStop, switchMode, swing)) return false;
        if (broken || mc.world.getBlockState(second).isReplaceable()) return mine(owner, priority, first, autoStop, switchMode, swing);

        send(PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK, pos, side, false);
        setBackground(pos, startTick, true);

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

    public void releaseAll(Object owner) {
        release(owner);
        if (pos == null) backgroundActive = false;
    }

    public float progress() {
        if (pos == null || mc.world == null) return 0;

        BlockState state = mc.world.getBlockState(pos);
        if (state.isReplaceable()) return broken ? 1 : 0;
        return Math.min(fullDelta(state, pos) * elapsed() / SERVER_THRESHOLD, 1);
    }

    public int remainingTicks() {
        if (pos == null || mc.world == null) return Integer.MAX_VALUE;
        return remainingTicks(mc.world.getBlockState(pos));
    }

    public int remainingTicks(BlockState state) {
        if (pos == null || mc.world == null || state.isReplaceable()) return Integer.MAX_VALUE;

        float delta = fullDelta(state, pos);
        if (delta <= 0) return Integer.MAX_VALUE;

        int remaining = (int) Math.ceil(SERVER_THRESHOLD / delta - elapsed() + margin());
        if (stopDelta(state, pos) < delta) remaining = Math.max(remaining, settleTicks() + 1);
        return Math.max(0, remaining);
    }

    public boolean isReady() {
        return pos != null && isReady(mc.world.getBlockState(pos));
    }

    public boolean stop(Object owner) {
        return pos != null && stop(owner, mc.world.getBlockState(pos));
    }

    private boolean isReady(BlockState state) {
        if (state.isReplaceable()) return false;
        return stopDelta(state, pos) * (elapsed() - margin()) >= SERVER_THRESHOLD;
    }

    private boolean stop(Object owner, BlockState state) {
        if (!owns(owner) || !isReady(state) || lastStopTick == ticks) return false;

        int tool = bestTool(state, pos);
        Runnable action = () -> {
            send(PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK);
            stopSequence = lastSequence;
        };

        if (tool == -1) action.run();
        else if (!InventoryManager.get().withItem(tool, switchMode, action)) return false;

        InteractionManager.get().swing(swing);
        lastStop = Util.getMeasuringTimeMs();
        lastStopTick = ticks;
        brokenSinceStop = false;
        if (primaryHold) endHold();

        MeteorClient.EVENT_BUS.post(MineStopEvent.get(pos));
        return true;
    }

    public float delta(BlockState state, BlockPos at, ItemStack tool) {
        return delta(state, at, tool, true);
    }

    private float delta(BlockState state, BlockPos at, ItemStack tool, boolean efficiency) {
        float hardness = state.getHardness(mc.world, at);
        if (hardness < 0) return 0;
        if (hardness == 0) return Float.MAX_VALUE;

        float speed = tool.getMiningSpeedMultiplier(state);
        if (speed > 1 && efficiency) {
            int level = enchantmentLevel(tool);
            if (level > 0) speed += level * level + 1;
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
        if (!mc.player.isOnGround()) speed /= 5.0f;

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

    private float fullDelta(BlockState state, BlockPos at) {
        int tool = bestTool(state, at);
        return delta(state, at, tool == -1 ? mc.player.getMainHandStack() : InventoryManager.get().stack(tool));
    }

    private float stopDelta(BlockState state, BlockPos at) {
        int tool = bestTool(state, at);
        if (tool == -1) return delta(state, at, mc.player.getMainHandStack(), mainSettled());
        return delta(state, at, InventoryManager.get().stack(tool), false);
    }

    private boolean mainSettled() {
        return ticks - mainSince >= settleTicks();
    }

    private int settleTicks() {
        return SETTLE_TICKS + (int) Math.ceil(LatencyManager.get().jitter() / 50.0);
    }

    public boolean inRange(BlockPos target) {
        double reach = mc.player.getBlockInteractionRange() + 1.0 - REACH_MARGIN;
        return new Box(target).squaredMagnitude(mc.player.getEyePos()) < reach * reach;
    }

    @EventHandler(priority = EventPriority.HIGH)
    private void onTick(TickEvent.Pre event) {
        ticks++;
        if (mc.player == null || mc.world == null) return;

        ItemStack main = mc.player.getMainHandStack();
        int efficiency = enchantmentLevel(main);
        if (main.getItem() != mainItem || efficiency != mainEfficiency) {
            mainItem = main.getItem();
            mainEfficiency = efficiency;
            mainSince = ticks;
        }

        tickBackground();
        if (pos == null) return;

        if (!inRange(pos)) {
            reset();
            return;
        }

        if (autoStop && (brokenSinceStop || Util.getMeasuringTimeMs() - lastStop > LatencyManager.get().timeout()) && !stop(owner)) preHold();
    }

    private void preHold() {
        BlockState state = mc.world.getBlockState(pos);
        if (state.isReplaceable() || primaryHold || ticks < primaryCooldown || isReady(state)) return;

        float full = fullDelta(state, pos);
        if (stopDelta(state, pos) >= full) return;
        if (full * (elapsed() + settleTicks() + 1 - margin()) < SERVER_THRESHOLD) return;
        if (background != null && Targets.ownRing().contains(background)) return;
        if (!holding && (!canPrimaryHold() || !beginHold(state, pos))) return;

        primaryHold = true;
        holdEnd = ticks + 2 * settleTicks() + PRIMARY_HOLD_SLACK;
    }

    @EventHandler(priority = EventPriority.HIGH)
    private void onApply(PacketApplyEvent.Post event) {
        if ((pos == null && background == null) || mc.world == null) return;

        switch (event.packet) {
            case BlockUpdateS2CPacket packet when packet.getPos().equals(pos) -> update(packet.getState());
            case BlockUpdateS2CPacket packet when packet.getPos().equals(background) && packet.getState().isAir() -> clearBackground();
            case ChunkDeltaUpdateS2CPacket packet -> packet.visitUpdates((at, state) -> {
                if (at.equals(pos)) update(state);
                else if (at.equals(background) && state.isAir()) clearBackground();
            });
            case PlayerActionResponseS2CPacket packet when stopSequence != -1 && packet.sequence() >= stopSequence -> checkStop();
            default -> {}
        }
    }

    private void checkStop() {
        stopSequence = -1;
        if (pos == null || brokenSinceStop || mc.world.getBlockState(pos).isReplaceable()) return;
        if (background == null) setBackground(pos, startTick, false);
    }

    @EventHandler
    private void onSend(PacketEvent.Send event) {
        if (sending || pos == null) return;
        if (event.packet instanceof PlayerActionC2SPacket packet && packet.getAction() == PlayerActionC2SPacket.Action.START_DESTROY_BLOCK) reset();
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        reset();
        forgetBackground();
    }

    @EventHandler
    private void onWorldReset(WorldResetEvent event) {
        reset();
        forgetBackground();
    }

    private void setBackground(BlockPos at, int start, boolean active) {
        background = at.toImmutable();
        backgroundStart = start;
        backgroundActive = active;
        backgroundSince = Util.getMeasuringTimeMs();
        holdAttempts = 0;
        nextHold = 0;
        blockedSince = 0;
    }

    private void tickBackground() {
        if (holding && ticks >= holdEnd) {
            if (primaryHold) primaryCooldown = ticks + PRIMARY_COOLDOWN;
            endHold();
        }
        if (background == null) return;

        BlockState state = mc.world.getBlockState(background);
        boolean predicted = background.equals(pos) && stopSequence != -1;
        if (state.isAir() && !predicted) {
            clearBackground();
            return;
        }

        if (!backgroundActive && Util.getMeasuringTimeMs() - backgroundSince > BACKGROUND_FORGET) {
            forgetBackground();
            return;
        }
        if (!backgroundActive || holding) return;
        if (Targets.ownRing().contains(background)) {
            backgroundActive = false;
            return;
        }

        if (Util.getMeasuringTimeMs() < nextHold || !backgroundReady(state)) return;
        if (!canHold() || mc.player.getHealth() + mc.player.getAbsorptionAmount() <= HOLD_MIN_HEALTH) {
            long now = Util.getMeasuringTimeMs();
            if (blockedSince == 0) blockedSince = now;
            else if (now - blockedSince > LatencyManager.get().timeout()) backgroundActive = false;
            return;
        }
        blockedSince = 0;

        if (++holdAttempts > MAX_HOLDS) {
            backgroundActive = false;
            return;
        }

        if (beginHold(state, background)) {
            primaryHold = false;
            holdEnd = ticks + settleTicks() + 1;
        }
        nextHold = Util.getMeasuringTimeMs() + LatencyManager.get().timeout();
    }

    private boolean backgroundReady(BlockState state) {
        float rate = Math.min(TickRate.INSTANCE.getTickRate() / 20.0f, 1.0f);
        return fullDelta(state, background) * ((ticks - backgroundStart) * rate - margin()) >= BACKGROUND_THRESHOLD;
    }

    private boolean canHold() {
        if (InventoryManager.get().isEating()) return false;
        return !mc.player.getMainHandStack().contains(DataComponentTypes.DEATH_PROTECTION);
    }

    private boolean canPrimaryHold() {
        if (canHold()) return true;
        if (!totemSwap || InventoryManager.get().isEating()) return false;
        if (!mc.player.getOffHandStack().contains(DataComponentTypes.DEATH_PROTECTION) || AutoTotem.popInFlight()) return false;
        return mc.player.getHealth() + mc.player.getAbsorptionAmount() > HOLD_MIN_HEALTH;
    }

    private boolean beginHold(BlockState state, BlockPos at) {
        int tool = bestTool(state, at);
        if (tool != -1) {
            int hotbar = InventoryManager.get().selectedSlot();
            if (!InventoryManager.get().swapWithHotbar(tool, hotbar)) return false;
            holdSource = tool;
            holdHotbar = hotbar;
        }

        holding = true;
        return true;
    }

    private void endHold() {
        if (holdSource != -1 && mc.player != null) InventoryManager.get().swapWithHotbar(holdSource, holdHotbar);
        holdSource = -1;
        holdHotbar = -1;
        holding = false;
        primaryHold = false;
    }

    private void clearBackground() {
        if (holding && !primaryHold) endHold();
        forgetBackground();
    }

    private void forgetBackground() {
        background = null;
        backgroundActive = false;
        holdAttempts = 0;
        blockedSince = 0;
    }

    private void update(BlockState state) {
        if (state.isReplaceable()) {
            broken = true;
            brokenSinceStop = true;
            if (primaryHold) endHold();
        } else if (autoStop && brokenSinceStop) {
            stop(owner, state);
        }
    }

    private void send(PlayerActionC2SPacket.Action action) {
        send(action, pos, side, action == PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK);
    }

    private void send(PlayerActionC2SPacket.Action action, BlockPos target, Direction face, boolean predict) {
        sending = true;
        try {
            ((ClientPlayerInteractionManagerAccessor) mc.interactionManager).coyduck$sendSequencedPacket(mc.world, sequence -> {
                lastSequence = sequence;
                if (predict) {
                    mc.world.setBlockState(target, mc.world.getFluidState(target).getBlockState(), Block.NOTIFY_ALL_AND_REDRAW);
                }
                return new PlayerActionC2SPacket(action, target, face, sequence);
            });
        } finally {
            sending = false;
        }
    }

    private float elapsed() {
        float rate = Math.min(TickRate.INSTANCE.getTickRate() / 20.0f, 1.0f);
        return (ticks - startTick) * rate;
    }

    private static float margin() {
        return (float) Math.ceil(LatencyManager.get().jitter() / 50.0);
    }

    private void reset() {
        if (holding) endHold();
        totemSwap = false;
        pos = null;
        owner = null;
        priority = 0;
        stopSequence = -1;
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
