package dev.coyduck.managers;

import dev.coyduck.events.PacketApplyEvent;
import dev.coyduck.events.WorldResetEvent;
import dev.coyduck.mixin.ClientWorldAccessor;
import dev.coyduck.utils.BlockRules;
import dev.coyduck.utils.PacketBatch;
import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.block.BlockState;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.enchantment.Enchantments;
import dev.coyduck.utils.ExplosionDamage;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
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
    private static final int WEAPON_SETTLE = 2;
    private static final int DROP_RADIUS = 6;
    private static final float DROP_RESISTANCE = 600.0f;
    private static final long DROP_WINDOW = 50;

    public enum SwingMode {
        Client,
        Packet,
        None
    }

    private record Pending(int sequence, long expiry) {}

    private final Long2ObjectOpenHashMap<Pending> pending = new Long2ObjectOpenHashMap<>();
    private final Int2LongOpenHashMap attacked = new Int2LongOpenHashMap();
    private int ticks;
    private Item heldItem;
    private ItemStack heldStack = ItemStack.EMPTY;
    private int heldSince;
    private long lastDropRisk;

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
            if (state.isReplaceable() || BlockRules.isInteractable(state.getBlock())) continue;

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

    public boolean hasCrystals() {
        return mc.player.getOffHandStack().isOf(Items.END_CRYSTAL) || crystalSlot() != -1;
    }

    public boolean placeCrystal(BlockPos base, InventoryManager.SwitchMode mode, SwingMode swing, Runnable after) {
        BlockHitResult hit = new BlockHitResult(new Vec3d(base.getX() + 0.5, base.getY() + 1.0, base.getZ() + 0.5), Direction.UP, base, false);

        if (mc.player.getOffHandStack().isOf(Items.END_CRYSTAL)) {
            boolean[] done = {false};
            PacketBatch.run(() -> {
                mc.interactionManager.interactBlock(mc.player, Hand.OFF_HAND, hit);
                swing(swing, Hand.OFF_HAND);
                CrystalTracker.get().placed(base);
                if (after != null) after.run();
                done[0] = true;
            });
            return done[0];
        }

        int slot = crystalSlot();
        if (slot == -1) return false;

        return InventoryManager.get().withItem(slot, mode, () -> {
            mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hit);
            swing(swing);
            CrystalTracker.get().placed(base);
            if (after != null) after.run();
        });
    }

    private int crystalSlot() {
        return InventoryManager.get().find(stack -> stack.isOf(Items.END_CRYSTAL), 0, InventoryManager.MAIN_END);
    }

    public boolean attack(Entity target, InventoryManager.SwitchMode mode, SwingMode swing) {
        Runnable hit = () -> {
            mc.interactionManager.attackEntity(mc.player, target);
            swing(swing);
        };

        if (canHitWithHand()) {
            hit.run();
        } else {
            int burst = sharpWeaponSlot();
            if (burst != -1) {
                if (!InventoryManager.get().withItem(burst, mode, hit)) return false;
            } else {
                holdWeapon();
                return false;
            }
        }

        attacked.put(target.getId(), Util.getMeasuringTimeMs());
        if (target instanceof EndCrystalEntity && dropRisk(target.getEntityPos())) lastDropRisk = Util.getMeasuringTimeMs();
        return true;
    }

    public boolean recentDropRisk() {
        return Util.getMeasuringTimeMs() - lastDropRisk < LatencyManager.get().rtt() + DROP_WINDOW;
    }

    public boolean dropRisk(Vec3d crystal) {
        for (PlayerEntity player : mc.world.getPlayers()) {
            if (player == mc.player || !player.isAlive()) continue;
            if (ExplosionDamage.upperBound(player, player.getEntityPos(), crystal) >= player.getHealth() + player.getAbsorptionAmount()) return true;
        }

        BlockPos center = BlockPos.ofFloored(crystal);
        BlockPos.Mutable pos = new BlockPos.Mutable();
        for (int dx = -DROP_RADIUS; dx <= DROP_RADIUS; dx++) {
            for (int dy = -DROP_RADIUS; dy <= DROP_RADIUS; dy++) {
                for (int dz = -DROP_RADIUS; dz <= DROP_RADIUS; dz++) {
                    pos.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
                    BlockState state = mc.world.getBlockState(pos);
                    if (!state.isAir() && state.getFluidState().isEmpty() && state.getBlock().getBlastResistance() < DROP_RESISTANCE) return true;
                }
            }
        }
        return false;
    }

    public boolean canHitWithHand() {
        if (!mc.player.hasStatusEffect(StatusEffects.WEAKNESS)) return attackDamage(mc.player.getMainHandStack()) > 0;
        return ticks - heldSince >= WEAPON_SETTLE && attackDamage(heldStack) > 0;
    }

    public boolean canAttack() {
        return canHitWithHand() || sharpWeaponSlot() != -1;
    }

    public void prepareAttack() {
        if (!canAttack()) holdWeapon();
    }

    private void holdWeapon() {
        if (MiningManager.get().isHolding() || mc.interactionManager.isBreakingBlock()) return;
        if (mc.player.getMainHandStack().contains(DataComponentTypes.DEATH_PROTECTION)) return;

        InventoryManager inventory = InventoryManager.get();
        int best = -1;
        double bestDamage = 0;

        for (int i = 0; i < InventoryManager.HOTBAR_END; i++) {
            double damage = attackDamage(inventory.stack(i));
            if (damage > bestDamage) {
                bestDamage = damage;
                best = i;
            }
        }

        if (best != -1 && best != inventory.selectedSlot()) inventory.select(best);
    }

    private int sharpWeaponSlot() {
        for (int i = 0; i < InventoryManager.MAIN_END; i++) {
            if (sharpness(InventoryManager.get().stack(i)) > 0) return i;
        }
        return -1;
    }

    private static int sharpness(ItemStack stack) {
        for (var entry : stack.getEnchantments().getEnchantmentEntries()) {
            if (entry.getKey().matchesKey(Enchantments.SHARPNESS)) return entry.getIntValue();
        }
        return 0;
    }

    public boolean recentlyAttacked(Entity entity) {
        long time = attacked.getOrDefault(entity.getId(), Long.MIN_VALUE);
        return time != Long.MIN_VALUE && Util.getMeasuringTimeMs() - time < LatencyManager.get().timeout();
    }

    public void swing(SwingMode mode) {
        swing(mode, Hand.MAIN_HAND);
    }

    public void swing(SwingMode mode, Hand hand) {
        switch (mode) {
            case Client -> mc.player.swingHand(hand);
            case Packet -> mc.getNetworkHandler().sendPacket(new HandSwingC2SPacket(hand));
            case None -> {}
        }
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
        if (event.packet instanceof PlayerActionResponseS2CPacket packet && mc.world != null) {
            int acked = packet.sequence();
            if (acked > ((ClientWorldAccessor) mc.world).coyduck$getPendingUpdateManager().getSequence()) return;
            pending.values().removeIf(entry -> entry.sequence <= acked);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    private void onTick(TickEvent.Pre event) {
        ticks++;
        if (mc.player != null) {
            ItemStack main = mc.player.getMainHandStack();
            if (main.getItem() != heldItem) {
                heldItem = main.getItem();
                heldSince = ticks;
            }
            heldStack = main.copy();
        }

        long now = Util.getMeasuringTimeMs();
        pending.values().removeIf(entry -> entry.expiry <= now);

        long lifetime = LatencyManager.get().timeout();
        attacked.int2LongEntrySet().removeIf(entry -> now - entry.getLongValue() >= lifetime);
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        reset();
    }

    @EventHandler
    private void onWorldReset(WorldResetEvent event) {
        reset();
    }

    private void reset() {
        pending.clear();
        attacked.clear();
    }
}
