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
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.block.BlockState;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.enchantment.Enchantments;
import dev.coyduck.utils.ExplosionDamage;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.decoration.AbstractDecorationEntity;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.entity.vehicle.VehicleEntity;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.HandSwingC2SPacket;
import net.minecraft.network.packet.s2c.play.ExplosionS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerActionResponseS2CPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.Util;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.util.ArrayDeque;
import java.util.List;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public final class InteractionManager {
    public static final int CLEAR_PRIORITY = 125;
    public static final int SURROUND_PRIORITY = 121;
    public static final int DEFENSE_PRIORITY = 120;
    public static final int TRAP_PRIORITY = 90;
    private static final float ROTATE_SPEED = 180;
    private static final double[][] FACE_POINTS = {{0, 0}, {0.3, 0.3}, {-0.3, 0.3}, {0.3, -0.3}, {-0.3, -0.3}, {0.45, 0}, {-0.45, 0}, {0, 0.45}, {0, -0.45}};
    private static final InteractionManager INSTANCE = new InteractionManager();

    private static final double REACH_MARGIN = 0.05;
    private static final int WEAPON_SETTLE = 2;
    private static final int DROP_RADIUS = 11;
    private static final double EXPLOSION_NOTICE = 20.0;
    private static final double OWN_TOLERANCE = 0.01;
    private static final float DROP_RESISTANCE = 600.0f;
    private static final long DROP_WINDOW = 50;

    public enum SwingMode {
        Client,
        Packet,
        None
    }

    private record Pending(int sequence, long expiry) {}

    private record OwnBreak(Vec3d position, long time) {}

    private final Long2ObjectOpenHashMap<Pending> pending = new Long2ObjectOpenHashMap<>();
    private final Int2LongOpenHashMap attacked = new Int2LongOpenHashMap();
    private int ticks;
    private Item heldItem;
    private ItemStack heldStack = ItemStack.EMPTY;
    private int heldSince;
    private volatile long lastDropRisk;
    private boolean trackDrops;
    private final ArrayDeque<OwnBreak> ownBreaks = new ArrayDeque<>();

    private InteractionManager() {}

    public static InteractionManager get() {
        return INSTANCE;
    }

    public boolean isPending(BlockPos pos) {
        Pending entry = pending.get(pos.asLong());
        return entry != null && entry.expiry > Util.getMeasuringTimeMs();
    }

    public void forget(BlockPos pos) {
        pending.remove(pos.asLong());
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
        // The clicked support counts against reach, and 6b6t holds clicks to 4.5; past that, airplace the cell itself.
        double reach = mc.player.getBlockInteractionRange() - REACH_MARGIN;
        double reachSquared = reach * reach;

        BlockHitResult facing = null;
        double facingDistance = Double.MAX_VALUE;
        BlockHitResult seen = null;
        double seenDistance = Double.MAX_VALUE;
        boolean support = false;

        for (Direction direction : Direction.values()) {
            BlockPos neighbor = pos.offset(direction);
            BlockState state = mc.world.getBlockState(neighbor);
            if (state.isReplaceable() || BlockRules.isInteractable(state.getBlock()) || isPending(neighbor)) continue;

            double distance = new Box(neighbor).squaredMagnitude(eye);
            if (distance >= reachSquared) continue;

            Direction side = direction.getOpposite();
            Vec3d normal = Vec3d.of(side.getVector());
            Vec3d center = Vec3d.ofCenter(neighbor).add(normal.multiply(0.5));
            boolean inside = new Box(neighbor).contains(eye);
            if (!inside && eye.subtract(center).dotProduct(normal) <= 0) continue;
            support = true;

            if (distance < facingDistance) {
                facing = new BlockHitResult(center, side, neighbor, false);
                facingDistance = distance;
            }
            if (distance >= seenDistance) continue;
            Vec3d point = inside ? center : visiblePoint(neighbor, side, eye, reachSquared);
            if (point != null) {
                seen = new BlockHitResult(point, side, neighbor, false);
                seenDistance = distance;
            }
        }

        if (seen != null) return seen;
        if (facing != null || support || !airplace) return facing;
        if (new Box(pos).squaredMagnitude(eye) >= reachSquared) return null;
        for (Direction direction : Direction.values()) {
            Vec3d point = visiblePoint(pos, direction, eye, reachSquared);
            if (point != null) return new BlockHitResult(point, direction, pos, false);
        }
        Direction side = null;
        double best = 0;
        for (Direction direction : Direction.values()) {
            Vec3d normal = Vec3d.of(direction.getVector());
            double facingBy = eye.subtract(Vec3d.ofCenter(pos).add(normal.multiply(0.5))).dotProduct(normal);
            if (facingBy > best) {
                side = direction;
                best = facingBy;
            }
        }
        return side == null ? null : new BlockHitResult(Vec3d.ofCenter(pos).add(Vec3d.of(side.getVector()).multiply(0.5)), side, pos, false);
    }

    public Vec3d visiblePoint(BlockPos block, Direction side, Vec3d eye, double reachSquared) {
        Vec3d normal = Vec3d.of(side.getVector());
        Vec3d center = Vec3d.ofCenter(block).add(normal.multiply(0.5));
        if (eye.subtract(center).dotProduct(normal) <= 0) return null;

        Direction.Axis axis = side.getAxis();
        Vec3d u = axis == Direction.Axis.X ? new Vec3d(0, 1, 0) : new Vec3d(1, 0, 0);
        Vec3d v = axis == Direction.Axis.Z ? new Vec3d(0, 1, 0) : new Vec3d(0, 0, 1);
        for (double[] offset : FACE_POINTS) {
            Vec3d point = center.add(u.multiply(offset[0])).add(v.multiply(offset[1]));
            if (point.squaredDistanceTo(eye) >= reachSquared) continue;
            if (sees(eye, point, block)) return point;
        }
        return null;
    }

    private boolean sees(Vec3d eye, Vec3d point, BlockPos block) {
        BlockHitResult ray = mc.world.raycast(new RaycastContext(eye, point, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, mc.player));
        return ray.getType() == HitResult.Type.MISS || ray.getBlockPos().equals(block) || ray.getPos().squaredDistanceTo(point) < 1.0E-4;
    }

    public void place(BlockPos pos, BlockHitResult hit, SwingMode swing) {
        place(pos, hit, swing, false, 0);
    }

    public boolean place(BlockPos pos, BlockHitResult hit, SwingMode swing, boolean rotate, int priority) {
        if (rotate && !aimed(hit.getPos(), new Box(hit.getBlockPos()), priority)) return false;
        mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hit);
        int sequence = ((ClientWorldAccessor) mc.world).coyduck$getPendingUpdateManager().getSequence();
        swing(swing);
        pending.put(pos.asLong(), new Pending(sequence, Util.getMeasuringTimeMs() + LatencyManager.get().timeout()));
        return true;
    }

    public boolean aimed(Vec3d point, Box box, int priority) {
        RotationManager rotations = RotationManager.get();
        rotations.request(priority, point, ROTATE_SPEED, true);
        double range = Math.max(mc.player.getBlockInteractionRange() + 1.0, mc.player.getEyePos().distanceTo(point) + 0.1);
        return rotations.isLookingAt(box, range);
    }

    public boolean ready(BlockPos pos, boolean airplace, boolean rotate, int priority) {
        if (!rotate) return true;
        BlockHitResult hit = hit(pos, airplace);
        if (hit == null && !airplace) hit = hit(pos.down(), false);
        return hit == null || aimed(hit.getPos(), new Box(hit.getBlockPos()), priority);
    }

    public Vec3d crystalAim(BlockPos base) {
        Vec3d point = visiblePoint(base, Direction.UP, mc.player.getEyePos(), Double.MAX_VALUE);
        return point != null ? point : new Vec3d(base.getX() + 0.5, base.getY() + 1.0, base.getZ() + 0.5);
    }



    public boolean hasCrystals() {
        return mc.player.getOffHandStack().isOf(Items.END_CRYSTAL) || crystalSlot() != -1;
    }

    public boolean placeCrystal(BlockPos base, InventoryManager.SwitchMode mode, SwingMode swing, Runnable after) {
        return placeCrystal(base, mode, swing, after, false);
    }

    public boolean placeCrystal(BlockPos base, InventoryManager.SwitchMode mode, SwingMode swing, Runnable after, boolean whileEating) {
        BlockHitResult hit = new BlockHitResult(crystalAim(base), Direction.UP, base, false);

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

        Runnable place = () -> {
            mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hit);
            swing(swing);
            CrystalTracker.get().placed(base);
            if (after != null) after.run();
        };
        InventoryManager inventory = InventoryManager.get();
        return whileEating ? inventory.withItemWhileEating(slot, mode, place) : inventory.withItem(slot, mode, place);
    }

    private int crystalSlot() {
        return InventoryManager.get().find(stack -> stack.isOf(Items.END_CRYSTAL), 0, InventoryManager.MAIN_END);
    }

    public boolean attack(Entity target, InventoryManager.SwitchMode mode, SwingMode swing) {
        return attack(target, mode, swing, false);
    }

    public boolean attack(Entity target, InventoryManager.SwitchMode mode, SwingMode swing, boolean whileEating) {
        return attack(target, mode, swing, whileEating, false);
    }

    public boolean attack(Entity target, InventoryManager.SwitchMode mode, SwingMode swing, boolean whileEating, boolean rotate) {
        return attack(target, mode, swing, whileEating, rotate, target.getBoundingBox().getCenter());
    }

    public Vec3d clearAim(Entity crystal, BlockPos cell, boolean airplace) {
        BlockHitResult place = hit(cell, airplace);
        Vec3d eye = mc.player.getEyePos();
        if (place != null && crystal.getBoundingBox().raycast(eye, place.getPos()).isPresent()) return place.getPos();
        return crystal.getBoundingBox().getCenter();
    }

    public boolean attack(Entity target, InventoryManager.SwitchMode mode, SwingMode swing, boolean whileEating, boolean rotate, Vec3d aim) {
        if (rotate && !aimed(aim, target.getBoundingBox(), CLEAR_PRIORITY)) return false;
        Runnable hit = () -> {
            mc.interactionManager.attackEntity(mc.player, target);
            swing(swing == SwingMode.None ? SwingMode.Packet : swing);
        };

        if (canHitWithHand()) {
            hit.run();
        } else {
            int burst = sharpWeaponSlot();
            if (burst != -1) {
                InventoryManager inventory = InventoryManager.get();
                if (!(whileEating ? inventory.withItemWhileEating(burst, mode, hit) : inventory.withItem(burst, mode, hit))) return false;
            } else {
                holdWeapon();
                return false;
            }
        }

        attacked.put(target.getId(), Util.getMeasuringTimeMs());
        if (target instanceof EndCrystalEntity) {
            noteOwnBreak(target.getEntityPos());
            if (trackDrops && dropRisk(target.getEntityPos())) lastDropRisk = Util.getMeasuringTimeMs();
        }
        return true;
    }

    public void trackDrops(boolean track) {
        trackDrops = track;
    }

    public void noteOwnBreak(Vec3d crystal) {
        long now = Util.getMeasuringTimeMs();
        synchronized (ownBreaks) {
            ownBreaks.removeIf(own -> now - own.time > LatencyManager.get().timeout());
            ownBreaks.addLast(new OwnBreak(crystal, now));
        }
    }

    private boolean isOwnBreak(Vec3d center) {
        long now = Util.getMeasuringTimeMs();
        synchronized (ownBreaks) {
            for (OwnBreak own : ownBreaks) {
                if (now - own.time <= LatencyManager.get().timeout() && own.position.squaredDistanceTo(center) < OWN_TOLERANCE) return true;
            }
        }
        return false;
    }

    public boolean recentDropRisk() {
        LatencyManager latency = LatencyManager.get();
        return Util.getMeasuringTimeMs() - lastDropRisk < latency.rtt() + 2L * latency.jitter() + DROP_WINDOW;
    }

    @EventHandler
    private void onReceive(PacketEvent.Receive event) {
        ClientPlayerEntity player = mc.player;
        if (!(event.packet instanceof ExplosionS2CPacket packet) || ExplosionDamage.isWind(packet) || player == null) return;
        if (packet.center().squaredDistanceTo(player.getEntityPos()) > EXPLOSION_NOTICE * EXPLOSION_NOTICE || isOwnBreak(packet.center())) return;
        lastDropRisk = Util.getMeasuringTimeMs();
    }

    public boolean dropRisk(Vec3d crystal) {
        double range = ExplosionDamage.CRYSTAL_RANGE * ExplosionDamage.CRYSTAL_RANGE;
        for (Entity entity : mc.world.getEntities()) {
            if (entity == mc.player || entity.squaredDistanceTo(crystal) > range) continue;
            if (entity instanceof ArmorStandEntity stand && stand.isMarker()) continue;
            if (entity instanceof AbstractDecorationEntity || entity instanceof ArmorStandEntity || entity instanceof VehicleEntity) return true;
            if (entity instanceof LivingEntity living && living.isAlive()
                && ExplosionDamage.upperBound(living, living.getEntityPos(), crystal) >= living.getHealth() + living.getAbsorptionAmount()) return true;
        }

        BlockPos center = BlockPos.ofFloored(crystal);
        BlockPos.Mutable pos = new BlockPos.Mutable();
        for (int dx = -DROP_RADIUS; dx <= DROP_RADIUS; dx++) {
            for (int dy = -DROP_RADIUS; dy <= DROP_RADIUS; dy++) {
                for (int dz = -DROP_RADIUS; dz <= DROP_RADIUS; dz++) {
                    if (dx * dx + dy * dy + dz * dz > DROP_RADIUS * DROP_RADIUS) continue;
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
        return ticks - heldSince >= WEAPON_SETTLE && mc.player.getMainHandStack().getItem() == heldItem && attackDamage(heldStack) > 0;
    }

    public boolean canAttack() {
        return canHitWithHand() || sharpWeaponSlot() != -1;
    }

    public void prepareAttack() {
        if (!canAttack()) holdWeapon();
    }

    private void holdWeapon() {
        if (MiningManager.get().isHolding() || mc.interactionManager.isBreakingBlock() || InventoryManager.get().wantsToEat()) return;
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
            ItemStack stack = InventoryManager.get().stack(i);
            if (sharpness(stack) > 0 && !stack.contains(DataComponentTypes.MINIMUM_ATTACK_CHARGE)) return i;
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
        if (stack.contains(DataComponentTypes.MINIMUM_ATTACK_CHARGE)) return 0;

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
