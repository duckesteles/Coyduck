package dev.coyduck.modules;

import dev.coyduck.Coyduck;
import meteordevelopment.meteorclient.events.entity.player.PlayerMoveEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.mixininterface.IVec3d;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.block.BlockState;
import net.minecraft.entity.MovementType;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

public class HoleSnap extends Module {
    private static final float SAFE_RESISTANCE = 600;
    private static final int MAX_DROP = 3;
    private static final double PATH_STEP = 0.2;
    private static final double ARRIVE = 0.05;
    private static final int STUCK_TICKS = 10;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Integer> range = sgGeneral.add(new IntSetting.Builder()
        .name("range")
        .description("Horizontal range to look for holes in.")
        .defaultValue(5)
        .range(1, 8)
        .sliderRange(1, 8)
        .build()
    );

    private final Setting<Double> speed = sgGeneral.add(new DoubleSetting.Builder()
        .name("speed")
        .description("Blocks moved per tick towards the hole.")
        .defaultValue(0.28)
        .range(0.05, 1)
        .sliderRange(0.05, 1)
        .build()
    );

    private final Setting<Boolean> doubles = sgGeneral.add(new BoolSetting.Builder()
        .name("doubles")
        .description("Also snaps into 2x1 holes.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> timeout = sgGeneral.add(new IntSetting.Builder()
        .name("timeout")
        .description("Ticks to try before giving up.")
        .defaultValue(40)
        .range(5, 200)
        .sliderRange(5, 100)
        .build()
    );

    private Vec3d target;
    private int targetY;
    private int ticks;
    private int stuck;
    private double lastDistance;

    public HoleSnap() {
        super(Coyduck.CATEGORY, "hole-snap", "Moves you into the nearest safe hole.");
    }

    public static boolean snapping() {
        HoleSnap snap = Modules.get().get(HoleSnap.class);
        return snap != null && snap.isActive() && snap.target != null;
    }

    @Override
    public void onActivate() {
        target = null;
        ticks = 0;
        stuck = 0;
        lastDistance = Double.MAX_VALUE;

        if (mc.player == null || mc.world == null) {
            toggle();
            return;
        }
        if (inHole()) {
            toggle();
            return;
        }

        find();
        if (target == null) {
            info("No reachable hole nearby.");
            toggle();
        }
    }

    @Override
    public void onDeactivate() {
        target = null;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || target == null) {
            toggle();
            return;
        }

        if (inHole()) {
            mc.player.setVelocity(0, mc.player.getVelocity().y, 0);
            toggle();
            return;
        }

        double distance = horizontal(target);
        if (distance > lastDistance - 1.0E-3 && mc.player.getY() > targetY + 1.0E-3 && distance > ARRIVE) stuck++;
        else stuck = 0;
        lastDistance = distance;

        if (++ticks > timeout.get() || stuck > STUCK_TICKS) {
            info("Could not reach the hole.");
            toggle();
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    private void onMove(PlayerMoveEvent event) {
        if (event.type != MovementType.SELF || mc.player == null || target == null) return;

        double dx = target.x - mc.player.getX();
        double dz = target.z - mc.player.getZ();
        double distance = Math.sqrt(dx * dx + dz * dz);

        double x = 0;
        double z = 0;
        if (distance > 1.0E-4) {
            double step = Math.min(speed.get(), distance);
            x = dx / distance * step;
            z = dz / distance * step;
        }

        ((IVec3d) event.movement).meteor$set(x, event.movement.y, z);
        mc.player.setVelocity(0, mc.player.getVelocity().y, 0);
    }

    private void find() {
        BlockPos feet = BlockPos.ofFloored(mc.player.getX(), mc.player.getY() + 1.0E-3, mc.player.getZ());
        int r = range.get();
        double best = Double.MAX_VALUE;

        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                for (int dy = 0; dy >= -MAX_DROP; dy--) {
                    BlockPos pos = feet.add(dx, dy, dz);

                    Vec3d center = null;
                    if (single(pos)) {
                        center = new Vec3d(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
                    } else if (doubles.get()) {
                        for (Direction direction : new Direction[]{Direction.EAST, Direction.SOUTH}) {
                            if (!pair(pos, direction)) continue;
                            BlockPos other = pos.offset(direction);
                            center = new Vec3d((pos.getX() + other.getX()) / 2.0 + 0.5, pos.getY(), (pos.getZ() + other.getZ()) / 2.0 + 0.5);
                            break;
                        }
                    }
                    if (center == null) continue;

                    double distance = horizontal(center) + (feet.getY() - pos.getY()) * 0.5;
                    if (distance >= best || !reachable(center)) continue;

                    best = distance;
                    target = center;
                    targetY = pos.getY();
                }
            }
        }
    }

    private boolean single(BlockPos pos) {
        if (!open(pos) || !safe(pos.down())) return false;
        for (Direction direction : Direction.Type.HORIZONTAL) {
            if (!safe(pos.offset(direction))) return false;
        }
        return true;
    }

    private boolean pair(BlockPos pos, Direction direction) {
        BlockPos other = pos.offset(direction);
        if (!open(pos) || !open(other) || !safe(pos.down()) || !safe(other.down())) return false;

        for (Direction side : Direction.Type.HORIZONTAL) {
            if (side != direction && !safe(pos.offset(side))) return false;
            if (side != direction.getOpposite() && !safe(other.offset(side))) return false;
        }
        return true;
    }

    private boolean open(BlockPos pos) {
        return mc.world.getBlockState(pos).getCollisionShape(mc.world, pos).isEmpty()
            && mc.world.getBlockState(pos.up()).getCollisionShape(mc.world, pos.up()).isEmpty();
    }

    private boolean safe(BlockPos pos) {
        BlockState state = mc.world.getBlockState(pos);
        if (!state.isFullCube(mc.world, pos)) return false;
        return state.getHardness(mc.world, pos) < 0 || state.getBlock().getBlastResistance() >= SAFE_RESISTANCE;
    }

    private boolean reachable(Vec3d center) {
        Vec3d start = mc.player.getEntityPos();
        Vec3d flat = new Vec3d(center.x, start.y, center.z);
        double length = start.distanceTo(flat);
        Box box = mc.player.getBoundingBox();

        int steps = MathHelper.ceil(length / PATH_STEP);
        for (int i = 1; i <= steps; i++) {
            Vec3d point = start.lerp(flat, (double) i / steps);
            if (!mc.world.isSpaceEmpty(mc.player, box.offset(point.subtract(start)))) return false;
        }

        Box drop = box.offset(flat.subtract(start)).stretch(0, center.y - start.y, 0).contract(1.0E-3);
        return mc.world.isSpaceEmpty(mc.player, drop);
    }

    private boolean inHole() {
        if (!mc.player.isOnGround()) return false;
        BlockPos feet = BlockPos.ofFloored(mc.player.getX(), mc.player.getY() + 1.0E-3, mc.player.getZ());
        if (target != null && (feet.getY() != targetY || horizontal(target) > ARRIVE)) return false;
        if (target != null) return true;

        if (single(feet)) return true;
        if (!doubles.get()) return false;
        for (Direction direction : Direction.Type.HORIZONTAL) {
            if (pair(feet, direction)) return true;
        }
        return false;
    }

    private double horizontal(Vec3d pos) {
        double dx = pos.x - mc.player.getX();
        double dz = pos.z - mc.player.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }
}
