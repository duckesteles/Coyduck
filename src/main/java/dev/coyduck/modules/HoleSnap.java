package dev.coyduck.modules;

import dev.coyduck.Coyduck;
import meteordevelopment.meteorclient.events.entity.player.PlayerMoveEvent;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.mixininterface.IVec3d;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.world.Timer;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.entity.MovementType;
import net.minecraft.network.packet.s2c.play.PlayerPositionLookS2CPacket;
import net.minecraft.util.Util;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.List;

public class HoleSnap extends Module {
    private static final float SAFE_RESISTANCE = 600;
    private static final double ARRIVE = 1.0E-3;
    private static final double JUMP = 0.42;
    private static final long MAX_TIME = 3_000;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgSpeed = settings.createGroup("Speed");
    private final SettingGroup sgHole = settings.createGroup("Hole");

    private final Setting<Integer> range = sgGeneral.add(new IntSetting.Builder()
        .name("range")
        .description("Horizontal range to look for holes in.")
        .defaultValue(3)
        .range(0, 6)
        .sliderRange(0, 6)
        .build()
    );

    private final Setting<Integer> downRange = sgGeneral.add(new IntSetting.Builder()
        .name("down-range")
        .description("How far below you a hole may be.")
        .defaultValue(3)
        .range(0, 6)
        .sliderRange(0, 6)
        .build()
    );

    private final Setting<Boolean> jump = sgGeneral.add(new BoolSetting.Builder()
        .name("jump")
        .description("Jumps over anything in the way.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> jumpCooldown = sgGeneral.add(new IntSetting.Builder()
        .name("jump-cooldown")
        .description("Ticks between jumps.")
        .defaultValue(5)
        .range(0, 40)
        .sliderRange(0, 40)
        .visible(jump::get)
        .build()
    );

    private final Setting<Integer> collisions = sgGeneral.add(new IntSetting.Builder()
        .name("collisions-to-disable")
        .description("Gives up after running into a wall this many moves in a row. 0 never gives up.")
        .defaultValue(15)
        .range(0, 100)
        .sliderRange(0, 100)
        .build()
    );

    private final Setting<Integer> rubberbands = sgGeneral.add(new IntSetting.Builder()
        .name("rubberbands-to-disable")
        .description("Gives up after the server pulls you back this many times. 0 never gives up.")
        .defaultValue(1)
        .range(0, 20)
        .sliderRange(0, 20)
        .build()
    );

    private final Setting<Double> speed = sgSpeed.add(new DoubleSetting.Builder()
        .name("speed")
        .description("Blocks moved per move towards the hole.")
        .defaultValue(0.2873)
        .range(0.01, 1)
        .sliderRange(0.01, 1)
        .build()
    );

    private final Setting<Boolean> boost = sgSpeed.add(new BoolSetting.Builder()
        .name("boost")
        .description("Moves faster for the first few moves.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Double> boostSpeed = sgSpeed.add(new DoubleSetting.Builder()
        .name("boost-speed")
        .description("Blocks moved per boosted move.")
        .defaultValue(0.5)
        .range(0.01, 1)
        .sliderRange(0.01, 1)
        .visible(boost::get)
        .build()
    );

    private final Setting<Integer> boostMoves = sgSpeed.add(new IntSetting.Builder()
        .name("boost-moves")
        .description("How many moves are boosted.")
        .defaultValue(3)
        .range(1, 20)
        .sliderRange(1, 10)
        .visible(boost::get)
        .build()
    );

    private final Setting<Double> timer = sgSpeed.add(new DoubleSetting.Builder()
        .name("timer")
        .description("Speeds up the game while snapping, so you are in the hole almost instantly. 1 leaves it alone.")
        .defaultValue(10)
        .range(1, 20)
        .sliderRange(1, 20)
        .build()
    );

    private final Setting<Boolean> singles = sgHole.add(new BoolSetting.Builder()
        .name("single")
        .description("Targets 1x1 holes.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> doubles = sgHole.add(new BoolSetting.Builder()
        .name("double")
        .description("Targets 2x1 holes.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> quads = sgHole.add(new BoolSetting.Builder()
        .name("quad")
        .description("Targets 2x2 holes.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> depth = sgHole.add(new IntSetting.Builder()
        .name("depth")
        .description("Free blocks a hole needs above its floor.")
        .defaultValue(3)
        .range(2, 5)
        .sliderRange(2, 5)
        .build()
    );

    private final Setting<Boolean> safeOnly = sgHole.add(new BoolSetting.Builder()
        .name("safe-only")
        .description("Only targets holes made of blast proof blocks like obsidian and bedrock.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> retarget = sgHole.add(new BoolSetting.Builder()
        .name("retarget")
        .description("Picks a new hole when the chosen one gets blocked, instead of giving up.")
        .defaultValue(true)
        .build()
    );

    private record Hole(List<BlockPos> cells, Vec3d middle) {}

    private Hole hole;
    private int collided;
    private int pulled;
    private int jumpTicks;
    private int boostLeft;
    private long startedAt;

    public HoleSnap() {
        super(Coyduck.CATEGORY, "hole-snap", "Moves you into the nearest hole, then turns itself off.");
    }

    public static boolean snapping() {
        HoleSnap snap = Modules.get().get(HoleSnap.class);
        return snap != null && snap.isActive() && snap.hole != null;
    }

    @Override
    public void onActivate() {
        collided = 0;
        pulled = 0;
        jumpTicks = 0;
        boostLeft = boost.get() ? boostMoves.get() : 0;
        startedAt = Util.getMeasuringTimeMs();
        hole = mc.player == null ? null : find();
    }

    @Override
    public void onDeactivate() {
        hole = null;
        Modules.get().get(Timer.class).setOverride(Timer.OFF);
    }

    @EventHandler
    private void onReceive(PacketEvent.Receive event) {
        if (!(event.packet instanceof PlayerPositionLookS2CPacket) || rubberbands.get() == 0) return;
        if (++pulled >= rubberbands.get()) mc.execute(() -> stop("Pulled back by the server."));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    private void onMove(PlayerMoveEvent event) {
        if (event.type != MovementType.SELF || mc.player == null || mc.world == null) return;
        if (!mc.player.isAlive()) {
            stop(null);
            return;
        }

        if (hole != null && blocked(hole)) hole = retarget.get() ? find() : null;
        if (hole == null) {
            stop("No hole to snap into.");
            return;
        }

        Vec3d middle = hole.middle;
        double dx = middle.x - mc.player.getX();
        double dz = middle.z - mc.player.getZ();

        if (Math.abs(dx) < ARRIVE && Math.abs(dz) < ARRIVE) {
            ((IVec3d) event.movement).meteor$setXZ(0, 0);
            mc.player.setVelocity(0, mc.player.getVelocity().y, 0);
            if (Math.abs(mc.player.getY() - middle.y) < ARRIVE) stop(null);
            else if (standing()) stop("Cannot drop into the hole.");
            return;
        }

        Modules.get().get(Timer.class).setOverride(timer.get());

        double distance = Math.sqrt(dx * dx + dz * dz);
        double step = Math.min(boostLeft-- > 0 ? boostSpeed.get() : speed.get(), distance);
        double x = dx / distance * step;
        double z = dz / distance * step;

        if (!mc.world.isSpaceEmpty(mc.player, mc.player.getBoundingBox().offset(x, 0, z))) {
            if (collisions.get() > 0 && ++collided >= collisions.get()) {
                stop("Ran into a wall.");
                return;
            }
        } else {
            collided = 0;
        }

        if (jumpTicks > 0) jumpTicks--;
        else if (jump.get() && standing()) {
            jumpTicks = jumpCooldown.get();
            ((IVec3d) event.movement).meteor$setY(JUMP);
        }

        ((IVec3d) event.movement).meteor$setXZ(x, z);
        mc.player.setVelocity(0, mc.player.getVelocity().y, 0);
    }

    private void stop(String reason) {
        if (!isActive()) return;
        if (reason != null) info(reason);
        toggle();
    }

    private boolean standing() {
        return !mc.world.isSpaceEmpty(mc.player, mc.player.getBoundingBox().offset(0, -0.05, 0));
    }

    private Hole find() {
        BlockPos feet = mc.player.getBlockPos();
        Vec3d position = mc.player.getEntityPos();
        Hole best = null;
        double bestDistance = Double.MAX_VALUE;

        for (int dx = -range.get(); dx <= range.get(); dx++) {
            for (int dz = -range.get(); dz <= range.get(); dz++) {
                for (int dy = -downRange.get(); dy <= 0; dy++) {
                    Hole candidate = hole(feet.add(dx, dy, dz));
                    if (candidate == null || covered(candidate, feet.getY() + 1) || occupied(candidate)) continue;
                    if (dy == 0 && candidate.cells.contains(feet)) return candidate;

                    double distance = candidate.middle.squaredDistanceTo(position);
                    if (distance < bestDistance) {
                        best = candidate;
                        bestDistance = distance;
                    }
                }
            }
        }
        return best;
    }

    private Hole hole(BlockPos pos) {
        if (!open(pos) || !wall(pos.west()) || !wall(pos.north())) return null;

        boolean east = open(pos.east()) && wall(pos.east().north()) && wall(pos.east(2));
        boolean south = open(pos.south()) && wall(pos.south().west()) && wall(pos.south(2));

        if (singles.get() && !east && !south && wall(pos.east()) && wall(pos.south())) {
            return new Hole(List.of(pos), new Vec3d(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5));
        }

        if (quads.get() && east && south && open(pos.south().east()) && wall(pos.east(2).south()) && wall(pos.south(2).east())) {
            return new Hole(List.of(pos, pos.east(), pos.south(), pos.south().east()), new Vec3d(pos.getX() + 1, pos.getY(), pos.getZ() + 1));
        }

        if (!doubles.get()) return null;
        if (east && !south && wall(pos.south()) && wall(pos.south().east())) {
            return new Hole(List.of(pos, pos.east()), new Vec3d(pos.getX() + 1, pos.getY(), pos.getZ() + 0.5));
        }
        if (south && !east && wall(pos.east()) && wall(pos.south().east())) {
            return new Hole(List.of(pos, pos.south()), new Vec3d(pos.getX() + 0.5, pos.getY(), pos.getZ() + 1));
        }
        return null;
    }

    private boolean open(BlockPos pos) {
        if (!wall(pos.down())) return false;
        for (int i = 0; i < depth.get(); i++) {
            BlockState state = mc.world.getBlockState(pos.up(i));
            if (collidable(pos.up(i)) || state.isOf(Blocks.COBWEB) || state.isIn(BlockTags.FIRE) || state.getFluidState().isIn(FluidTags.LAVA)) return false;
        }
        return true;
    }

    private boolean occupied(Hole target) {
        for (BlockPos cell : target.cells) {
            Box box = new Box(cell).stretch(0, depth.get() - 1, 0);
            if (!mc.world.getOtherEntities(mc.player, box.contract(0.2, 0, 0.2), entity -> entity instanceof PlayerEntity).isEmpty()) return true;
            if (!mc.world.getOtherEntities(mc.player, box.expand(2), entity -> entity instanceof EndCrystalEntity crystal && inCell(crystal.getBlockPos(), cell)).isEmpty()) return true;
        }
        return false;
    }

    private boolean inCell(BlockPos pos, BlockPos cell) {
        return pos.getX() == cell.getX() && pos.getZ() == cell.getZ() && pos.getY() >= cell.getY() && pos.getY() < cell.getY() + depth.get();
    }

    private boolean wall(BlockPos pos) {
        if (!collidable(pos)) return false;
        if (!safeOnly.get()) return true;

        BlockState state = mc.world.getBlockState(pos);
        return state.getHardness(mc.world, pos) < 0 || state.getBlock().getBlastResistance() >= SAFE_RESISTANCE;
    }

    private boolean collidable(BlockPos pos) {
        return !mc.world.getBlockState(pos).getCollisionShape(mc.world, pos).isEmpty();
    }

    private boolean covered(Hole target, int top) {
        for (BlockPos cell : target.cells) {
            for (int y = cell.getY() + depth.get(); y <= top; y++) {
                if (collidable(cell.withY(y))) return true;
            }
        }
        return false;
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        if (isActive()) toggle();
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null) return;
        if (mc.player.hasVehicle()) stop("Cannot snap while riding.");
        else if (Util.getMeasuringTimeMs() - startedAt > MAX_TIME) stop("Took too long to reach the hole.");
    }

    private boolean blocked(Hole target) {
        for (BlockPos cell : target.cells) {
            if (collidable(cell)) return true;
        }
        return occupied(target);
    }
}
