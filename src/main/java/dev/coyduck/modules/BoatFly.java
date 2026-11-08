package dev.coyduck.modules;

import dev.coyduck.Coyduck;
import dev.coyduck.utils.Trace;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.entity.EntityMoveEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
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
import net.minecraft.entity.Entity;
import net.minecraft.entity.vehicle.AbstractBoatEntity;
import net.minecraft.network.packet.s2c.play.VehicleMoveS2CPacket;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayDeque;
import java.util.List;

public class BoatFly extends Module {
    private static final double SIDE_SLACK = 0.2;
    private static final double UP_SLACK = 0.45;
    private static final double ARRIVED = 0.5;
    private static final double LOOK_AHEAD = 16;
    private static final int FLOAT_LIMIT = 60;
    private static final double KICK_DIP = 0.04;
    private static final double FLOAT_MIN_FALL = -0.03125;
    private static final long RESET_WINDOW = 2000;
    private static final int RESET_LIMIT = 10;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgTravel = settings.createGroup("Travel");

    private final Setting<Double> speed = sgGeneral.add(new DoubleSetting.Builder()
        .name("speed")
        .description("Horizontal blocks per tick. NoCheatPlus allows a boat about 0.4 when it counts it as on the ground, so raise this only if the server lets you.")
        .defaultValue(0.38)
        .range(0.05, 2)
        .sliderRange(0.05, 1.5)
        .build()
    );

    private final Setting<Double> verticalSpeed = sgGeneral.add(new DoubleSetting.Builder()
        .name("vertical-speed")
        .description("Blocks per tick up with jump and down with sneak. NoCheatPlus allows a boat to climb about 0.27 per move.")
        .defaultValue(0.25)
        .range(0.05, 1.4)
        .sliderRange(0.05, 1.4)
        .build()
    );

    private final Setting<Boolean> phase = sgGeneral.add(new BoolSetting.Builder()
        .name("phase")
        .description("Lets the boat move through blocks. Each move only goes as far past a wall as the server forgives, so it is not set back.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> antiKick = sgGeneral.add(new BoolSetting.Builder()
        .name("anti-kick")
        .description("Dips the boat a little every few seconds while nothing is around it, so the server does not kick you for flying.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> travel = sgTravel.add(new BoolSetting.Builder()
        .name("travel")
        .description("Goes up or down to cruise-y first, then flies to the target and stops there. Turns itself off when you arrive.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> targetX = sgTravel.add(new IntSetting.Builder()
        .name("target-x")
        .description("X of the place to travel to.")
        .defaultValue(0)
        .range(-30_000_000, 30_000_000)
        .sliderRange(-100_000, 100_000)
        .build()
    );

    private final Setting<Integer> targetZ = sgTravel.add(new IntSetting.Builder()
        .name("target-z")
        .description("Z of the place to travel to.")
        .defaultValue(0)
        .range(-30_000_000, 30_000_000)
        .sliderRange(-100_000, 100_000)
        .build()
    );

    private final Setting<Double> cruiseY = sgTravel.add(new DoubleSetting.Builder()
        .name("cruise-y")
        .description("Height of the boat while travelling. At 127 in the nether the boat sits inside the top bedrock layer and your head is just above the roof, so nothing can reach you and you do not suffocate. Lower than that your head is inside blocks and you take suffocation damage.")
        .defaultValue(127)
        .range(-64, 320)
        .sliderRange(0, 128)
        .build()
    );

    private AbstractBoatEntity boat;
    private boolean riding;
    private boolean warnedWall;
    private int floating;
    private volatile int pendingResets;
    private final ArrayDeque<Long> resets = new ArrayDeque<>();
    private String status;

    public BoatFly() {
        super(Coyduck.CATEGORY, "boat-fly+", "Flies the boat you steer. Jump goes up and sneak goes down without getting off the boat, and with phase on it passes through floors, ceilings and walls. Travel takes it to a set height, then to a set place, for going far away while you are AFK.");
    }

    public static boolean keepSeat() {
        BoatFly module = Modules.get().get(BoatFly.class);
        return module != null && module.isActive() && MeteorClient.mc.player != null && MeteorClient.mc.player.getVehicle() instanceof AbstractBoatEntity;
    }

    @Override
    public void onActivate() {
        riding = false;
        warnedWall = false;
        floating = 0;
        pendingResets = 0;
        resets.clear();
        status = null;
    }

    @Override
    public void onDeactivate() {
        release();
    }

    @Override
    public String getInfoString() {
        return status;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;
        boolean now = mc.player.getVehicle() instanceof AbstractBoatEntity;
        if (riding && !now) {
            release();
            if (travel.get()) {
                travel.set(false);
                warning("You are off the boat, travel stopped.");
            }
        }
        riding = now;
        if (!now) status = travel.get() ? "no boat" : null;

        long time = System.currentTimeMillis();
        for (int i = pendingResets; i > 0; i--) resets.addLast(time);
        pendingResets = 0;
        while (!resets.isEmpty() && time - resets.peekFirst() > RESET_WINDOW) resets.pollFirst();
        if (travel.get() && resets.size() >= RESET_LIMIT) {
            travel.set(false);
            resets.clear();
            warning("The server keeps setting the boat back, travel stopped. Lower speed or vertical-speed.");
        }

        if (travel.get() && now && atCruise(mc.player.getVehicle()) && mc.player.isInsideWall()) {
            if (!warnedWall) {
                warnedWall = true;
                warning("Your head is inside blocks at this cruise-y, so you take suffocation damage. Raise it until your head is above the roof, 127 in the nether.");
            }
        } else {
            warnedWall = false;
        }
    }

    @EventHandler
    private void onMove(EntityMoveEvent event) {
        if (!(event.entity instanceof AbstractBoatEntity vehicle) || vehicle.getControllingPassenger() != mc.player) return;
        if (boat != vehicle) {
            release();
            boat = vehicle;
        }

        Vec3d want = travel.get() ? travelMove(vehicle) : manualMove(vehicle);
        vehicle.noClip = phase.get();
        Vec3d move = phase.get() ? forgiven(vehicle, want) : want;
        move = antiKick(vehicle, move);
        vehicle.setVelocity(Vec3d.ZERO);
        ((IVec3d) event.movement).meteor$set(move.x, move.y, move.z);
    }

    @EventHandler
    private void onReceive(PacketEvent.Receive event) {
        if (!(event.packet instanceof VehicleMoveS2CPacket packet) || boat == null) return;
        pendingResets++;
        Trace.log("boat set back by the server to {} from {}", packet.position(), boat.getEntityPos());
    }

    private Vec3d manualMove(AbstractBoatEntity vehicle) {
        float yaw = mc.player.getYaw();
        vehicle.setYaw(yaw);
        double forward = (mc.options.forwardKey.isPressed() ? 1 : 0) - (mc.options.backKey.isPressed() ? 1 : 0);
        double strafe = (mc.options.leftKey.isPressed() ? 1 : 0) - (mc.options.rightKey.isPressed() ? 1 : 0);
        double y = (mc.options.jumpKey.isPressed() ? verticalSpeed.get() : 0) - (mc.options.sneakKey.isPressed() ? verticalSpeed.get() : 0);
        status = null;
        if (forward == 0 && strafe == 0) return new Vec3d(0, y, 0);
        double rad = Math.toRadians(yaw);
        double sin = Math.sin(rad);
        double cos = Math.cos(rad);
        double x = strafe * cos - forward * sin;
        double z = forward * cos + strafe * sin;
        double length = Math.sqrt(x * x + z * z);
        return new Vec3d(x / length * speed.get(), y, z / length * speed.get());
    }

    private Vec3d travelMove(AbstractBoatEntity vehicle) {
        Vec3d pos = vehicle.getEntityPos();
        double dy = cruiseY.get() - pos.y;
        if (!atCruise(vehicle)) {
            status = String.format("to y %.0f", cruiseY.get());
            return new Vec3d(0, MathHelper.clamp(dy, -verticalSpeed.get(), verticalSpeed.get()), 0);
        }

        double dx = targetX.get() + 0.5 - pos.x;
        double dz = targetZ.get() + 0.5 - pos.z;
        double flat = Math.sqrt(dx * dx + dz * dz);
        if (flat < ARRIVED) {
            travel.set(false);
            status = null;
            info("Arrived at %d, %d.", targetX.get(), targetZ.get());
            Trace.log("boat travel arrived at {}", pos);
            return Vec3d.ZERO;
        }

        vehicle.setYaw((float) Math.toDegrees(Math.atan2(-dx, dz)));
        double step = Math.min(speed.get(), flat);
        double ahead = Math.min(flat, LOOK_AHEAD);
        if (!loaded(pos.x + dx / flat * ahead, pos.z + dz / flat * ahead)) {
            status = "waiting for chunks";
            return Vec3d.ZERO;
        }
        status = String.format("%.0f blocks", flat);
        return new Vec3d(dx / flat * step, 0, dz / flat * step);
    }

    private boolean atCruise(Entity vehicle) {
        return Math.abs(cruiseY.get() - vehicle.getY()) < 1.0E-3;
    }

    private boolean loaded(double x, double z) {
        return mc.world.getChunkManager().isChunkLoaded(ChunkSectionPos.getSectionCoord(x), ChunkSectionPos.getSectionCoord(z));
    }

    private Vec3d forgiven(AbstractBoatEntity vehicle, Vec3d want) {
        Vec3d free = Entity.adjustMovementForCollisions(vehicle, want, vehicle.getBoundingBox(), mc.world, List.of());
        double y = free.y + MathHelper.clamp(want.y - free.y, -UP_SLACK, UP_SLACK);
        double ex = want.x - free.x;
        double ez = want.z - free.z;
        double error = Math.sqrt(ex * ex + ez * ez);
        if (error > SIDE_SLACK) {
            ex *= SIDE_SLACK / error;
            ez *= SIDE_SLACK / error;
        }
        return new Vec3d(free.x + ex, y, free.z + ez);
    }

    private Vec3d antiKick(AbstractBoatEntity vehicle, Vec3d move) {
        if (!antiKick.get() || move.y < FLOAT_MIN_FALL || nearBlocks(vehicle)) {
            floating = 0;
            return move;
        }
        if (++floating < FLOAT_LIMIT) return move;
        floating = 0;
        return new Vec3d(move.x, -KICK_DIP, move.z);
    }

    private boolean nearBlocks(AbstractBoatEntity vehicle) {
        Box around = vehicle.getBoundingBox().expand(0.0625).stretch(0, -0.55, 0);
        return BlockPos.stream(around).anyMatch(pos -> !mc.world.getBlockState(pos).isAir());
    }

    private void release() {
        if (boat != null) boat.noClip = false;
        boat = null;
        floating = 0;
    }
}
