package dev.coyduck.modules;

import dev.coyduck.Coyduck;
import dev.coyduck.utils.ExplosionDamage;
import dev.coyduck.utils.Trace;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.block.Blocks;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

public class ElytraEvade extends Module {
    private static final double GROUND_CLEARANCE = 3;
    private static final double LIFT_STEP = 1;
    private static final double MAX_LIFT = 12;
    private static final double ARRIVED = 0.6;
    private static final double SWITCH_GAIN = 2;
    private static final double GLIDER_DANGER = 20;
    private static final long ANCHOR_TIMEOUT = 30_000;
    private static final int PATH_SAMPLES = 8;
    private static final int DIRECTIONS = 8;
    private static final int HORIZON = 10;
    private static final double ABOVE_WEIGHT = 0.5;
    private static final double BELOW_WEIGHT = 1.5;
    private static final double FACING_WEIGHT = 0.6;
    private static final double CRYSTAL_RADIUS = 12;

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
        .description("Extra reach assumed while evading, so it only calms down once you are this much further out than they could hurt you from.")
        .defaultValue(4)
        .range(0, 16)
        .sliderRange(0, 16)
        .build()
    );

    private final Setting<Double> radius = sgGeneral.add(new DoubleSetting.Builder()
        .name("radius")
        .description("How far each hop goes. Spots are picked around you at this distance and half of it, in eight directions.")
        .defaultValue(16)
        .range(6, 48)
        .sliderRange(6, 48)
        .build()
    );

    private final Setting<Double> height = sgGeneral.add(new DoubleSetting.Builder()
        .name("height")
        .description("Spots are also tried this much higher and lower than you, so a hop can climb over a glider or drop away from it.")
        .defaultValue(6)
        .range(0, 20)
        .sliderRange(0, 20)
        .build()
    );

    private final Setting<Double> speed = sgGeneral.add(new DoubleSetting.Builder()
        .name("speed")
        .description("Blocks per tick while moving between spots.")
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

    private final Setting<Double> leash = sgGeneral.add(new DoubleSetting.Builder()
        .name("leash")
        .description("Spots stay within this many blocks of where the evasion started, so you do not drift away from your fight.")
        .defaultValue(48)
        .range(16, 160)
        .sliderRange(16, 160)
        .build()
    );

    private final java.util.Map<Integer, Vec3d> lastSeen = new java.util.HashMap<>();
    private final java.util.Map<Integer, Vec3d> motion = new java.util.HashMap<>();
    private Vec3d anchor;
    private long calmSince;
    private Vec3d goal;
    private PlayerEntity threat;

    public ElytraEvade() {
        super(Coyduck.CATEGORY, "elytra-evade", "While you fly with elytra-fly+, keeps hopping between spots around you that are safest from players who could crystal you or glide in for a dive. Gliders are judged by where they are heading, whether they are above you and where they look, and the whole way to a spot is checked, not just the spot. Stays near where trouble started, stays out of mace-strike's way while it hunts and does nothing while nobody is a danger, so you can leave it on.");
    }

    @Override
    public void onDeactivate() {
        if (threat != null) ElytraFly.pilot(null);
        anchor = null;
        goal = null;
        threat = null;
        lastSeen.clear();
        motion.clear();
    }

    @Override
    public String getInfoString() {
        return threat == null ? null : threat.getName().getString();
    }

    public boolean evading() {
        return isActive() && threat != null && Modules.get().isActive(ElytraFly.class);
    }

    @EventHandler(priority = EventPriority.HIGH)
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;
        trackMotion();
        if (!Modules.get().isActive(ElytraFly.class)) {
            if (threat != null) calm("elytra-fly+ is off");
            return;
        }
        MaceStrike mace = Modules.get().get(MaceStrike.class);
        if (mace != null && mace.hunting()) {
            calm("mace-strike is hunting");
            return;
        }

        boolean evading = threat != null;
        PlayerEntity danger = dangerous(mc.player.getEntityPos(), evading ? margin.get() : 0);
        long now = System.currentTimeMillis();
        if (danger == null) {
            if (evading) calm("nobody can hurt you");
            if (anchor != null && now - calmSince > ANCHOR_TIMEOUT) anchor = null;
            return;
        }

        if (!mc.player.isGliding()) {
            if (evading && !mc.player.isOnGround() && mc.player.getVelocity().y < 0 && mc.player.checkGliding()) {
                mc.getNetworkHandler().sendPacket(new ClientCommandC2SPacket(mc.player, ClientCommandC2SPacket.Mode.START_FALL_FLYING));
                Trace.log("evade restarted the glide");
            } else if (evading && (mc.player.isOnGround() || mc.player.isTouchingWater() || mc.player.hasVehicle())) {
                calm("not gliding");
            }
            return;
        }

        if (threat != danger) Trace.log("evade from {} at {} blocks{}", danger.getName().getString(), danger.distanceTo(mc.player), danger.isGliding() ? ", gliding" : "");
        threat = danger;
        if (anchor == null) anchor = mc.player.getEntityPos();

        Vec3d best = null;
        double bestScore = Double.MAX_VALUE;
        for (Vec3d at : spots()) {
            double score = risk(at);
            if (score < bestScore) {
                best = at;
                bestScore = score;
            }
        }
        if (best == null) return;

        boolean arrived = goal != null && goal.distanceTo(mc.player.getEntityPos()) < ARRIVED * 2;
        double current = goal == null || arrived || !stillFree(goal) ? Double.MAX_VALUE : risk(goal);
        if (current >= minDamage.get() || current > bestScore + SWITCH_GAIN) {
            Trace.log("evade goes to {} (risk {}{})", best, bestScore, arrived ? ", arrived at the last spot" : "");
            goal = best;
        }
        ElytraFly.pilot(safeStep(toward(goal)));
    }

    private void trackMotion() {
        java.util.Set<Integer> present = new java.util.HashSet<>();
        for (PlayerEntity player : mc.world.getPlayers()) {
            if (player == mc.player) continue;
            int id = player.getId();
            present.add(id);
            Vec3d now = player.getEntityPos();
            Vec3d before = lastSeen.put(id, now);
            Vec3d step = before == null ? Vec3d.ZERO : now.subtract(before);
            if (step.lengthSquared() > 100) step = Vec3d.ZERO;
            Vec3d old = motion.get(id);
            motion.put(id, old == null ? step : old.multiply(0.5).add(step.multiply(0.5)));
        }
        lastSeen.keySet().retainAll(present);
        motion.keySet().retainAll(present);
    }

    private boolean stillFree(Vec3d at) {
        Box body = mc.player.getBoundingBox().offset(at.subtract(mc.player.getEntityPos()));
        return mc.world.isSpaceEmpty(mc.player, body) && !webbed(body);
    }

    private void calm(String why) {
        if (threat != null) Trace.log("evade stops, {}", why);
        if (threat != null) ElytraFly.pilot(null);
        threat = null;
        goal = null;
        calmSince = System.currentTimeMillis();
    }

    private List<Vec3d> spots() {
        Vec3d me = mc.player.getEntityPos();
        double r = radius.get();
        double h = height.get();
        double limit = leash.get();
        List<Vec3d> out = new ArrayList<>();
        for (double distance : new double[] {r * 0.5, r}) {
            for (int i = 0; i < DIRECTIONS; i++) {
                double angle = Math.PI * 2 * i / DIRECTIONS;
                for (double dy : new double[] {-h, 0, h}) {
                    Vec3d raw = me.add(Math.cos(angle) * distance, dy, Math.sin(angle) * distance);
                    double fx = raw.x - anchor.x;
                    double fz = raw.z - anchor.z;
                    if (fx * fx + fz * fz > limit * limit) continue;
                    Vec3d at = free(raw);
                    if (at != null) out.add(at);
                }
            }
        }
        return out;
    }

    private Vec3d free(Vec3d at) {
        for (double lift = 0; lift <= MAX_LIFT; lift += LIFT_STEP) {
            Vec3d up = at.add(0, lift, 0);
            Box body = mc.player.getBoundingBox().offset(up.subtract(mc.player.getEntityPos()));
            if (!mc.world.isSpaceEmpty(mc.player, body) || webbed(body)) continue;
            if (!mc.world.isSpaceEmpty(mc.player, body.stretch(0, -GROUND_CLEARANCE, 0))) continue;
            if (!clearPath(up)) continue;
            return up;
        }
        return null;
    }

    private boolean clearPath(Vec3d to) {
        Vec3d from = mc.player.getEntityPos();
        Box body = mc.player.getBoundingBox();
        for (int i = 1; i <= PATH_SAMPLES; i++) {
            Vec3d step = from.lerp(to, i / (double) PATH_SAMPLES).subtract(from);
            if (webbed(body.offset(step))) return false;
        }
        return true;
    }

    private boolean webbed(Box box) {
        for (BlockPos pos : BlockPos.iterate(BlockPos.ofFloored(box.minX, box.minY, box.minZ), BlockPos.ofFloored(box.maxX, box.maxY, box.maxZ))) {
            if (mc.world.getBlockState(pos).isOf(Blocks.COBWEB)) return true;
        }
        return false;
    }

    private Vec3d safeStep(Vec3d move) {
        Box body = mc.player.getBoundingBox();
        for (double scale = 1; scale >= 0.125; scale /= 2) {
            Vec3d step = move.multiply(scale);
            if (mc.world.isSpaceEmpty(mc.player, body.stretch(step))) return step;
        }
        return Vec3d.ZERO;
    }

    private Vec3d toward(Vec3d goal) {
        ElytraFly fly = Modules.get().get(ElytraFly.class);
        Vec3d delta = goal.subtract(mc.player.getEntityPos());
        double flat = Math.sqrt(delta.x * delta.x + delta.z * delta.z);
        if (flat < ARRIVED && Math.abs(delta.y) < ARRIVED) return Vec3d.ZERO;
        double move = Math.min(flat, speed.get());
        double x = flat < 1.0E-4 ? 0 : delta.x / flat * move;
        double z = flat < 1.0E-4 ? 0 : delta.z / flat * move;
        double y = MathHelper.clamp(delta.y, -fly.downSpeed(), fly.upSpeed());
        return new Vec3d(x, y, z);
    }

    private PlayerEntity dangerous(Vec3d at, double extra) {
        PlayerEntity best = null;
        double bestScore = 0;
        for (PlayerEntity player : mc.world.getPlayers()) {
            if (!enemy(player)) continue;
            double score = Math.max(glider(player, at, extra), potential(player, at, extra));
            if (score >= minDamage.get() && score > bestScore) {
                best = player;
                bestScore = score;
            }
        }
        return best;
    }

    private double risk(Vec3d at) {
        double total = 0;
        for (PlayerEntity player : mc.world.getPlayers()) {
            if (!enemy(player)) continue;
            total += Math.max(glider(player, at, 0), Math.min(potential(player, at, 0), GLIDER_DANGER * 2));
        }
        return total;
    }

    private double glider(PlayerEntity player, Vec3d at, double extra) {
        if (!player.isGliding()) return 0;
        Vec3d me = mc.player.getEntityPos();
        Vec3d them = player.getEntityPos();
        Vec3d velocity = motion.getOrDefault(player.getId(), Vec3d.ZERO);
        Vec3d path = at.subtract(me);
        double length = path.length();
        double pace = Math.max(speed.get(), 0.1);

        double closest = Double.MAX_VALUE;
        for (int tick = 0; tick <= HORIZON; tick++) {
            Vec3d ours = length < 1.0E-4 ? me : me.add(path.multiply(Math.min(1, pace * tick / length)));
            Vec3d theirs = them.add(velocity.multiply(tick));
            closest = Math.min(closest, weighted(theirs, ours));
        }
        closest = Math.min(closest, weighted(them.add(velocity.multiply(HORIZON)), at));

        double range = maceRange.get() + extra;
        if (closest >= range) return 0;
        Vec3d look = player.getRotationVector();
        Vec3d to = at.subtract(player.getEyePos());
        double facing = to.lengthSquared() < 1.0E-6 ? 1 : Math.max(0, look.dotProduct(to.normalize()));
        return (GLIDER_DANGER * (1 - closest / range) + minDamage.get()) * (1 + FACING_WEIGHT * facing);
    }

    private static double weighted(Vec3d them, Vec3d us) {
        double dx = them.x - us.x;
        double dz = them.z - us.z;
        double dy = them.y - us.y;
        double vertical = dy * (dy > 0 ? ABOVE_WEIGHT : BELOW_WEIGHT);
        return Math.sqrt(dx * dx + dz * dz + vertical * vertical);
    }

    private float potential(PlayerEntity player, Vec3d at, double extra) {
        Vec3d eye = player.getEyePos();
        Box body = mc.player.getBoundingBox().offset(at.subtract(mc.player.getEntityPos()));
        Vec3d toward = body.getCenter().subtract(eye);
        double distance = toward.length();
        double range = reach.get() + extra;
        if (distance <= range) return Float.MAX_VALUE;
        if (distance > range + CRYSTAL_RADIUS) return 0;
        Vec3d crystal = eye.add(toward.multiply(range / distance));
        return ExplosionDamage.crystal(mc.player, at, body, crystal, mc.world);
    }

    private boolean enemy(PlayerEntity player) {
        return player != mc.player && player.isAlive() && !player.isSpectator() && !Friends.get().isFriend(player);
    }
}
