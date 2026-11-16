package dev.coyduck.modules;

import dev.coyduck.events.GameJoinedEvent;
import dev.coyduck.events.PacketApplyEvent;
import dev.coyduck.events.TickEvent;
import dev.coyduck.feature.BoolSetting;
import dev.coyduck.feature.DoubleSetting;
import dev.coyduck.feature.Feature;
import dev.coyduck.feature.Features;
import dev.coyduck.feature.Setting;
import dev.coyduck.feature.SettingGroup;
import dev.coyduck.managers.InteractionManager;
import dev.coyduck.managers.LatencyManager;
import dev.coyduck.utils.ExplosionDamage;
import dev.coyduck.utils.Trace;
import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import meteordevelopment.meteorclient.systems.friends.Friends;
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
import net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ChunkDeltaUpdateS2CPacket;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class ElytraEvade extends Feature {
    private static final double GROUND_CLEARANCE = 3;
    private static final double LIFT_STEP = 2;
    private static final double MAX_LIFT = 12;
    private static final double ARRIVED = 0.6;
    private static final double SWITCH_GAIN = 2;
    private static final double SWITCH_SHARE = 0.15;
    private static final double GLIDER_DANGER = 20;
    private static final double CRYSTAL_CAP = GLIDER_DANGER * 2;
    private static final double SPOT_WEB = GLIDER_DANGER;
    private static final double PATH_WEB = GLIDER_DANGER / 2;
    private static final double UNKNOWN_WEB_SHARE = 0.25;
    private static final long WEBBER_MS = 60_000;
    private static final long ARMED_MS = 60_000;
    private static final int FLOOR_SCAN = 96;
    private static final byte FLOOR_SAFE = 1;
    private static final byte FLOOR_BAD = 2;
    private static final double PATH_CRYSTAL_SHARE = 0.5;
    private static final int AWAY_PATHS = 24;
    private static final long OWN_WEB_MS = 2_000;
    private static final double WEB_TRACE_RANGE = 6;
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
    private static final int PATH_CHECKS = 24;
    private static final int SPOT_PROBES = 96;
    private static final int FALLBACK_PROBES = 128;
    private static final int AWAY_PROBES = 96;
    private static final int GLIDE_RETRY_TICKS = 5;
    private static final int MANUAL_TICKS = 20;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Double> minDamage = sgGeneral.add(new DoubleSetting.Builder()
        .name("min-damage")
        .description("Evades players who could deal this much crystal damage.")
        .defaultValue(4)
        .range(0.5, 20)
        .sliderRange(0.5, 20)
        .build()
    );

    private final Setting<Double> reach = sgGeneral.add(new DoubleSetting.Builder()
        .name("reach")
        .description("How far a player can place a crystal from their eyes.")
        .defaultValue(5)
        .range(3, 7)
        .sliderRange(3, 7)
        .build()
    );

    private final Setting<Double> margin = sgGeneral.add(new DoubleSetting.Builder()
        .name("margin")
        .description("Extra reach assumed while evading.")
        .defaultValue(4)
        .range(0, 16)
        .sliderRange(0, 16)
        .build()
    );

    private final Setting<Double> radius = sgGeneral.add(new DoubleSetting.Builder()
        .name("radius")
        .description("How far each hop goes, in blocks.")
        .defaultValue(16)
        .range(6, 48)
        .sliderRange(6, 48)
        .build()
    );

    private final Setting<Double> height = sgGeneral.add(new DoubleSetting.Builder()
        .name("height")
        .description("How far above and below you spots are tried.")
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
        .description("Range at which diving players count as threats.")
        .defaultValue(28)
        .range(6, 64)
        .sliderRange(6, 64)
        .build()
    );

    private final Setting<Double> leash = sgGeneral.add(new DoubleSetting.Builder()
        .name("leash")
        .description("Tries to keep spots within this range of the start.")
        .defaultValue(48)
        .range(16, 160)
        .sliderRange(16, 160)
        .build()
    );

    private final Setting<Boolean> yieldToKeys = sgGeneral.add(new BoolSetting.Builder()
        .name("yield-to-keys")
        .description("Movement keys give you control for a second.")
        .defaultValue(true)
        .build()
    );

    private record Ban(Vec3d way, int level, long until) {}

    private record Pick(Vec3d at, double risk) {}

    private final Int2ObjectOpenHashMap<Vec3d> lastSeen = new Int2ObjectOpenHashMap<>();
    private final Int2ObjectOpenHashMap<Vec3d> motion = new Int2ObjectOpenHashMap<>();
    private final Int2LongOpenHashMap webbers = new Int2LongOpenHashMap();
    private final Int2LongOpenHashMap armedUntil = new Int2LongOpenHashMap();
    private final Long2ByteOpenHashMap floors = new Long2ByteOpenHashMap();
    private final IntOpenHashSet present = new IntOpenHashSet();
    private final IntOpenHashSet divers = new IntOpenHashSet();
    private final List<PlayerEntity> near = new ArrayList<>();
    private final List<Ban> bans = new ArrayList<>();
    private final ArrayDeque<Vec3d> trail = new ArrayDeque<>();
    private final LongOpenHashSet startHazards = new LongOpenHashSet();
    private final Random random = new Random();
    private Vec3d anchor;
    private long calmSince;
    private long now;
    private Vec3d goal;
    private PlayerEntity threat;
    private double phase;
    private double lead;
    private int ticks;
    private int nextScan;
    private int probes;
    private int probeLimit;
    private int lastGlideTry = -GLIDE_RETRY_TICKS;
    private int lastManual = -MANUAL_TICKS;
    private boolean webbed;
    private ClientWorld world;

    public ElytraEvade() {
        super("elytra-evade", "Dodges threats while you fly.");
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
        if (threat != null) ElytraFly.pilot(null);
        reset();
    }

    @EventHandler
    private void onPacket(PacketApplyEvent.Post event) {
        if (mc.player == null || mc.world == null) return;
        switch (event.packet) {
            case BlockUpdateS2CPacket packet when packet.getState().isOf(Blocks.COBWEB) -> traceWeb(packet.getPos());
            case ChunkDeltaUpdateS2CPacket packet -> packet.visitUpdates((pos, state) -> {
                if (state.isOf(Blocks.COBWEB)) traceWeb(pos);
            });
            default -> {}
        }
    }

    private void traceWeb(BlockPos pos) {
        if (InteractionManager.get().placedByUs(pos, OWN_WEB_MS)) return;
        Vec3d center = Vec3d.ofCenter(pos);
        PlayerEntity placer = null;
        double best = WEB_TRACE_RANGE * WEB_TRACE_RANGE;
        for (PlayerEntity player : mc.world.getPlayers()) {
            if (!enemy(player)) continue;
            double distance = player.getEyePos().squaredDistanceTo(center);
            if (distance <= best) {
                best = distance;
                placer = player;
            }
        }
        if (placer != null) webbers.put(placer.getId(), System.currentTimeMillis() + WEBBER_MS);
    }

    private void reset() {
        anchor = null;
        goal = null;
        threat = null;
        lastSeen.clear();
        motion.clear();
        webbers.clear();
        armedUntil.clear();
        floors.clear();
        present.clear();
        divers.clear();
        near.clear();
        bans.clear();
        trail.clear();
        startHazards.clear();
        calmSince = 0;
        phase = 0;
        nextScan = ticks;
        lastManual = ticks - MANUAL_TICKS;
        lastGlideTry = ticks - GLIDE_RETRY_TICKS;
        world = null;
    }

    @EventHandler(priority = EventPriority.HIGH)
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;
        if (!mc.player.isAlive()) {
            if (threat != null) calm("dead");
            return;
        }
        if (world != mc.world) {
            if (threat != null) ElytraFly.pilot(null);
            reset();
            world = mc.world;
        }
        ticks++;
        now = System.currentTimeMillis();
        trackMotion();
        trail.addLast(mc.player.getEntityPos());
        while (trail.size() > STUCK_TICKS + 1) trail.pollFirst();

        if (!Modules.get().isActive(ElytraFly.class)) {
            if (threat != null) calm("elytra-fly+ is off");
            return;
        }
        MaceStrike mace = Features.get(MaceStrike.class);
        if (mace != null && mace.hunting()) {
            calm("mace-strike is hunting");
            return;
        }
        if (yieldToKeys.get() && steering()) lastManual = ticks;
        if (ticks - lastManual < MANUAL_TICKS) {
            if (threat != null) calm("you are steering");
            return;
        }

        lead = LatencyManager.get().rtt() / 2.0 / 50.0 + INTERPOLATION_TICKS;
        collectNear();
        boolean evading = threat != null;
        PlayerEntity danger = dangerous(mc.player.getEntityPos(), evading ? margin.get() : 0);
        if (danger == null) {
            if (evading) calm("nobody can hurt you");
            if (anchor != null && now - calmSince > ANCHOR_TIMEOUT) anchor = null;
            return;
        }

        if (!mc.player.isGliding()) {
            if (evading) ElytraFly.pilot(null);
            if (evading && (mc.player.isOnGround() || mc.player.isTouchingWater() || mc.player.isInLava() || mc.player.hasVehicle())) {
                calm("not gliding");
            } else if (evading && mc.player.getVelocity().y < 0 && ticks - lastGlideTry >= GLIDE_RETRY_TICKS && !cobwebIn(mc.player.getBoundingBox()) && mc.player.checkGliding()) {
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

        bans.removeIf(ban -> ban.until() < now);
        noteStartHazards();
        webbed = cobwebIn(mc.player.getBoundingBox());

        boolean arrived = goal != null && goal.distanceTo(me) < speed.get() + ARRIVED;
        boolean valid = goal != null && !arrived && !isBanned(goal) && stillFree(goal) && (ticks < nextScan || clearPath(goal));
        double current = valid ? risk(goal) : Double.MAX_VALUE;
        boolean risky = current >= minDamage.get();
        if (ticks >= nextScan || (!valid || risky) && ticks >= nextScan - (RESCAN_TICKS - RESCAN_TICKS_RISKY)) {
            nextScan = ticks + RESCAN_TICKS;
            Pick pick = choose(spots(DIRECTIONS, new double[] {0.5, 1}, false, SPOT_PROBES));
            if (pick == null) pick = choose(spots(FALLBACK_DIRECTIONS, new double[] {0.5, 1.5}, true, FALLBACK_PROBES));
            if (pick == null) {
                Vec3d out = away();
                pick = new Pick(out, risk(out));
                if (goal == null || goal.distanceTo(out) > 1) Trace.log("evade is boxed in, flees to {}", out);
            }
            if (!valid || pick.risk() < current - Math.max(SWITCH_GAIN, current * SWITCH_SHARE)) {
                Trace.log("evade goes to {} (risk {}{})", pick.at(), pick.risk(), arrived ? ", arrived at the last spot" : "");
                goal = pick.at();
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

    private Pick choose(List<Vec3d> spots) {
        int count = spots.size();
        if (count == 0) return null;
        double[] cheap = new double[count];
        for (int i = 0; i < count; i++) cheap[i] = cheapRisk(spots.get(i));

        Vec3d best = null;
        double bestRisk = Double.MAX_VALUE;
        int full = 0;
        int paths = 0;
        while (full < FULL_CHECKS && paths < PATH_CHECKS) {
            int pick = -1;
            for (int i = 0; i < count; i++) {
                if (!Double.isNaN(cheap[i]) && (pick == -1 || cheap[i] < cheap[pick])) pick = i;
            }
            if (pick == -1) break;
            cheap[pick] = Double.NaN;
            Vec3d at = spots.get(pick);
            paths++;
            if (!clearPath(at)) continue;
            full++;
            double value = risk(at);
            if (value < bestRisk) {
                best = at;
                bestRisk = value;
            }
        }
        return best == null ? null : new Pick(best, bestRisk);
    }

    private boolean steering() {
        return mc.options.forwardKey.isPressed() || mc.options.backKey.isPressed() || mc.options.leftKey.isPressed()
            || mc.options.rightKey.isPressed() || mc.options.jumpKey.isPressed() || mc.options.sneakKey.isPressed();
    }

    private void calm(String why) {
        if (threat != null) {
            Trace.log("evade stops, {}", why);
            ElytraFly.pilot(null);
            calmSince = System.currentTimeMillis();
        }
        threat = null;
        goal = null;
    }

    private void collectNear() {
        near.clear();
        divers.clear();
        double range = Math.max(Math.max(maceRange.get(), reach.get() + CRYSTAL_RADIUS) + margin.get(), WEB_REACH) + radius.get() * 1.5 + height.get() * 2;
        double limit = range * range;
        for (PlayerEntity player : mc.world.getPlayers()) {
            if (!enemy(player) || player.squaredDistanceTo(mc.player) > limit) continue;
            near.add(player);
            if (diving(player)) divers.add(player.getId());
        }
    }

    private void trackMotion() {
        present.clear();
        for (PlayerEntity player : mc.world.getPlayers()) {
            if (player == mc.player) continue;
            int id = player.getId();
            present.add(id);
            Vec3d at = player.getEntityPos();
            Vec3d before = lastSeen.put(id, at);
            Vec3d step = before == null ? Vec3d.ZERO : at.subtract(before);
            if (step.lengthSquared() > 100) step = Vec3d.ZERO;
            Vec3d old = motion.get(id);
            motion.put(id, old == null ? step : old.multiply(0.3).add(step.multiply(0.7)));
            if (player.getMainHandStack().isOf(Items.COBWEB) || player.getOffHandStack().isOf(Items.COBWEB)) webbers.put(id, now + WEBBER_MS);
            if (holdsDiveWeapon(player)) armedUntil.put(id, now + ARMED_MS);
        }
        lastSeen.keySet().removeIf((java.util.function.IntPredicate) id -> !present.contains(id));
        motion.keySet().removeIf((java.util.function.IntPredicate) id -> !present.contains(id));
        webbers.int2LongEntrySet().removeIf(entry -> entry.getLongValue() < now);
        if (!armedUntil.isEmpty()) armedUntil.int2LongEntrySet().removeIf(entry -> entry.getLongValue() < now);
    }

    private void ban(Vec3d at) {
        if (at == null) return;
        Vec3d way = way(at);
        if (way != null) bans.add(new Ban(way, level(at), now + BAN_MS));
    }

    private boolean isBanned(Vec3d at) {
        Vec3d way = way(at);
        if (way == null) return false;
        int level = level(at);
        for (Ban ban : bans) {
            if (ban.level() == level && ban.until() >= now && ban.way().dotProduct(way) > BAN_COS) return true;
        }
        return false;
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

    private static int spread(int index, int count) {
        int bits = Integer.numberOfTrailingZeros(count);
        return Integer.reverse(index) >>> (32 - bits);
    }

    private List<Vec3d> spots(int directions, double[] scales, boolean wide, int budget) {
        probes = 0;
        probeLimit = budget;
        floors.clear();
        Vec3d me = mc.player.getEntityPos();
        double r = radius.get();
        double h = height.get();
        double limit = leash.get() * 2;
        double[] heights = webbed && !wide ? new double[] {0} : wide ? new double[] {-h, 0, h, h * 2} : new double[] {-h, 0, h};
        List<Vec3d> out = new ArrayList<>();
        for (int i = 0; i < directions; i++) {
            double angle = phase + Math.PI * 2 * spread(i, directions) / directions;
            for (int s = 0; s < scales.length; s++) {
                double distance = r * scales[(s + i) % scales.length];
                for (double dy : heights) {
                    Vec3d raw = me.add(Math.cos(angle) * distance, dy, Math.sin(angle) * distance);
                    if (!wide && anchor != null && flat(raw, anchor) > limit) continue;
                    Vec3d at = open(raw);
                    if (at != null && !isBanned(at) && safeFloor(at)) out.add(at);
                }
            }
        }
        return out;
    }

    private Vec3d away() {
        probes = 0;
        probeLimit = AWAY_PROBES;
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
        int paths = 0;
        for (Vec3d way : ways) {
            for (double dy : heights) {
                Vec3d at = open(me.add(way.multiply(r)).add(0, dy, 0));
                if (at != null && !isBanned(at) && paths++ < AWAY_PATHS && clearPath(at)) return at;
                if (probes > probeLimit || paths >= AWAY_PATHS) break;
            }
            if (probes > probeLimit || paths >= AWAY_PATHS) break;
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

    private Vec3d open(Vec3d at) {
        for (double lift = 0; lift <= MAX_LIFT; lift += LIFT_STEP) {
            if (++probes > probeLimit) return null;
            Vec3d up = at.add(0, lift, 0);
            Box body = mc.player.getBoundingBox().offset(up.subtract(mc.player.getEntityPos()));
            if (!mc.world.isSpaceEmpty(mc.player, body) || hazard(body.expand(WEB_CLEARANCE))) continue;
            if (!mc.world.isSpaceEmpty(mc.player, body.stretch(0, -GROUND_CLEARANCE, 0))) continue;
            return up;
        }
        return null;
    }

    private boolean safeFloor(Vec3d at) {
        int x = MathHelper.floor(at.x);
        int z = MathHelper.floor(at.z);
        int top = MathHelper.floor(at.y);
        long key = BlockPos.asLong(x, 0, z);
        byte known = floors.get(key);
        if (known != 0) return known == FLOOR_SAFE;

        byte result = FLOOR_SAFE;
        int bottom = mc.world.getBottomY();
        BlockPos.Mutable pos = new BlockPos.Mutable();
        int y = top;
        for (; y >= Math.max(bottom, top - FLOOR_SCAN); y--) {
            BlockState state = mc.world.getBlockState(pos.set(x, y, z));
            if (state.getFluidState().isIn(FluidTags.LAVA)) {
                result = FLOOR_BAD;
                break;
            }
            if (!state.isAir()) break;
        }
        if (y < bottom) result = FLOOR_BAD;
        floors.put(key, result);
        return result == FLOOR_SAFE;
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
        Box box = mc.player.getBoundingBox();
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
        double up = fly == null ? 0 : Math.max(0, fly.upSpeed());
        double down = fly == null ? 0 : Math.max(0, fly.downSpeed());
        Vec3d delta = target.subtract(mc.player.getEntityPos());
        double flat = Math.sqrt(delta.x * delta.x + delta.z * delta.z);
        double y = MathHelper.clamp(delta.y, -down, up);
        if (flat < ARRIVED && Math.abs(y) < ARRIVED) return Vec3d.ZERO;
        double move = Math.min(flat, pace());
        double x = flat < 1.0E-4 ? 0 : delta.x / flat * move;
        double z = flat < 1.0E-4 ? 0 : delta.z / flat * move;
        return new Vec3d(x, y, z);
    }

    private PlayerEntity dangerous(Vec3d at, double extra) {
        PlayerEntity best = null;
        double bestScore = 0;
        for (PlayerEntity player : near) {
            double score = Math.max(diver(player, at, extra, true), potential(player, at, extra));
            if (score >= minDamage.get() && score > bestScore) {
                best = player;
                bestScore = score;
            }
        }
        return best;
    }

    private double cheapRisk(Vec3d at) {
        double total = 0;
        for (PlayerEntity player : near) total += Math.max(diver(player, at, 0, false), reachRisk(player, at)) + webRisk(player, at);
        return total + leashPenalty(at);
    }

    private double risk(Vec3d at) {
        double total = 0;
        Vec3d from = mc.player.getEntityPos();
        Vec3d first = from.lerp(at, 1 / 3.0);
        Vec3d second = from.lerp(at, 2 / 3.0);
        for (PlayerEntity player : near) {
            double passing = Math.max(reachRisk(player, first), reachRisk(player, second)) * PATH_CRYSTAL_SHARE;
            total += Math.max(Math.max(diver(player, at, 0, true), Math.min(potential(player, at, 0), CRYSTAL_CAP)), passing) + webRisk(player, at);
        }
        return total + leashPenalty(at);
    }

    private boolean diving(PlayerEntity player) {
        if (player.isOnGround() || player.isTouchingWater() || player.isClimbing()) return false;
        boolean armed = armed(player);
        Vec3d velocity = motion.getOrDefault(player.getId(), Vec3d.ZERO);
        if (!player.isGliding() || player.hasVehicle()) return armed && player.getY() > mc.player.getY() + 1 && velocity.y < -DIVE_SPEED;
        if (armed) return true;
        if (player.getY() - mc.player.getY() > OVERHEAD_HEIGHT && flat(player.getEntityPos(), mc.player.getEntityPos()) < OVERHEAD_RANGE) return true;
        Vec3d to = mc.player.getEntityPos().subtract(player.getEntityPos());
        double length = to.length();
        return length > 1.0E-3 && velocity.dotProduct(to.multiply(1 / length)) > CLOSING_SPEED;
    }

    private boolean armed(PlayerEntity player) {
        return holdsDiveWeapon(player) || armedUntil.get(player.getId()) > now;
    }

    private static boolean holdsDiveWeapon(PlayerEntity player) {
        ItemStack main = player.getMainHandStack();
        return main.isOf(Items.MACE) || Registries.ITEM.getId(main.getItem()).getPath().endsWith("_spear");
    }

    private double diver(PlayerEntity player, Vec3d at, double extra, boolean exact) {
        if (!divers.contains(player.getId())) return 0;
        Vec3d me = mc.player.getEntityPos();
        Vec3d them = player.getEntityPos();
        Vec3d velocity = motion.getOrDefault(player.getId(), Vec3d.ZERO);
        Vec3d path = at.subtract(me);
        double length = path.length();
        double pace = Math.max(pace(), 0.1);

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
        if (exact && closest >= COVER_IGNORED_WITHIN) {
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

    private double webRisk(PlayerEntity player, Vec3d at) {
        double share = webbers.containsKey(player.getId()) ? 1 : UNKNOWN_WEB_SHARE;
        Vec3d from = mc.player.getEntityPos();
        double half = mc.player.getHeight() / 2;
        double travel = travelTicks(at);
        double eyeHeight = player.getEyeY() - player.getY();
        double total = 0;
        if (predicted(player, travel).add(0, eyeHeight, 0).distanceTo(at.add(0, half, 0)) <= WEB_REACH) total += SPOT_WEB;
        int inside = 0;
        for (int i = 1; i <= PATH_SAMPLES; i++) {
            double part = i / (double) PATH_SAMPLES;
            Vec3d eye = predicted(player, travel * part).add(0, eyeHeight, 0);
            if (eye.distanceTo(from.lerp(at, part).add(0, half, 0)) <= WEB_REACH) inside++;
        }
        total += PATH_WEB * inside / PATH_SAMPLES;
        return total * share;
    }

    private double travelTicks(Vec3d at) {
        return Math.min(HORIZON, mc.player.getEntityPos().distanceTo(at) / Math.max(pace(), 0.1));
    }

    private double pace() {
        ElytraFly fly = Modules.get().get(ElytraFly.class);
        return fly == null ? speed.get() : Math.min(speed.get(), fly.horizontalSpeed());
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
        double rise = at.y - anchor.y - height.get() * 2;
        return (excess <= 0 ? 0 : excess / leash.get() * GLIDER_DANGER) + (rise <= 0 ? 0 : rise / height.get() * GLIDER_DANGER);
    }

    private Vec3d crystalEye(PlayerEntity player, Vec3d at) {
        return predicted(player, travelTicks(at)).add(0, player.getEyeY() - player.getY(), 0);
    }

    private double reachRisk(PlayerEntity player, Vec3d at) {
        Vec3d eye = crystalEye(player, at);
        Vec3d center = at.add(0, mc.player.getHeight() / 2, 0);
        double distance = eye.distanceTo(center);
        double range = reach.get();
        if (distance <= range) return CRYSTAL_CAP;
        if (distance > range + CRYSTAL_RADIUS) return 0;
        return Math.min(CRYSTAL_CAP, ExplosionDamage.upperBound(mc.player, at, eye.add(center.subtract(eye).multiply(range / distance))));
    }

    private double potential(PlayerEntity player, Vec3d at, double extra) {
        Vec3d eye = crystalEye(player, at);
        Box body = mc.player.getBoundingBox().offset(at.subtract(mc.player.getEntityPos()));
        Vec3d toward = body.getCenter().subtract(eye);
        double distance = toward.length();
        double range = reach.get() + extra;
        if (distance <= range) return Double.POSITIVE_INFINITY;
        if (distance > range + CRYSTAL_RADIUS) return 0;
        Vec3d crystal = eye.add(toward.multiply(range / distance));
        double bound = ExplosionDamage.upperBound(mc.player, at, crystal);
        if (bound < 1) return bound;
        return ExplosionDamage.crystal(mc.player, at, body, crystal, mc.world);
    }

    private boolean enemy(PlayerEntity player) {
        return player != mc.player && player.isAlive() && !player.isSpectator() && !Friends.get().isFriend(player);
    }
}
