package dev.coyduck.modules;

import dev.coyduck.Coyduck;
import dev.coyduck.utils.Trace;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

public class ElytraEvade extends Module {
    private static final double LOOKAHEAD = 3;
    private static final double GROUND_CLEARANCE = 3;
    private static final double HEIGHT_PULL = 1.0;
    private static final double ABOVE = 2;
    private static final double LEAD_TICKS = 5;
    private static final double OUTWARD_PULL = 3;
    private static final int FLIP_COOLDOWN = 10;
    private static final double RELEASE_MARGIN = 2;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Double> trigger = sgGeneral.add(new DoubleSetting.Builder()
        .name("trigger")
        .description("A player this close starts the evasion. Crystal placing, webs and hits reach about 5 to 6 blocks and a crystal blast about 12 more, so 14 reacts before any of them can land.")
        .defaultValue(14)
        .range(6, 40)
        .sliderRange(6, 40)
        .build()
    );

    private final Setting<Double> keep = sgGeneral.add(new DoubleSetting.Builder()
        .name("keep")
        .description("Distance held from the closest player while evading. Beyond about 18 blocks no crystal they can place reaches you.")
        .defaultValue(22)
        .range(8, 64)
        .sliderRange(8, 64)
        .build()
    );

    private final Setting<Double> speed = sgGeneral.add(new DoubleSetting.Builder()
        .name("speed")
        .description("Blocks per tick while evading.")
        .defaultValue(2.9)
        .range(0.5, 5)
        .sliderRange(0.5, 5)
        .build()
    );

    private final Setting<Double> maceRange = sgGeneral.add(new DoubleSetting.Builder()
        .name("mace-range")
        .description("A player holding a mace or gliding counts as close from this far, since a mace dive starts from far above.")
        .defaultValue(28)
        .range(6, 64)
        .sliderRange(6, 64)
        .build()
    );

    private final Setting<Boolean> matchHeight = sgGeneral.add(new BoolSetting.Builder()
        .name("match-height")
        .description("Climbs at full speed when the player climbs and stays a little above them, using their vertical speed to get there first, so nobody gets above you for a mace dive.")
        .defaultValue(true)
        .build()
    );

    private PlayerEntity threat;
    private double lastThreatY = Double.NaN;
    private int direction = 1;
    private int flipTicks;
    private double holdY = Double.NaN;

    public ElytraEvade() {
        super(Coyduck.CATEGORY, "elytra-evade", "While you fly with elytra-fly+, circles away from any player who gets close enough to crystal, web or hit you, and keeps a distance where their crystals can't reach. Stays out of mace-strike's way while it hunts. Does nothing while nobody is near, so you can leave it on.");
    }

    @Override
    public void onDeactivate() {
        release(null);
    }

    @Override
    public String getInfoString() {
        return threat == null ? null : threat.getName().getString();
    }

    @EventHandler(priority = EventPriority.HIGH)
    private void onTick(TickEvent.Pre event) {
        if (flipTicks > 0) flipTicks--;
        if (mc.player == null || mc.world == null || !mc.player.isGliding() || !Modules.get().isActive(ElytraFly.class)) {
            release(null);
            return;
        }
        MaceStrike mace = Modules.get().get(MaceStrike.class);
        if (mace != null && mace.hunting()) {
            release("mace-strike is hunting");
            return;
        }

        PlayerEntity nearest = nearest();
        double distance = nearest == null ? Double.MAX_VALUE : nearest.distanceTo(mc.player);
        double reach = nearest != null && maceThreat(nearest) ? Math.max(trigger.get(), maceRange.get()) : trigger.get();
        if (threat == null && distance > reach) return;
        if (nearest == null || distance > Math.max(keep.get(), reach) + RELEASE_MARGIN) {
            release("safe");
            return;
        }

        if (threat != nearest) lastThreatY = Double.NaN;
        if (threat != nearest) Trace.log("evade from {} at {} blocks", nearest.getName().getString(), distance);
        threat = nearest;
        if (Double.isNaN(holdY)) holdY = mc.player.getY();
        ElytraFly.pilot(motion(distance));
    }

    private Vec3d motion(double distance) {
        ElytraFly fly = Modules.get().get(ElytraFly.class);
        Vec3d me = mc.player.getEntityPos();
        Vec3d them = threat.getEntityPos();

        double dx = me.x - them.x;
        double dz = me.z - them.z;
        double flat = Math.sqrt(dx * dx + dz * dz);
        if (flat < 1.0E-3) {
            dx = 1;
            dz = 0;
            flat = 1;
        }
        double awayX = dx / flat;
        double awayZ = dz / flat;
        double out = MathHelper.clamp((keep.get() - distance) / keep.get() * OUTWARD_PULL, -0.5, 1);

        double climb = Double.isNaN(lastThreatY) ? 0 : them.y - lastThreatY;
        lastThreatY = them.y;
        double goalY = matchHeight.get() ? Math.max(holdY, them.y + Math.max(0, climb) * LEAD_TICKS + ABOVE) : holdY;
        double y = MathHelper.clamp((goalY - me.y) * HEIGHT_PULL, -fly.downSpeed(), fly.upSpeed());
        if (nearGround()) y = fly.upSpeed();

        Vec3d move = horizontal(awayX, awayZ, out);
        if (blocked(move, y) && flipTicks == 0) {
            direction = -direction;
            flipTicks = FLIP_COOLDOWN;
            move = horizontal(awayX, awayZ, out);
        }
        if (blocked(move, y)) y = fly.upSpeed();
        return new Vec3d(move.x, y, move.z);
    }

    private Vec3d horizontal(double awayX, double awayZ, double out) {
        double x = -awayZ * direction + awayX * out;
        double z = awayX * direction + awayZ * out;
        double length = Math.sqrt(x * x + z * z);
        return new Vec3d(x / length * speed.get(), 0, z / length * speed.get());
    }

    private boolean blocked(Vec3d move, double y) {
        Box ahead = mc.player.getBoundingBox().offset(move.x * LOOKAHEAD, y, move.z * LOOKAHEAD);
        return !mc.world.isSpaceEmpty(mc.player, ahead);
    }

    private static boolean maceThreat(PlayerEntity player) {
        return player.isGliding() || player.getMainHandStack().isOf(net.minecraft.item.Items.MACE) || player.getOffHandStack().isOf(net.minecraft.item.Items.MACE);
    }

    private boolean nearGround() {
        return !mc.world.isSpaceEmpty(mc.player, mc.player.getBoundingBox().stretch(0, -GROUND_CLEARANCE, 0));
    }

    private PlayerEntity nearest() {
        PlayerEntity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (PlayerEntity player : mc.world.getPlayers()) {
            if (player == mc.player || !player.isAlive() || player.isSpectator() || Friends.get().isFriend(player)) continue;
            double distance = player.distanceTo(mc.player);
            if (distance < bestDistance) {
                best = player;
                bestDistance = distance;
            }
        }
        return best;
    }

    private void release(String why) {
        if (threat != null && why != null) Trace.log("evade stops, {}", why);
        threat = null;
        holdY = Double.NaN;
        lastThreatY = Double.NaN;
    }
}
