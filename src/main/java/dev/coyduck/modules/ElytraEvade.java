package dev.coyduck.modules;

import dev.coyduck.Coyduck;
import dev.coyduck.utils.ExplosionDamage;
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
    private static final int FLIP_COOLDOWN = 10;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Double> minDamage = sgGeneral.add(new DoubleSetting.Builder()
        .name("min-damage")
        .description("Evades only from players who could hurt you this much with one crystal, placed at the closest point they can reach, walls and blocks between counted.")
        .defaultValue(4)
        .range(0.5, 20)
        .sliderRange(0.5, 20)
        .build()
    );

    private final Setting<Double> reach = sgGeneral.add(new DoubleSetting.Builder()
        .name("reach")
        .description("How far a player can place a crystal from their eyes. 6b6t enforces about 4.5.")
        .defaultValue(5)
        .range(3, 7)
        .sliderRange(3, 7)
        .build()
    );

    private final Setting<Double> margin = sgGeneral.add(new DoubleSetting.Builder()
        .name("margin")
        .description("Extra reach assumed while evading, so you only stop once you are this much further out than they could hurt you from.")
        .defaultValue(4)
        .range(0, 16)
        .sliderRange(0, 16)
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
        .description("A gliding player counts as a threat from this far, since a mace dive starts from far above.")
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
        super(Coyduck.CATEGORY, "elytra-evade", "While you fly with elytra-fly+, moves sideways and away from any player who could hurt you with a crystal at the closest point they can reach, or who glides near you for a mace dive, until none of them could. Stays out of mace-strike's way while it hunts. Does nothing while nobody is near, so you can leave it on.");
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

        PlayerEntity danger = dangerous(threat == null ? 0 : margin.get());
        if (danger == null) {
            release("nobody can hurt you");
            return;
        }

        if (threat != danger) {
            lastThreatY = Double.NaN;
            float could = potential(danger, 0);
            Trace.log("evade from {} at {} blocks{}", danger.getName().getString(), danger.distanceTo(mc.player), danger.isGliding() ? ", gliding" : could == Float.MAX_VALUE ? ", in reach" : ", could hit you for " + String.format(java.util.Locale.ROOT, "%.1f", could));
        }
        threat = danger;
        if (Double.isNaN(holdY)) holdY = mc.player.getY();
        ElytraFly.pilot(motion());
    }

    private PlayerEntity dangerous(double extra) {
        PlayerEntity best = null;
        double bestScore = 0;
        for (PlayerEntity player : mc.world.getPlayers()) {
            if (player == mc.player || !player.isAlive() || player.isSpectator() || Friends.get().isFriend(player)) continue;
            double score = player.isGliding() && player.distanceTo(mc.player) <= maceRange.get() + extra ? Double.MAX_VALUE / 2 - player.distanceTo(mc.player) : potential(player, extra);
            if (score >= minDamage.get() && score > bestScore) {
                best = player;
                bestScore = score;
            }
        }
        return best;
    }

    private float potential(PlayerEntity player, double extra) {
        Vec3d eye = player.getEyePos();
        Vec3d body = mc.player.getBoundingBox().getCenter();
        Vec3d toward = body.subtract(eye);
        double distance = toward.length();
        double range = reach.get() + extra;
        if (distance <= range) return Float.MAX_VALUE;
        Vec3d crystal = eye.add(toward.multiply(range / distance));
        return ExplosionDamage.crystal(mc.player, crystal);
    }

    private Vec3d motion() {
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
        double out = 1;

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

    private boolean nearGround() {
        return !mc.world.isSpaceEmpty(mc.player, mc.player.getBoundingBox().stretch(0, -GROUND_CLEARANCE, 0));
    }

    private void release(String why) {
        if (threat != null && why != null) Trace.log("evade stops, {}", why);
        threat = null;
        holdY = Double.NaN;
        lastThreatY = Double.NaN;
    }
}
