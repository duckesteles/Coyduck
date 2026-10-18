package dev.coyduck.managers;

import dev.coyduck.events.PacketApplyEvent;
import dev.coyduck.mixin.ClientWorldAccessor;
import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.c2s.play.HandSwingC2SPacket;
import net.minecraft.network.packet.s2c.play.PlayerActionResponseS2CPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.Util;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.List;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public final class InteractionManager {
    private static final InteractionManager INSTANCE = new InteractionManager();

    private static final double REACH_MARGIN = 0.05;

    public enum SwingMode {
        Client,
        Packet,
        None
    }

    private record Pending(int sequence, long expiry) {}

    private final Long2ObjectOpenHashMap<Pending> pending = new Long2ObjectOpenHashMap<>();
    private final Int2LongOpenHashMap attacked = new Int2LongOpenHashMap();

    private InteractionManager() {}

    public static InteractionManager get() {
        return INSTANCE;
    }

    public boolean isPending(BlockPos pos) {
        Pending entry = pending.get(pos.asLong());
        return entry != null && entry.expiry > Util.getMeasuringTimeMs();
    }

    public boolean canPlace(BlockPos pos) {
        if (isPending(pos)) return false;
        if (mc.world.isOutOfHeightLimit(pos) || !mc.world.getWorldBorder().contains(pos)) return false;
        return mc.world.getBlockState(pos).isReplaceable();
    }

    public List<Entity> obstructions(BlockPos pos) {
        return mc.world.getOtherEntities(null, new Box(pos), entity -> !entity.isRemoved() && entity.intersectionChecked);
    }

    public BlockHitResult hit(BlockPos pos, boolean airplace) {
        Vec3d eye = mc.player.getEyePos();
        double reach = mc.player.getBlockInteractionRange() + 1.0 - REACH_MARGIN;
        double reachSquared = reach * reach;

        BlockHitResult best = null;
        double bestDistance = Double.MAX_VALUE;

        for (Direction direction : Direction.values()) {
            BlockPos neighbor = pos.offset(direction);
            BlockState state = mc.world.getBlockState(neighbor);
            if (state.isReplaceable() || BlockUtils.isClickable(state.getBlock())) continue;

            double distance = new Box(neighbor).squaredMagnitude(eye);
            if (distance >= reachSquared || distance >= bestDistance) continue;

            Direction side = direction.getOpposite();
            Vec3d point = Vec3d.ofCenter(neighbor).add(side.getOffsetX() * 0.5, side.getOffsetY() * 0.5, side.getOffsetZ() * 0.5);
            best = new BlockHitResult(point, side, neighbor, false);
            bestDistance = distance;
        }

        if (best != null || !airplace) return best;
        if (new Box(pos).squaredMagnitude(eye) >= reachSquared) return null;
        return new BlockHitResult(Vec3d.ofCenter(pos), Direction.UP, pos, false);
    }

    public void place(BlockPos pos, BlockHitResult hit, SwingMode swing) {
        mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hit);
        int sequence = ((ClientWorldAccessor) mc.world).coyduck$getPendingUpdateManager().getSequence();
        swing(swing);
        pending.put(pos.asLong(), new Pending(sequence, Util.getMeasuringTimeMs() + LatencyManager.get().timeout()));
    }

    public boolean attack(Entity target, InventoryManager.SwitchMode mode, SwingMode swing) {
        Runnable hit = () -> {
            mc.interactionManager.attackEntity(mc.player, target);
            swing(swing);
        };

        if (canHitWithHand()) {
            hit.run();
        } else {
            int weapon = weaponSlot();
            if (weapon == -1 || !InventoryManager.get().withItem(weapon, mode, hit)) return false;
        }

        attacked.put(target.getId(), Util.getMeasuringTimeMs());
        return true;
    }

    public boolean canHitWithHand() {
        return attackDamage(mc.player.getMainHandStack()) > 0;
    }

    public boolean recentlyAttacked(Entity entity) {
        long time = attacked.getOrDefault(entity.getId(), Long.MIN_VALUE);
        return time != Long.MIN_VALUE && Util.getMeasuringTimeMs() - time < LatencyManager.get().timeout();
    }

    public void swing(SwingMode mode) {
        switch (mode) {
            case Client -> mc.player.swingHand(Hand.MAIN_HAND);
            case Packet -> mc.getNetworkHandler().sendPacket(new HandSwingC2SPacket(Hand.MAIN_HAND));
            case None -> {}
        }
    }

    private int weaponSlot() {
        int best = -1;
        double bestDamage = 0;

        for (int i = 0; i < InventoryManager.MAIN_END; i++) {
            double damage = attackDamage(InventoryManager.get().stack(i));
            if (damage > bestDamage) {
                bestDamage = damage;
                best = i;
            }
        }

        return best;
    }

    private double attackDamage(ItemStack stack) {
        double[] damage = {1.0};
        stack.applyAttributeModifiers(EquipmentSlot.MAINHAND, (attribute, modifier) -> {
            if (attribute.equals(EntityAttributes.ATTACK_DAMAGE) && modifier.operation() == EntityAttributeModifier.Operation.ADD_VALUE) damage[0] += modifier.value();
        });

        StatusEffectInstance strength = mc.player.getStatusEffect(StatusEffects.STRENGTH);
        if (strength != null) damage[0] += 3.0 * (strength.getAmplifier() + 1);

        StatusEffectInstance weakness = mc.player.getStatusEffect(StatusEffects.WEAKNESS);
        if (weakness != null) damage[0] -= 4.0 * (weakness.getAmplifier() + 1);

        return damage[0];
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    private void onApply(PacketApplyEvent.Post event) {
        if (event.packet instanceof PlayerActionResponseS2CPacket packet) {
            int acked = packet.sequence();
            pending.values().removeIf(entry -> entry.sequence <= acked);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    private void onTick(TickEvent.Pre event) {
        long now = Util.getMeasuringTimeMs();
        pending.values().removeIf(entry -> entry.expiry <= now);

        long lifetime = LatencyManager.get().timeout();
        attacked.int2LongEntrySet().removeIf(entry -> now - entry.getLongValue() >= lifetime);
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        pending.clear();
        attacked.clear();
    }
}
