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
        .description("The four spots sit this far from where the evasion started, north, east, south and west, so you keep to the same area.")
        .defaultValue(16)
        .range(6, 48)
        .sliderRange(6, 48)
        .build()
    );

    private final Setting<Double> height = sgGeneral.add(new DoubleSetting.Builder()
        .name("height")
        .description("North and south spots are this much higher, east and west this much lower, so every move between spots also goes up or down.")
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

    private Vec3d anchor;
    private long calmSince;
    private int spot = -1;
    private PlayerEntity threat;

    public ElytraEvade() {
        super(Coyduck.CATEGORY, "elytra-evade", "While you fly with elytra-fly+, keeps four spots around where trouble started and flies to whichever is safest from players who could crystal you or glide in for a mace dive, moving on when a spot gets dangerous. Stays out of mace-strike's way while it hunts and does nothing while nobody is a danger, so you can leave it on.");
    }

    @Override
    public void onDeactivate() {
        if (threat != null) ElytraFly.pilot(null);
        anchor = null;
        spot = -1;
        threat = null;
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

        List<Vec3d> spots = spots();
        int best = -1;
        double bestScore = Double.MAX_VALUE;
        for (int i = 0; i < spots.size(); i++) {
            Vec3d at = spots.get(i);
            if (at == null) continue;
            double score = risk(at);
            if (score < bestScore) {
                best = i;
                bestScore = score;
            }
        }
        if (best == -1) return;

        if (spot == -1 || spots.get(spot) == null || risk(spots.get(spot)) >= minDamage.get() || risk(spots.get(spot)) > bestScore + SWITCH_GAIN) {
            if (spot != best) Trace.log("evade goes to spot {} {} (risk {})", best, spots.get(best), bestScore);
            spot = best;
        }
        ElytraFly.pilot(toward(spots.get(spot)));
    }

    private void calm(String why) {
        if (threat != null) Trace.log("evade stops, {}", why);
        if (threat != null) ElytraFly.pilot(null);
        threat = null;
        spot = -1;
        calmSince = System.currentTimeMillis();
    }

    private List<Vec3d> spots() {
        double r = radius.get();
        double h = height.get();
        double[][] offsets = {{0, h, -r}, {r, -h, 0}, {0, h, r}, {-r, -h, 0}};
        List<Vec3d> out = new ArrayList<>();
        for (double[] offset : offsets) out.add(free(anchor.add(offset[0], offset[1], offset[2])));
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
        double distance = player.getEntityPos().distanceTo(at);
        double range = maceRange.get() + extra;
        return distance >= range ? 0 : GLIDER_DANGER * (1 - distance / range) + minDamage.get();
    }

    private float potential(PlayerEntity player, Vec3d at, double extra) {
        Vec3d eye = player.getEyePos();
        Box body = mc.player.getBoundingBox().offset(at.subtract(mc.player.getEntityPos()));
        Vec3d toward = body.getCenter().subtract(eye);
        double distance = toward.length();
        double range = reach.get() + extra;
        if (distance <= range) return Float.MAX_VALUE;
        Vec3d crystal = eye.add(toward.multiply(range / distance));
        return ExplosionDamage.crystal(mc.player, at, body, crystal, mc.world);
    }

    private boolean enemy(PlayerEntity player) {
        return player != mc.player && player.isAlive() && !player.isSpectator() && !Friends.get().isFriend(player);
    }
}
