package dev.coyduck.modules;

import dev.coyduck.Coyduck;
import dev.coyduck.managers.LatencyManager;
import dev.coyduck.utils.ExplosionDamage;
import dev.coyduck.utils.Trace;
import meteordevelopment.meteorclient.events.game.GameJoinedEvent;
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
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.registry.Registries;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

public class ElytraEvade extends Module {
    private static final double GROUND_CLEARANCE = 3;
    private static final double LIFT_STEP = 2;
    private static final double MAX_LIFT = 12;
    private static final double ARRIVED = 0.6;
    private static final double SWITCH_GAIN = 2;
    private static final double SWITCH_SHARE = 0.15;
    private static final double GLIDER_DANGER = 20;
    private static final long ANCHOR_TIMEOUT = 30_000;
    private static final int PATH_SAMPLES = 8;
    private static final int DIRECTIONS = 8;
    private static final int FALLBACK_DIRECTIONS = 16;
    private static final int HORIZON = 10;
    private static final int INTERPOLATION_TICKS = 3;
    private static final double ABOVE_WEIGHT = 0.5;
    private static final double BELOW_WEIGHT = 1.5;
    private static final double FACING_WEIGHT = 0.3;
    private static final double CRYSTAL_RADIUS = 12;
    private static final double WEB_REACH = 5.5;
    private static final double WEB_CLEARANCE = 1.0;
    private static final double PATH_WEB_CLEARANCE = 0.3;
    private static final long BAN_MS = 2_000;
    private static final double BAN_COS = Math.cos(Math.toRadians(30));
    private static final double LEVEL_BAND = 1;
    private static final int STUCK_TICKS = 4;
    private static final double STUCK_MOVE = 0.3;
    private static final double SIGHT_FACTOR = 0.35;
    private static final double COVER_FACTOR = 0.5;
    private static final double COVER_HEIGHT = 4;
    private static final double COVER_IGNORED_WITHIN = 12;
    private static final double LOW_GROUND = 6;
    private static final double CLOSING_SPEED = 0.3;
    private static final double OVERHEAD_HEIGHT = 3;
    private static final double OVERHEAD_RANGE = 10;
    private static final double DIVE_SPEED = 0.5;
    private static final double SEGMENT = 2;
    private static final int RESCAN_TICKS = 4;
    private static final int RESCAN_TICKS_RISKY = 2;
    private static final int FULL_CHECKS = 8;
    private static final int GLIDE_RETRY_TICKS = 5;
    private static final int MANUAL_TICKS = 20;

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
        .description("How far each hop goes. Spots are picked around you at this distance and half of it.")
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
        .description("A diving player counts as a threat from this far, since a mace or spear dive starts from far above. Gliders only count when they hold a mace or spear, close in on you or hang over you.")
        .defaultValue(28)
        .range(6, 64)
        .sliderRange(6, 64)
        .build()
    );

    private final Setting<Double> leash = sgGeneral.add(new DoubleSetting.Builder()
        .name("leash")
        .description("Spots try to stay within this many blocks of where the evasion started, so you do not drift away from your fight. Going past it costs, it is not a wall.")
        .defaultValue(48)
        .range(16, 160)
        .sliderRange(16, 160)
        .build()
    );

    private final Setting<Boolean> yieldToKeys = sgGeneral.add(new BoolSetting.Builder()
        .name("yield-to-keys")
        .description("Hands the flight back to you for a second whenever you press a movement key, so you can always steer out yourself.")
        .defaultValue(true)
        .build()
    );

    private final Map<Integer, Vec3d> lastSeen = new HashMap<>();
    private final Map<Integer, Vec3d> motion = new HashMap<>();
    private final List<PlayerEntity> near = new ArrayList<>();
    private final List<Vec3d> bannedWays = new ArrayList<>();
    private final List<Integer> bannedLevels = new ArrayList<>();
    private final List<Long> bannedUntil = new ArrayList<>();
    private final ArrayDeque<Vec3d> trail = new ArrayDeque<>();
    private final Set<Long> startHazards = new HashSet<>();
    private final Set<Integer> present = new HashSet<>();
    private final Random random = new Random();
    private Vec3d anchor;
    private long calmSince;
    private Vec3d goal;
    private PlayerEntity threat;
    private double phase;
    private double lead;
    private int ticks;
    private int nextScan;
    private int lastGlideTry = -GLIDE_RETRY_TICKS;
    private int lastManual = -MANUAL_TICKS;
    private boolean webbed;
    private ClientWorld world;

    public ElytraEvade() {
        super(Coyduck.CATEGORY, "elytra-evade", "While you fly with elytra-fly+, keeps hopping between spots around you that are safest from players who could crystal you, web you or dive at you with a mace or spear. Enemies are judged where they will really be once your packets land, the whole way to a spot is checked, webs and web reach are kept clear of, and a way it cannot fly is dropped for a moment so you never sit still under threat. Stays near where trouble started, yields when you steer, stays out of mace-strike's way while it hunts and does nothing while nobody is a danger, so you can leave it on.");
    }

    @Override
    public void onDeactivate() {
        if (threat != null) ElytraFly.pilot(null);
        reset();
    }

    @Override
    public String getInfoString() {
        return threat == null ? null : threat.getName().getString();
    }

    public boolean evading() {
        return isActive() && threat != null && Modules.get().isActive(ElytraFly.class);
    }

    @EventHandler
    private void onJoin(GameJoinedEvent event) {
        reset();
    }

    private void reset() {
        anchor = null;
        goal = null;
        threat = null;
        lastSeen.clear();
        motion.clear();
        near.clear();
        bannedWays.clear();
        bannedLevels.clear();
        bannedUntil.clear();
        trail.clear();
        world = null;
    }

    @EventHandler(priority = EventPriority.HIGH)
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;
        if (world != mc.world) {
            if (threat != null) ElytraFly.pilot(null);
            reset();
            world = mc.world;
        }
        ticks++;
        lead = LatencyManager.get().rtt() / 2.0 / 50.0 + INTERPOLATION_TICKS;
        trackMotion();
        collectNear();
        trail.addLast(mc.player.getEntityPos());
        while (trail.size() > STUCK_TICKS + 1) trail.pollFirst();

        if (!Modules.get().isActive(ElytraFly.class)) {
            if (threat != null) calm("elytra-fly+ is off");
            return;
        }
        MaceStrike mace = Modules.get().get(MaceStrike.class);
        if (mace != null && mace.hunting()) {
            calm("mace-strike is hunting");
            return;
        }
        if (yieldToKeys.get() && steering()) lastManual = ticks;
        if (ticks - lastManual < MANUAL_TICKS) {
            if (threat != null) calm("you are steering");
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
            if (evading) ElytraFly.pilot(null);
            if (evading && (mc.player.isOnGround() || mc.player.isTouchingWater() || mc.player.isInLava() || mc.player.hasVehicle())) {
                calm("not gliding");
            } else if (evading && mc.player.getVelocity().y < 0 && ticks - lastGlideTry >= GLIDE_RETRY_TICKS && mc.player.checkGliding()) {
                lastGlideTry = ticks;
                mc.getNetworkHandler().sendPacket(new ClientCommandC2SPacket(mc.player, ClientCommandC2SPacket.Mode.START_FALL_FLYING));
                Trace.log("evade restarted the glide");
            }
            return;
        }

        Vec3d me = mc.player.getEntityPos();
        if (!evading) {
            if (anchor == null || flat(anchor, me) > leash.get() || now - calmSince > ANCHOR_TIMEOUT) anchor = me;
            trail.clear();
            trail.addLast(me);
        }
        if (threat != danger) Trace.log("evade from {} at {} blocks{}", danger.getName().getString(), danger.distanceTo(mc.player), danger.isGliding() ? ", gliding" : "");
        threat = danger;

        noteStartHazards();
        webbed = cobwebIn(mc.player.getBoundingBox());

        boolean arrived = goal != null && goal.distanceTo(me) < speed.get() + ARRIVED;
        boolean valid = goal != null && !arrived && !isBanned(goal) && stillFree(goal) && (ticks < nextScan || clearPath(goal));
        double current = valid ? risk(goal) : Double.MAX_VALUE;
        boolean risky = current >= minDamage.get();
        if (!valid || ticks >= nextScan || risky && ticks >= nextScan - (RESCAN_TICKS - RESCAN_TICKS_RISKY)) {
            nextScan = ticks + RESCAN_TICKS;
            Vec3d best = null;
            double bestScore = Double.MAX_VALUE;
            List<Vec3d> spots = spots(DIRECTIONS, new double[] {0.5, 1}, false);
            if (spots.isEmpty()) spots = spots(FALLBACK_DIRECTIONS, new double[] {0.5, 1.5}, true);
            int count = spots.size();
            double[] cheap = new double[count];
            Integer[] order = new Integer[count];
            for (int i = 0; i < count; i++) {
                cheap[i] = cheapRisk(spots.get(i));
                order[i] = i;
            }
            Arrays.sort(order, (a, b) -> Double.compare(cheap[a], cheap[b]));
            for (int k = 0; k < Math.min(count, FULL_CHECKS); k++) {
                Vec3d at = spots.get(order[k]);
                double score = risk(at);
                if (score < bestScore) {
                    best = at;
                    bestScore = score;
                }
            }
            if (best == null) {
                best = away();
                bestScore = risk(best);
                if (goal == null || goal.distanceTo(best) > 1) Trace.log("evade is boxed in, flees to {}", best);
            }
            if (!valid || bestScore < current - Math.max(SWITCH_GAIN, current * SWITCH_SHARE)) {
                Trace.log("evade goes to {} (risk {}{})", best, bestScore, arrived ? ", arrived at the last spot" : "");
                goal = best;
                phase = random.nextDouble() * Math.PI * 2 / DIRECTIONS;
            }
        }

        if (trail.size() > STUCK_TICKS && trail.peekFirst().distanceTo(me) < STUCK_MOVE) {
            Trace.log("evade gives up on {}, you barely moved in {} ticks{}", goal, STUCK_TICKS, webbed ? " in a web" : "");
            ban(goal);
            goal = null;
            nextScan = ticks;
            trail.clear();
            trail.addLast(me);
        }
        ElytraFly.pilot(goal == null ? Vec3d.ZERO : safeStep(toward(goal)));
    }

    private boolean steering() {
        return mc.options.forwardKey.isPressed() || mc.options.backKey.isPressed() || mc.options.leftKey.isPressed()
            || mc.options.rightKey.isPressed() || mc.options.jumpKey.isPressed() || mc.options.sneakKey.isPressed();
    }

    private void calm(String why) {
        if (threat != null) {
            Trace.log("evade stops, {}", why);
            ElytraFly.pilot(null);
        }
        threat = null;
        goal = null;
        calmSince = System.currentTimeMillis();
    }

    private void collectNear() {
        near.clear();
        double range = Math.max(Math.max(maceRange.get(), reach.get() + CRYSTAL_RADIUS) + margin.get(), WEB_REACH) + radius.get() * 1.5 + height.get() * 2;
        double limit = range * range;
        for (PlayerEntity player : mc.world.getPlayers()) {
            if (enemy(player) && player.squaredDistanceTo(mc.player) <= limit) near.add(player);
        }
    }

    private void trackMotion() {
        present.clear();
        for (PlayerEntity player : mc.world.getPlayers()) {
            if (player == mc.player) continue;
            int id = player.getId();
            present.add(id);
            Vec3d now = player.getEntityPos();
            Vec3d before = lastSeen.put(id, now);
            Vec3d step = before == null ? Vec3d.ZERO : now.subtract(before);
            if (step.lengthSquared() > 100) step = Vec3d.ZERO;
            Vec3d old = motion.get(id);
            motion.put(id, old == null ? step : old.multiply(0.3).add(step.multiply(0.7)));
        }
        lastSeen.keySet().retainAll(present);
        motion.keySet().retainAll(present);
    }

    private void ban(Vec3d at) {
        if (at == null) return;
        Vec3d way = way(at);
        if (way == null) return;
        bannedWays.add(way);
        bannedLevels.add(level(at));
        bannedUntil.add(System.currentTimeMillis() + BAN_MS);
    }

    private boolean isBanned(Vec3d at) {
        long now = System.currentTimeMillis();
        Vec3d way = way(at);
        int level = level(at);
        boolean hit = false;
        for (int i = bannedWays.size() - 1; i >= 0; i--) {
            if (bannedUntil.get(i) < now) {
                bannedWays.remove(i);
                bannedLevels.remove(i);
                bannedUntil.remove(i);
            } else if (way != null && bannedLevels.get(i) == level && bannedWays.get(i).dotProduct(way) > BAN_COS) {
                hit = true;
            }
        }
        return hit;
    }

    private Vec3d way(Vec3d at) {
        Vec3d off = at.subtract(mc.player.getEntityPos());
        double length = Math.sqrt(off.x * off.x + off.z * off.z);
        return length < 1.0E-3 ? null : new Vec3d(off.x / length, 0, off.z / length);
    }

    private int level(Vec3d at) {
        double dy = at.y - mc.player.getY();
        return dy > LEVEL_BAND ? 1 : dy < -LEVEL_BAND ? -1 : 0;
    }

    private static double flat(Vec3d a, Vec3d b) {
        double dx = a.x - b.x;
        double dz = a.z - b.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    private List<Vec3d> spots(int directions, double[] scales, boolean wide) {
        Vec3d me = mc.player.getEntityPos();
        double r = radius.get();
        double h = height.get();
        double limit = leash.get() * 2;
        double[] heights = webbed && !wide ? new double[] {0} : wide ? new double[] {-h, 0, h, h * 2} : new double[] {-h, 0, h};
        List<Vec3d> out = new ArrayList<>();
        for (double scale : scales) {
            double distance = r * scale;
            for (int i = 0; i < directions; i++) {
                double angle = phase + Math.PI * 2 * i / directions;
                for (double dy : heights) {
                    Vec3d raw = me.add(Math.cos(angle) * distance, dy, Math.sin(angle) * distance);
                    if (!wide && anchor != null && flat(raw, anchor) > limit) continue;
                    Vec3d at = free(raw);
                    if (at != null && !isBanned(at)) out.add(at);
                }
            }
        }
        return out;
    }

    private Vec3d away() {
        Vec3d me = mc.player.getEntityPos();
        Vec3d center = Vec3d.ZERO;
        for (PlayerEntity player : near) center = center.add(player.getEntityPos());
        Vec3d off = near.isEmpty() ? new Vec3d(1, 0, 0) : me.subtract(center.multiply(1.0 / near.size()));
        double length = Math.sqrt(off.x * off.x + off.z * off.z);
        Vec3d out = length < 1.0E-3 ? new Vec3d(Math.cos(phase), 0, Math.sin(phase)) : new Vec3d(off.x / length, 0, off.z / length);

        List<Vec3d> ways = new ArrayList<>();
        for (int i = 0; i < FALLBACK_DIRECTIONS; i++) {
            double angle = Math.PI * 2 * i / FALLBACK_DIRECTIONS;
            ways.add(new Vec3d(Math.cos(angle), 0, Math.sin(angle)));
        }
        ways.sort((a, b) -> Double.compare(b.dotProduct(out), a.dotProduct(out)));

        double r = radius.get() * 0.75;
        double h = height.get();
        double[] heights = webbed ? new double[] {0, h, -h} : new double[] {0, h, -h, h * 2};
        for (Vec3d way : ways) {
            for (double dy : heights) {
                Vec3d at = free(me.add(way.multiply(r)).add(0, dy, 0));
                if (at != null && !isBanned(at)) return at;
            }
        }

        Vec3d best = null;
        double bestMove = -1;
        for (Vec3d way : ways) {
            Vec3d target = me.add(way.multiply(r));
            if (isBanned(target)) continue;
            double move = safeStep(toward(target)).lengthSquared();
            if (move > bestMove) {
                bestMove = move;
                best = target;
            }
        }
        return best != null ? best : me.add(out.multiply(r));
    }

    private Vec3d free(Vec3d at) {
        for (double lift = 0; lift <= MAX_LIFT; lift += LIFT_STEP) {
            Vec3d up = at.add(0, lift, 0);
            Box body = mc.player.getBoundingBox().offset(up.subtract(mc.player.getEntityPos()));
            if (!mc.world.isSpaceEmpty(mc.player, body) || hazard(body.expand(WEB_CLEARANCE))) continue;
            if (!mc.world.isSpaceEmpty(mc.player, body.stretch(0, -GROUND_CLEARANCE, 0))) continue;
            if (!clearPath(up)) continue;
            return up;
        }
        return null;
    }

    private boolean stillFree(Vec3d at) {
        Box body = mc.player.getBoundingBox().offset(at.subtract(mc.player.getEntityPos()));
        return mc.world.isSpaceEmpty(mc.player, body) && !hazard(body);
    }

    private boolean clearPath(Vec3d to) {
        Vec3d from = mc.player.getEntityPos();
        Box body = mc.player.getBoundingBox();
        Vec3d path = to.subtract(from);
        int segments = Math.max(1, (int) Math.ceil(path.length() / SEGMENT));
        Vec3d last = Vec3d.ZERO;
        for (int i = 1; i <= segments; i++) {
            Vec3d next = path.multiply(i / (double) segments);
            Box sweep = body.offset(last).stretch(next.subtract(last));
            if (!mc.world.isSpaceEmpty(mc.player, sweep) || hazard(sweep.expand(PATH_WEB_CLEARANCE))) return false;
            last = next;
        }
        return true;
    }

    private void noteStartHazards() {
        startHazards.clear();
        Box box = mc.player.getBoundingBox().expand(PATH_WEB_CLEARANCE + 0.5);
        for (BlockPos pos : BlockPos.iterate(BlockPos.ofFloored(box.minX, box.minY, box.minZ), BlockPos.ofFloored(box.maxX, box.maxY, box.maxZ))) {
            if (hazardous(mc.world.getBlockState(pos))) startHazards.add(pos.asLong());
        }
    }

    private boolean hazard(Box box) {
        for (BlockPos pos : BlockPos.iterate(BlockPos.ofFloored(box.minX, box.minY, box.minZ), BlockPos.ofFloored(box.maxX, box.maxY, box.maxZ))) {
            if (hazardous(mc.world.getBlockState(pos)) && !startHazards.contains(pos.asLong())) return true;
        }
        return false;
    }

    private static boolean hazardous(BlockState state) {
        return state.isOf(Blocks.COBWEB) || !state.getFluidState().isEmpty();
    }

    private boolean cobwebIn(Box box) {
        for (BlockPos pos : BlockPos.iterate(BlockPos.ofFloored(box.minX, box.minY, box.minZ), BlockPos.ofFloored(box.maxX, box.maxY, box.maxZ))) {
            if (mc.world.getBlockState(pos).isOf(Blocks.COBWEB)) return true;
        }
        return false;
    }

    private Vec3d safeStep(Vec3d move) {
        Box body = mc.player.getBoundingBox();
        for (double scale = 1; scale >= 0.125; scale /= 2) {
            Vec3d step = move.multiply(scale);
            Box swept = body.stretch(step);
            if (mc.world.isSpaceEmpty(mc.player, swept) && !hazard(swept)) return step;
        }
        return Vec3d.ZERO;
    }

    private Vec3d toward(Vec3d target) {
        ElytraFly fly = Modules.get().get(ElytraFly.class);
        Vec3d delta = target.subtract(mc.player.getEntityPos());
        double length = delta.length();
        if (length < ARRIVED) return Vec3d.ZERO;
        double scale = Math.min(1, speed.get() / length);
        if (delta.y * scale > fly.upSpeed()) scale = fly.upSpeed() / delta.y;
        if (delta.y * scale < -fly.downSpeed()) scale = -fly.downSpeed() / delta.y;
        return delta.multiply(scale);
    }

    private PlayerEntity dangerous(Vec3d at, double extra) {
        PlayerEntity best = null;
        double bestScore = 0;
        for (PlayerEntity player : near) {
            double score = Math.max(diver(player, at, extra), potential(player, at, extra));
            if (score >= minDamage.get() && score > bestScore) {
                best = player;
                bestScore = score;
            }
        }
        return best;
    }

    private double cheapRisk(Vec3d at) {
        double total = 0;
        for (PlayerEntity player : near) total += diver(player, at, 0);
        return total + webRisk(at) + leashPenalty(at);
    }

    private double risk(Vec3d at) {
        double total = 0;
        for (PlayerEntity player : near) {
            total += Math.max(diver(player, at, 0), Math.min(potential(player, at, 0), GLIDER_DANGER * 2));
        }
        return total + webRisk(at) + leashPenalty(at);
    }

    private boolean diving(PlayerEntity player) {
        if (player.isOnGround() || player.hasVehicle() && !armed(player)) return false;
        if (!player.isGliding()) return armed(player) || motion.getOrDefault(player.getId(), Vec3d.ZERO).y < -DIVE_SPEED && player.getY() > mc.player.getY();
        if (armed(player)) return true;
        Vec3d to = mc.player.getEntityPos().subtract(player.getEntityPos());
        if (player.getY() - mc.player.getY() > OVERHEAD_HEIGHT && flat(player.getEntityPos(), mc.player.getEntityPos()) < OVERHEAD_RANGE) return true;
        double length = to.length();
        return length > 1.0E-3 && motion.getOrDefault(player.getId(), Vec3d.ZERO).dotProduct(to.multiply(1 / length)) > CLOSING_SPEED;
    }

    private static boolean armed(PlayerEntity player) {
        ItemStack main = player.getMainHandStack();
        return main.isOf(Items.MACE) || Registries.ITEM.getId(main.getItem()).getPath().endsWith("_spear");
    }

    private double diver(PlayerEntity player, Vec3d at, double extra) {
        if (!diving(player)) return 0;
        Vec3d me = mc.player.getEntityPos();
        Vec3d them = player.getEntityPos();
        Vec3d velocity = motion.getOrDefault(player.getId(), Vec3d.ZERO);
        Vec3d path = at.subtract(me);
        double length = path.length();
        double pace = Math.max(speed.get(), 0.1);

        double closest = Double.MAX_VALUE;
        for (int tick = 1; tick <= HORIZON; tick++) {
            Vec3d ours = length < 1.0E-4 ? me : me.add(path.multiply(Math.min(1, pace * tick / length)));
            Vec3d theirs = them.add(velocity.multiply(tick + lead));
            closest = Math.min(closest, weighted(theirs, ours));
        }
        closest = Math.min(closest, weighted(them.add(velocity.multiply(HORIZON + lead)), at));

        double range = maceRange.get() + extra;
        if (closest >= range) return 0;
        Vec3d eye = them.add(velocity.multiply(lead)).add(0, player.getEyeY() - player.getY(), 0);
        Vec3d look = player.getRotationVector();
        Vec3d to = at.subtract(eye);
        double facing = to.lengthSquared() < 1.0E-6 ? 1 : Math.max(0, look.dotProduct(to.normalize()));
        double danger = (GLIDER_DANGER * (1 - closest / range) + minDamage.get()) * (1 + FACING_WEIGHT * facing);
        if (closest >= COVER_IGNORED_WITHIN) {
            if (!sees(eye, at)) danger *= SIGHT_FACTOR;
            if (eye.y > at.y + 1 && covered(at)) danger *= COVER_FACTOR;
        }
        if (them.y > at.y + 1 && lowAbove(at)) danger += minDamage.get();
        return danger;
    }

    private static double weighted(Vec3d them, Vec3d us) {
        double dx = them.x - us.x;
        double dz = them.z - us.z;
        double dy = them.y - us.y;
        double vertical = dy * (dy > 0 ? ABOVE_WEIGHT : BELOW_WEIGHT);
        return Math.sqrt(dx * dx + dz * dz + vertical * vertical);
    }

    private double webRisk(Vec3d at) {
        Vec3d from = mc.player.getEntityPos();
        double half = mc.player.getHeight() / 2;
        double travel = travelTicks(at);
        double total = 0;
        for (PlayerEntity player : near) {
            double eyeHeight = player.getEyeY() - player.getY();
            if (predicted(player, travel).add(0, eyeHeight, 0).distanceTo(at.add(0, half, 0)) <= WEB_REACH) total += GLIDER_DANGER * 2;
            int inside = 0;
            for (int i = 1; i <= PATH_SAMPLES; i++) {
                double share = i / (double) PATH_SAMPLES;
                Vec3d eye = predicted(player, travel * share).add(0, eyeHeight, 0);
                if (eye.distanceTo(from.lerp(at, share).add(0, half, 0)) <= WEB_REACH) inside++;
            }
            total += GLIDER_DANGER * inside / PATH_SAMPLES;
        }
        return total;
    }

    private double travelTicks(Vec3d at) {
        return Math.min(HORIZON, mc.player.getEntityPos().distanceTo(at) / Math.max(speed.get(), 0.1));
    }

    private Vec3d predicted(PlayerEntity player, double ticks) {
        return player.getEntityPos().add(motion.getOrDefault(player.getId(), Vec3d.ZERO).multiply(ticks + lead));
    }

    private boolean sees(Vec3d eye, Vec3d at) {
        Vec3d center = at.add(0, mc.player.getHeight() / 2, 0);
        HitResult hit = mc.world.raycast(new RaycastContext(eye, center, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, mc.player));
        return hit.getType() == HitResult.Type.MISS;
    }

    private boolean covered(Vec3d at) {
        Box body = mc.player.getBoundingBox().offset(at.subtract(mc.player.getEntityPos()));
        return !mc.world.isSpaceEmpty(mc.player, new Box(body.minX, body.maxY, body.minZ, body.maxX, body.maxY + COVER_HEIGHT, body.maxZ));
    }

    private boolean lowAbove(Vec3d at) {
        Box body = mc.player.getBoundingBox().offset(at.subtract(mc.player.getEntityPos()));
        return !mc.world.isSpaceEmpty(mc.player, body.stretch(0, -LOW_GROUND, 0));
    }

    private double leashPenalty(Vec3d at) {
        if (anchor == null) return 0;
        double excess = flat(at, anchor) - leash.get();
        return excess <= 0 ? 0 : excess / leash.get() * GLIDER_DANGER;
    }

    private float potential(PlayerEntity player, Vec3d at, double extra) {
        Vec3d eye = predicted(player, travelTicks(at)).add(0, player.getEyeY() - player.getY(), 0);
        Box body = mc.player.getBoundingBox().offset(at.subtract(mc.player.getEntityPos()));
        Vec3d toward = body.getCenter().subtract(eye);
        double distance = toward.length();
        double range = reach.get() + extra;
        if (distance <= range) return Float.MAX_VALUE;
        if (distance > range + CRYSTAL_RADIUS) return 0;
        Vec3d crystal = eye.add(toward.multiply(range / distance));
        float bound = ExplosionDamage.upperBound(mc.player, at, crystal);
        if (bound < 1) return bound;
        return ExplosionDamage.crystal(mc.player, at, body, crystal, mc.world);
    }

    private boolean enemy(PlayerEntity player) {
        return player != mc.player && player.isAlive() && !player.isSpectator() && !Friends.get().isFriend(player);
    }
}
