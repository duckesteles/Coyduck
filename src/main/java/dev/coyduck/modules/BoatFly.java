package dev.coyduck.modules;

import dev.coyduck.Coyduck;
import dev.coyduck.events.PacketApplyEvent;
import dev.coyduck.managers.LatencyManager;
import dev.coyduck.utils.Trace;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.entity.EntityMoveEvent;
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
import net.minecraft.entity.Entity;
import net.minecraft.entity.vehicle.AbstractBoatEntity;
import net.minecraft.network.packet.s2c.play.VehicleMoveS2CPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Locale;

public class BoatFly extends Module {
    private static final double SIDE_SLACK = 0.2;
    private static final double UP_SLACK = 0.45;
    private static final double ARRIVED = 0.5;
    private static final double LOOK_AHEAD = 16;
    private static final int FLOAT_LIMIT = 60;
    private static final double KICK_DIP = 0.04;
    private static final double FLOAT_MIN_FALL = -0.03125;
    private static final long RESET_WINDOW = 4000;
    private static final int RESET_LIMIT = 12;
    private static final double CUT = 0.6;
    private static final double RAMP = 0.02;
    private static final long RAMP_EVERY = 1500;
    private static final double FLOOR = 0.05;
    private static final long SETTLE = 150;
    private static final int BLAME_TICKS = 4;
    private static final int HISTORY = 4;
    private static final long REMOUNT_EVERY = 500;
    private static final int REMOUNT_TRIES = 10;
    private static final double REMOUNT_RANGE = 4.5;

    private enum Lane {
        Air("air", 0.38),
        Inside("inside", 0.2),
        Vertical("vertical", 0.2);

        final String label;
        final double start;

        Lane(String label, double start) {
            this.label = label;
            this.start = start;
        }
    }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgTravel = settings.createGroup("Travel");

    private final Setting<Double> speed = sgGeneral.add(new DoubleSetting.Builder()
        .name("speed")
        .description("Highest horizontal blocks per tick. With learn on the boat starts slower and speeds up to this only while the server accepts it.")
        .defaultValue(0.9)
        .range(0.05, 2)
        .sliderRange(0.05, 1.5)
        .build()
    );

    private final Setting<Double> verticalSpeed = sgGeneral.add(new DoubleSetting.Builder()
        .name("vertical-speed")
        .description("Highest blocks per tick up with jump and down with sneak. With learn on it works the same way as speed.")
        .defaultValue(0.5)
        .range(0.05, 1.4)
        .sliderRange(0.05, 1.4)
        .build()
    );

    private final Setting<Boolean> learn = sgGeneral.add(new BoolSetting.Builder()
        .name("learn")
        .description("Finds the fastest the server allows. Every time the server sets the boat back it slows down, and while moves go through it speeds up again a little at a time. Open air, inside blocks and up or down are learned apart, since the server treats them differently.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> phase = sgGeneral.add(new BoolSetting.Builder()
        .name("phase")
        .description("Lets the boat move through blocks. Each move only goes as far past a wall as the server forgives.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> remount = sgGeneral.add(new BoolSetting.Builder()
        .name("remount")
        .description("Gets back on the boat by itself when the server takes you off it, which it does when it sets the boat back.")
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
        .description("Height of the boat while travelling. At 127 in the nether the boat sits inside the top bedrock layer and your head is just above the roof, so nothing can reach you and you do not suffocate.")
        .defaultValue(127)
        .range(-64, 320)
        .sliderRange(0, 128)
        .build()
    );

    private final double[] pace = {Double.NaN, Double.NaN, Double.NaN};
    private final long[] cutAt = new long[3];
    private final long[] rampAt = new long[3];
    private final int[] usedTick = {Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
    private final ArrayDeque<Long> resets = new ArrayDeque<>();
    private final ArrayDeque<String> history = new ArrayDeque<>();

    private AbstractBoatEntity boat;
    private AbstractBoatEntity lastBoat;
    private boolean riding;
    private boolean warnedWall;
    private int ticks;
    private int floating;
    private int remountTries;
    private long remountAt;
    private long frozenUntil;
    private String status;

    public BoatFly() {
        super(Coyduck.CATEGORY, "boat-fly+", "Flies the boat you steer. Jump goes up and sneak goes down without getting off the boat, and with phase on it passes through floors, ceilings and walls. Learns how fast the server lets it go. Travel takes it to a set height, then to a set place, for going far away while you are AFK.");
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
        remountTries = 0;
        frozenUntil = 0;
        resets.clear();
        history.clear();
        status = null;
    }

    @Override
    public void onDeactivate() {
        release();
        lastBoat = null;
    }

    @Override
    public String getInfoString() {
        return status;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;
        ticks++;
        long time = System.currentTimeMillis();
        boolean now = mc.player.getVehicle() instanceof AbstractBoatEntity;
        if (riding && !now) {
            release();
            Trace.log("boat off, remount {}", remount.get() && lastBoat != null ? "tries" : "off");
        }
        if (now) remountTries = 0;
        riding = now;
        if (!now) {
            status = travel.get() ? "no boat" : null;
            tryRemount(time);
        }

        while (!resets.isEmpty() && time - resets.peekFirst() > RESET_WINDOW) resets.pollFirst();
        if (travel.get() && resets.size() >= RESET_LIMIT) {
            travel.set(false);
            resets.clear();
            warning("The server set the boat back %d times in a few seconds, travel stopped.", RESET_LIMIT);
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

    private void tryRemount(long time) {
        if (lastBoat == null || !remount.get()) return;
        boolean gone = lastBoat.isRemoved() || mc.player.getEyePos().distanceTo(lastBoat.getBoundingBox().getCenter()) > REMOUNT_RANGE;
        if (gone || remountTries >= REMOUNT_TRIES) {
            Trace.log("boat remount gave up after {} tries{}", remountTries, gone ? ", boat gone or too far" : "");
            lastBoat = null;
            if (travel.get()) {
                travel.set(false);
                warning("Could not get back on the boat, travel stopped.");
            }
            return;
        }
        if (time - remountAt < REMOUNT_EVERY) return;
        remountAt = time;
        remountTries++;
        mc.interactionManager.interactEntity(mc.player, lastBoat, Hand.MAIN_HAND);
        Trace.log("boat remount try {}", remountTries);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    private void onMove(EntityMoveEvent event) {
        if (!(event.entity instanceof AbstractBoatEntity vehicle) || vehicle.getControllingPassenger() != mc.player) return;
        if (boat != vehicle) {
            release();
            boat = vehicle;
        }
        lastBoat = vehicle;

        Vec3d want;
        if (System.currentTimeMillis() < frozenUntil) {
            status = "settling";
            want = new Vec3d(0, 0, 0);
        } else {
            want = travel.get() ? travelMove(vehicle) : manualMove(vehicle);
        }
        vehicle.noClip = phase.get();
        Vec3d move = phase.get() ? forgiven(vehicle, want) : want;
        move = antiKick(vehicle, move);
        vehicle.setVelocity(new Vec3d(0, 0, 0));
        remember(vehicle, want, move);
        ((IVec3d) event.movement).meteor$set(move.x, move.y, move.z);
    }

    @EventHandler
    private void onApply(PacketApplyEvent.Pre event) {
        if (!(event.packet instanceof VehicleMoveS2CPacket packet) || boat == null || mc.player.getVehicle() != boat) return;
        long time = System.currentTimeMillis();
        int rtt = LatencyManager.get().rtt();
        Vec3d from = boat.getEntityPos();
        Vec3d to = packet.position();
        resets.addLast(time);
        frozenUntil = time + rtt + SETTLE;

        StringBuilder cut = new StringBuilder();
        if (learn.get()) {
            int blame = BLAME_TICKS + rtt / 50;
            for (Lane lane : Lane.values()) {
                int i = lane.ordinal();
                if (ticks - usedTick[i] > blame || time - cutAt[i] < rtt + SETTLE + 100 || Double.isNaN(pace[i])) continue;
                pace[i] = Math.max(FLOOR, pace[i] * CUT);
                cutAt[i] = time;
                cut.append(' ').append(lane.label).append('=').append(num(pace[i]));
            }
        }
        Trace.log("boat set back gap {} offset {},{},{} y {} -> {}{} last moves [{}]",
            num(from.distanceTo(to)), num(to.x - from.x), num(to.y - from.y), num(to.z - from.z), num(from.y), num(to.y),
            cut.isEmpty() ? "" : " pace cut" + cut, String.join(" | ", history));
    }

    private Vec3d manualMove(AbstractBoatEntity vehicle) {
        float yaw = mc.player.getYaw();
        vehicle.setYaw(yaw);
        status = null;
        double forward = (mc.options.forwardKey.isPressed() ? 1 : 0) - (mc.options.backKey.isPressed() ? 1 : 0);
        double strafe = (mc.options.leftKey.isPressed() ? 1 : 0) - (mc.options.rightKey.isPressed() ? 1 : 0);
        double up = (mc.options.jumpKey.isPressed() ? 1 : 0) - (mc.options.sneakKey.isPressed() ? 1 : 0);
        double y = 0;
        if (up != 0) {
            y = up * pace(Lane.Vertical, verticalSpeed.get());
            used(Lane.Vertical, verticalSpeed.get());
        }
        if (forward == 0 && strafe == 0) return new Vec3d(0, y, 0);

        double rad = Math.toRadians(yaw);
        double sin = Math.sin(rad);
        double cos = Math.cos(rad);
        double x = strafe * cos - forward * sin;
        double z = forward * cos + strafe * sin;
        double length = Math.sqrt(x * x + z * z);
        x /= length;
        z /= length;
        Lane lane = lane(vehicle, x, z);
        double h = pace(lane, speed.get());
        used(lane, speed.get());
        return new Vec3d(x * h, y, z * h);
    }

    private Vec3d travelMove(AbstractBoatEntity vehicle) {
        Vec3d pos = vehicle.getEntityPos();
        double dy = cruiseY.get() - pos.y;
        if (!atCruise(vehicle)) {
            status = String.format(Locale.ROOT, "to y %.0f", cruiseY.get());
            double step = pace(Lane.Vertical, verticalSpeed.get());
            if (Math.abs(dy) > step) used(Lane.Vertical, verticalSpeed.get());
            return new Vec3d(0, MathHelper.clamp(dy, -step, step), 0);
        }

        double dx = targetX.get() + 0.5 - pos.x;
        double dz = targetZ.get() + 0.5 - pos.z;
        double flat = Math.sqrt(dx * dx + dz * dz);
        if (flat < ARRIVED) {
            travel.set(false);
            status = null;
            info("Arrived at %d, %d.", targetX.get(), targetZ.get());
            Trace.log("boat travel arrived, y {}", num(pos.y));
            return new Vec3d(0, 0, 0);
        }

        double x = dx / flat;
        double z = dz / flat;
        vehicle.setYaw((float) Math.toDegrees(Math.atan2(-dx, dz)));
        double ahead = Math.min(flat, LOOK_AHEAD);
        if (!loaded(pos.x + x * ahead, pos.z + z * ahead)) {
            status = "waiting for chunks";
            return new Vec3d(0, 0, 0);
        }
        Lane lane = lane(vehicle, x, z);
        double step = pace(lane, speed.get());
        if (flat > step) used(lane, speed.get());
        step = Math.min(step, flat);
        status = String.format(Locale.ROOT, "%.0f blocks", flat);
        return new Vec3d(x * step, 0, z * step);
    }

    private Lane lane(AbstractBoatEntity vehicle, double x, double z) {
        Box box = vehicle.getBoundingBox();
        double probe = pace(Lane.Air, speed.get());
        return inside(vehicle, box) || inside(vehicle, box.offset(x * probe, 0, z * probe)) ? Lane.Inside : Lane.Air;
    }

    private boolean inside(Entity vehicle, Box box) {
        return !mc.world.isSpaceEmpty(vehicle, box.contract(0.0625));
    }

    private double pace(Lane lane, double max) {
        if (!learn.get()) return max;
        int i = lane.ordinal();
        if (Double.isNaN(pace[i])) pace[i] = Math.min(lane.start, max);
        return Math.min(pace[i], max);
    }

    private void used(Lane lane, double max) {
        int i = lane.ordinal();
        usedTick[i] = ticks;
        if (!learn.get() || pace[i] >= max) return;
        long time = System.currentTimeMillis();
        if (time - cutAt[i] < RAMP_EVERY || time - rampAt[i] < RAMP_EVERY) return;
        pace[i] = Math.min(max, pace[i] + RAMP);
        rampAt[i] = time;
        Trace.log("boat {} pace up to {}", lane.label, num(pace[i]));
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

    private void remember(AbstractBoatEntity vehicle, Vec3d want, Vec3d move) {
        if (!Trace.on() || (want.lengthSquared() == 0 && move.lengthSquared() == 0)) return;
        boolean in = inside(vehicle, vehicle.getBoundingBox());
        history.addLast(String.format(Locale.ROOT, "y %.3f want %.2f,%.2f,%.2f sent %.2f,%.2f,%.2f %s",
            vehicle.getY(), want.x, want.y, want.z, move.x, move.y, move.z, in ? "in" : "air"));
        while (history.size() > HISTORY) history.pollFirst();
    }

    private static String num(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private void release() {
        if (boat != null) boat.noClip = false;
        boat = null;
        floating = 0;
    }
}
