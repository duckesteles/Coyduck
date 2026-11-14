package dev.coyduck.modules;

import dev.coyduck.Coyduck;
import dev.coyduck.events.WorldResetEvent;
import dev.coyduck.managers.InteractionManager;
import dev.coyduck.managers.InventoryManager;
import dev.coyduck.managers.RotationManager;
import dev.coyduck.utils.BlockRules;
import dev.coyduck.utils.Targets;
import dev.coyduck.utils.Trace;
import it.unimi.dsi.fastutil.longs.LongSet;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.PistonBlock;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Items;
import net.minecraft.util.Util;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

public class Pusher extends Module {
    private static final int PRIORITY = 108;
    private static final double REACH = 4.45;
    private static final double LOOK_MARGIN = 0.15;
    private static final double[] FACE_POINTS = {-0.35, 0, 0.35};
    private static final EquipmentSlot[] ARMOR = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Double> targetRange = sgGeneral.add(new DoubleSetting.Builder()
        .name("target-range")
        .description("How far away a player can be to get pushed.")
        .defaultValue(5)
        .range(1, 6)
        .sliderRange(1, 6)
        .build()
    );

    private final Setting<Boolean> onlyHole = sgGeneral.add(new BoolSetting.Builder()
        .name("only-hole")
        .description("Only pushes players standing in a hole.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> ignoreNakeds = sgGeneral.add(new BoolSetting.Builder()
        .name("ignore-nakeds")
        .description("Leaves players without armor alone.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> delay = sgGeneral.add(new IntSetting.Builder()
        .name("delay")
        .description("Milliseconds between the piston and the power.")
        .defaultValue(150)
        .range(0, 1000)
        .sliderRange(0, 500)
        .build()
    );

    private final Setting<Boolean> autoDisable = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-disable")
        .description("Turns off once the piston has pushed.")
        .defaultValue(true)
        .build()
    );

    private final Setting<InventoryManager.SwitchMode> switchMode = sgGeneral.add(new EnumSetting.Builder<InventoryManager.SwitchMode>()
        .name("switch")
        .description("How to switch to the piston and the power.")
        .defaultValue(InventoryManager.SwitchMode.Silent)
        .build()
    );

    private final Setting<InteractionManager.SwingMode> swing = sgGeneral.add(new EnumSetting.Builder<InteractionManager.SwingMode>()
        .name("swing")
        .description("How to swing when placing.")
        .defaultValue(InteractionManager.SwingMode.Packet)
        .build()
    );

    private PlayerEntity target;
    private long nextAction;
    private BlockPos piston;

    public Pusher() {
        super(Coyduck.CATEGORY, "pusher", "Puts a piston next to the target's head and powers it, pushing them out of their hole for your crystals or mace.");
    }

    @Override
    public void onActivate() {
        target = null;
        piston = null;
        nextAction = 0;
    }

    @Override
    public String getInfoString() {
        return target == null ? null : target.getName().getString();
    }

    @EventHandler
    private void onWorldReset(WorldResetEvent event) {
        onActivate();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null || !mc.player.isAlive()) return;
        if (piston != null && pushed()) {
            Trace.log("pusher pushed {}", target == null ? "-" : target.getName().getString());
            if (autoDisable.get()) {
                toggle();
                return;
            }
            piston = null;
        }

        target = pick();
        if (target == null) {
            piston = null;
            return;
        }
        if (Util.getMeasuringTimeMs() < nextAction) return;

        BlockPos head = head(target);
        BlockPos ready = null;
        Direction free = null;
        for (Direction direction : Direction.Type.HORIZONTAL) {
            if (!pushable(head, direction)) continue;
            BlockPos at = head.offset(direction);
            BlockState state = mc.world.getBlockState(at);
            if (state.getBlock() instanceof PistonBlock) {
                if (state.get(PistonBlock.FACING) == direction.getOpposite() && !state.get(PistonBlock.EXTENDED)) ready = at;
                continue;
            }
            if (free == null && InteractionManager.get().canPlace(at) && !InteractionManager.get().wasRejected(at) && InteractionManager.get().obstructions(at).isEmpty()) free = direction;
        }

        if (ready != null) {
            piston = ready;
            if (power(ready, head)) nextAction = Util.getMeasuringTimeMs() + delay.get();
        } else if (free != null && placePiston(head, free)) {
            piston = head.offset(free);
            nextAction = Util.getMeasuringTimeMs() + delay.get();
        }
    }

    private PlayerEntity pick() {
        PlayerEntity keep = target != null && valid(target) ? target : null;
        if (keep != null) return keep;
        PlayerEntity best = null;
        double closest = Double.MAX_VALUE;
        for (PlayerEntity player : mc.world.getPlayers()) {
            if (!valid(player)) continue;
            double distance = player.squaredDistanceTo(mc.player);
            if (distance < closest) {
                closest = distance;
                best = player;
            }
        }
        return best;
    }

    private boolean valid(PlayerEntity player) {
        if (!Targets.valid(player, targetRange.get()) || !player.isOnGround() || player.getBoundingBox().getLengthY() <= 1) return false;
        if (ignoreNakeds.get() && naked(player)) return false;
        Box box = Targets.box(player);
        LongSet columns = Targets.columns(box);
        if (columns.size() != 1) return false;
        if (onlyHole.get() && !Targets.isHoled(columns)) return false;
        BlockPos head = head(player);
        BlockState headState = mc.world.getBlockState(head);
        if (headState.isOf(Blocks.OBSIDIAN) || headState.isOf(Blocks.CRYING_OBSIDIAN) || headState.isOf(Blocks.RESPAWN_ANCHOR) || headState.getHardness(mc.world, head) < 0) return false;
        return mc.world.getBlockState(head.up()).isReplaceable();
    }

    private static BlockPos head(PlayerEntity player) {
        return BlockPos.fromLong(Targets.columns(Targets.box(player)).iterator().nextLong()).up();
    }

    private boolean pushable(BlockPos head, Direction direction) {
        BlockPos at = head.offset(direction);
        if (Vec3d.ofCenter(at).squaredDistanceTo(mc.player.getEyePos()) > targetRange.get() * targetRange.get()) return false;
        BlockPos destination = head.offset(direction.getOpposite());
        if (!mc.world.getBlockState(destination).isReplaceable()) return false;
        if (mc.player.getBoundingBox().intersects(new Box(at))) return false;
        int walls = 0;
        for (Direction side : Direction.Type.HORIZONTAL) {
            if (!mc.world.getBlockState(head.down().offset(side)).isReplaceable()) walls++;
        }
        return walls <= 2 || !mc.world.getBlockState(destination.down()).isReplaceable();
    }

    private boolean placePiston(BlockPos head, Direction direction) {
        int slot = InventoryManager.get().find(stack -> stack.isOf(Items.PISTON) || stack.isOf(Items.STICKY_PISTON), 0, InventoryManager.MAIN_END);
        if (slot == -1) return false;
        BlockPos at = head.offset(direction);
        BlockHitResult hit = facingHit(at, direction);
        if (hit == null) return false;

        RotationManager rotations = RotationManager.get();
        Vec3d point = hit.getPos();
        float[] look = angles(point);
        boolean[] placed = {false};
        placed[0] = rotations.afterRotation(point, PRIORITY, () -> InventoryManager.get().withItem(slot, switchMode.get(), () -> {
            if (!mc.world.getBlockState(at).isReplaceable()) return;
            float yaw = mc.player.getYaw();
            float pitch = mc.player.getPitch();
            mc.player.setYaw(look[0]);
            mc.player.setPitch(look[1]);
            InteractionManager.get().place(at, hit, swing.get());
            mc.player.setYaw(yaw);
            mc.player.setPitch(pitch);
            Trace.log("pusher piston at {} facing {}", at, direction.getOpposite());
        }));
        return placed[0];
    }

    private boolean power(BlockPos at, BlockPos head) {
        for (Direction direction : Direction.values()) {
            BlockState state = mc.world.getBlockState(at.offset(direction));
            if (state.isOf(Blocks.REDSTONE_BLOCK) || state.isOf(Blocks.REDSTONE_TORCH) || state.isOf(Blocks.REDSTONE_WALL_TORCH)) return false;
        }
        InventoryManager inventory = InventoryManager.get();
        int block = inventory.find(stack -> stack.isOf(Items.REDSTONE_BLOCK), 0, InventoryManager.MAIN_END);
        int torch = block == -1 ? inventory.find(stack -> stack.isOf(Items.REDSTONE_TORCH), 0, InventoryManager.MAIN_END) : -1;
        if (block == -1 && torch == -1) return false;

        InteractionManager interaction = InteractionManager.get();
        for (Direction direction : Direction.values()) {
            BlockPos spot = at.offset(direction);
            if (spot.equals(head) || !interaction.canPlace(spot) || interaction.wasRejected(spot) || !interaction.obstructions(spot).isEmpty()) continue;
            BlockHitResult hit = interaction.hit(spot, block != -1);
            if (hit == null) continue;
            if (block == -1 && (hit.getBlockPos().equals(at) || hit.getSide() == Direction.DOWN || hit.getBlockPos().equals(spot))) continue;
            int slot = block != -1 ? block : torch;
            boolean[] placed = {false};
            boolean done = inventory.withItem(slot, switchMode.get(), () -> placed[0] = interaction.place(spot, hit, swing.get(), true, PRIORITY));
            if (done && placed[0]) {
                Trace.log("pusher power at {}", spot);
                return true;
            }
            return false;
        }
        return false;
    }

    private BlockHitResult facingHit(BlockPos at, Direction look) {
        Vec3d eye = mc.player.getEyePos();
        for (Direction direction : Direction.values()) {
            BlockPos support = at.offset(direction);
            BlockState state = mc.world.getBlockState(support);
            if (state.isReplaceable() || BlockRules.isInteractable(state.getBlock()) || !InteractionManager.centerReach(support)) continue;
            Direction side = direction.getOpposite();
            Vec3d normal = Vec3d.of(side.getVector());
            Vec3d center = Vec3d.ofCenter(support).add(normal.multiply(0.5));
            if (eye.subtract(center).dotProduct(normal) <= 0) continue;

            Direction.Axis axis = side.getAxis();
            Vec3d u = axis == Direction.Axis.X ? new Vec3d(0, 1, 0) : new Vec3d(1, 0, 0);
            Vec3d v = axis == Direction.Axis.Z ? new Vec3d(0, 1, 0) : new Vec3d(0, 0, 1);
            for (double a : FACE_POINTS) {
                for (double b : FACE_POINTS) {
                    Vec3d point = center.add(u.multiply(a)).add(v.multiply(b));
                    if (point.squaredDistanceTo(eye) > REACH * REACH || !looksAlong(eye, point, look) || !sees(eye, point, support)) continue;
                    return new BlockHitResult(point, side, support, false);
                }
            }
        }
        return null;
    }

    private static boolean looksAlong(Vec3d eye, Vec3d point, Direction look) {
        Vec3d dir = point.subtract(eye).normalize();
        double along = dir.x * look.getOffsetX() + dir.y * look.getOffsetY() + dir.z * look.getOffsetZ();
        double other = 0;
        for (Direction direction : Direction.values()) {
            if (direction == look) continue;
            other = Math.max(other, dir.x * direction.getOffsetX() + dir.y * direction.getOffsetY() + dir.z * direction.getOffsetZ());
        }
        return along > other + LOOK_MARGIN;
    }

    private boolean sees(Vec3d eye, Vec3d point, BlockPos support) {
        BlockHitResult ray = mc.world.raycast(new RaycastContext(eye, point, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, mc.player));
        return ray.getType() == HitResult.Type.MISS || ray.getBlockPos().equals(support) || ray.getPos().squaredDistanceTo(point) < 1.0E-4;
    }

    private float[] angles(Vec3d point) {
        Vec3d eye = mc.player.getEyePos();
        double dx = point.x - eye.x;
        double dy = point.y - eye.y;
        double dz = point.z - eye.z;
        float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90.0f;
        float pitch = MathHelper.clamp((float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz))), -90, 90);
        return new float[] {yaw, pitch};
    }

    private boolean pushed() {
        BlockState state = mc.world.getBlockState(piston);
        return state.getBlock() instanceof PistonBlock && state.get(PistonBlock.EXTENDED);
    }

    private static boolean naked(PlayerEntity player) {
        for (EquipmentSlot slot : ARMOR) {
            if (!player.getEquippedStack(slot).isEmpty()) return false;
        }
        return true;
    }
}
