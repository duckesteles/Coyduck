package dev.coyduck.modules;

import dev.coyduck.Coyduck;
import dev.coyduck.events.PacketApplyEvent;
import dev.coyduck.events.WorldResetEvent;
import dev.coyduck.managers.InventoryManager;
import dev.coyduck.managers.RotationManager;
import dev.coyduck.utils.NetherRoute;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.ChunkDataEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ChunkDeltaUpdateS2CPacket;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;
import net.minecraft.world.World;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

public class ElytraPilot extends Module {
    private static final int ROTATE_PRIORITY = 60;
    private static final int CHEST_SLOT = 6;

    private static final double ARRIVE_RADIUS = 3;
    private static final double SLOW_ZONE = 10;
    private static final double LOOKAHEAD = 48;
    private static final double UNLOADED_MARGIN = 12;
    private static final int SPOT_RADIUS = 16;
    private static final int SPOT_DEPTH = 384;
    private static final int SPOT_SEARCH_TICKS = 20;
    private static final int STUCK_TICKS = 100;
    private static final int REPLAN_TICKS = 80;
    private static final int TAKEOFF_TICKS = 40;
    private static final int THROW_TICKS = 2;
    private static final int CHUNKS_PER_TICK = 12;
    private static final double CRITICAL_WEAR = 0.03;
    private static final double BACKTRACK_DISTANCE = 20;
    private static final int MAX_DEAD_ENDS = 3;
    private static final int MAX_SPOT_RADIUS = 48;

    private static final Set<net.minecraft.block.Block> DANGER = Set.of(
        Blocks.MAGMA_BLOCK, Blocks.CAMPFIRE, Blocks.SOUL_CAMPFIRE, Blocks.FIRE, Blocks.SOUL_FIRE, Blocks.CACTUS,
        Blocks.SWEET_BERRY_BUSH, Blocks.POWDER_SNOW, Blocks.WITHER_ROSE, Blocks.POINTED_DRIPSTONE, Blocks.LAVA_CAULDRON
    );

    private enum State { Takeoff, Cruise, Land, Mend }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgFlight = settings.createGroup("Flight");
    private final SettingGroup sgRender = settings.createGroup("Render");

    private final Setting<Boolean> mend = sgGeneral.add(new BoolSetting.Builder()
        .name("mend")
        .description("Lands on safe ground when the elytra wears down, throws experience bottles until it is fully repaired, then flies on.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> mendAt = sgGeneral.add(new DoubleSetting.Builder()
        .name("mend-at")
        .description("Durability percent of the worn elytra at which it lands to mend.")
        .defaultValue(10)
        .range(2, 60)
        .sliderRange(2, 60)
        .visible(mend::get)
        .build()
    );

    private final Setting<Boolean> spare = sgGeneral.add(new BoolSetting.Builder()
        .name("spare-elytra")
        .description("Puts on a fresher elytra from the inventory instead of landing, when there is one.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> walk = sgGeneral.add(new BoolSetting.Builder()
        .name("baritone-finish")
        .description("After landing, lets Baritone walk the last blocks to the exact destination.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> warnPlayers = sgGeneral.add(new BoolSetting.Builder()
        .name("warn-players")
        .description("Warns when another player comes close while you are landed and mending.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> overworldY = sgFlight.add(new IntSetting.Builder()
        .name("overworld-y")
        .description("Cruising height in the overworld. It climbs higher when the terrain ahead needs it.")
        .defaultValue(200)
        .range(-60, 318)
        .sliderRange(64, 318)
        .build()
    );

    private final Setting<Integer> endY = sgFlight.add(new IntSetting.Builder()
        .name("end-y")
        .description("Cruising height in the end, so you never sink into the void between islands.")
        .defaultValue(100)
        .range(0, 254)
        .sliderRange(40, 254)
        .build()
    );

    private final Setting<Integer> clearance = sgFlight.add(new IntSetting.Builder()
        .name("clearance")
        .description("Blocks kept above the terrain ahead.")
        .defaultValue(8)
        .range(2, 64)
        .sliderRange(2, 32)
        .build()
    );

    private final Setting<Double> landSpeed = sgFlight.add(new DoubleSetting.Builder()
        .name("land-speed")
        .description("Blocks per tick for the last blocks of a landing. Slow enough that the server keeps resetting fall distance.")
        .defaultValue(0.35)
        .range(0.05, 0.45)
        .sliderRange(0.05, 0.45)
        .build()
    );

    private final Setting<Boolean> render = sgRender.add(new BoolSetting.Builder()
        .name("render")
        .description("Draws the route and the landing spot.")
        .defaultValue(true)
        .build()
    );

    private final Setting<SettingColor> color = sgRender.add(new ColorSetting.Builder()
        .name("color")
        .description("Route and landing spot color.")
        .defaultValue(new SettingColor(110, 45, 200, 200))
        .visible(render::get)
        .build()
    );

    private static NetherRoute route;

    private double goalX;
    private double goalZ;
    private Integer goalY;
    private boolean hasGoal;

    private State state = State.Takeoff;
    private boolean landToMend;
    private boolean enabledFly;
    private int ticks;

    private int takeoffTick;
    private int jumps;
    private int deployTick = -100;
    private BlockPos spot;
    private int spotTick = -100;
    private final LongSet badSpots = new LongOpenHashSet();
    private double spotProgress;
    private int spotProgressTick;

    private double bestDistance;
    private int bestTick;
    private int extraHeight;
    private int throwTick;
    private boolean warnedXp;
    private boolean warnedPlayer;
    private boolean warnedSpot;
    private boolean warnedStuck;
    private boolean warnedCritical;
    private int spotSearches;

    private List<BlockPos> path = List.of();
    private int pathIndex;
    private boolean pathFinished;
    private CompletableFuture<NetherRoute.Path> planning;
    private int planTick = -1000;
    private int failures;
    private int endTick = -1;
    private final ArrayDeque<Vec3d> trail = new ArrayDeque<>();
    private boolean backtracking;
    private Vec3d deadEnd;
    private int deadEnds;
    private int crumbTick;
    private double crumbBest;
    private String detail;
    private final LongArrayFIFOQueue chunkQueue = new LongArrayFIFOQueue();
    private final LongSet queued = new LongOpenHashSet();
    private int cullTick;

    public ElytraPilot() {
        super(Coyduck.CATEGORY, "elytra-pilot", "Flies to a destination with elytra-fly+, routes through the nether with Baritone's pathfinder, and lands to mend the elytra on the way. Start it with .pilot x z");
    }

    public void travel(int x, Integer y, int z) {
        goalX = x + 0.5;
        goalZ = z + 0.5;
        goalY = y;
        hasGoal = true;
        if (isActive()) start();
        else toggle();
    }

    public String status() {
        if (!isActive() || !hasGoal) return "idle";
        return String.format("%s to %d %s%d, %.0f blocks left", state.name().toLowerCase(), (int) Math.floor(goalX), goalY == null ? "" : goalY + " ", (int) Math.floor(goalZ), horizontalDistance());
    }

    @Override
    public void onActivate() {
        if (!hasGoal) {
            error("Set a destination first: .pilot <x> <z> or .pilot <x> <y> <z>");
            toggle();
            return;
        }
        if (mc.player == null || mc.world == null) {
            toggle();
            return;
        }
        if (nether() && !routesAvailable()) {
            error("Nether routes need Baritone (its nether-pathfinder). Install Baritone for Meteor.");
            toggle();
            return;
        }

        ElytraFly fly = Modules.get().get(ElytraFly.class);
        enabledFly = !fly.isActive();
        if (enabledFly) fly.toggle();
        start();
    }

    @Override
    public void onDeactivate() {
        ElytraFly.pilot(null);
        closeRoute();

        ElytraFly fly = Modules.get().get(ElytraFly.class);
        if (enabledFly && fly.isActive() && (mc.player == null || mc.player.isOnGround())) fly.toggle();
        enabledFly = false;
    }

    @Override
    public String getInfoString() {
        if (!hasGoal) return null;
        return detail != null && state == State.Cruise ? state.name() + " " + detail : state.name();
    }

    private void start() {
        state = mc.player.isGliding() ? State.Cruise : State.Takeoff;
        takeoffTick = ticks;
        jumps = 0;
        spot = null;
        badSpots.clear();
        extraHeight = 0;
        bestDistance = horizontalDistance();
        bestTick = ticks;
        warnedXp = warnedPlayer = warnedSpot = warnedStuck = false;

        closeRoute();
        if (nether()) openRoute();
        info("Flying to %d %s%d (%.0f blocks).", (int) Math.floor(goalX), goalY == null ? "" : goalY + " ", (int) Math.floor(goalZ), horizontalDistance());
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        ElytraFly.pilot(null);
        if (mc.player == null || mc.world == null) return;
        ticks++;

        if (!mc.player.isAlive()) {
            toggle();
            return;
        }

        if (!Modules.get().get(ElytraFly.class).isActive()) {
            error("elytra-fly+ was turned off, stopping.");
            toggle();
            return;
        }

        feedChunks();

        switch (state) {
            case Takeoff -> takeoff();
            case Cruise -> cruise();
            case Land -> land();
            case Mend -> mend();
        }
    }

    // Flight phases

    private void takeoff() {
        if (mc.player.isOnGround() && needsMend() && mend.get()) {
            state = State.Mend;
            return;
        }
        if (!hasUsableElytra()) {
            error("No usable elytra in the chest slot.");
            toggle();
            return;
        }
        if (mc.player.isGliding()) {
            state = State.Cruise;
            jumps = 0;
            return;
        }

        if (mc.player.isOnGround()) {
            if (!mc.world.isSpaceEmpty(mc.player, mc.player.getBoundingBox().stretch(0, 1.5, 0))) {
                error("No room above you to take off.");
                toggle();
                return;
            }
            if (++jumps > 5) {
                error("Could not open the elytra.");
                toggle();
                return;
            }
            mc.player.jump();
            takeoffTick = ticks;
            return;
        }

        deploy();
        if (ticks - takeoffTick > TAKEOFF_TICKS) {
            error("Could not open the elytra.");
            toggle();
        }
    }

    private void cruise() {
        if (!mc.player.isGliding()) {
            if (mc.player.isOnGround()) state = State.Takeoff;
            else deploy();
            return;
        }

        if (needsMend() && !swapSpare()) {
            if (mend.get()) {
                beginLanding(true);
                return;
            }
            if (!warnedSpot) warning("Elytra is low and mend is off.");
            warnedSpot = true;
        }

        double distance = horizontalDistance();
        if (distance <= ARRIVE_RADIUS) {
            beginLanding(false);
            return;
        }

        trackProgress(distance);
        ElytraFly.pilot(nether() ? netherMove() : openMove());
    }

    private void beginLanding(boolean toMend) {
        landToMend = toMend;
        state = State.Land;
        spot = null;
        spotTick = -100;
        spotSearches = 0;
        warnedCritical = false;
        badSpots.clear();
        warnedSpot = false;
    }

    private void land() {
        if (mc.player.isOnGround()) {
            if (landToMend) {
                state = State.Mend;
                throwTick = ticks;
                warnedXp = warnedPlayer = false;
                return;
            }
            arrive();
            return;
        }
        if (!mc.player.isGliding()) {
            deploy();
            return;
        }

        Vec3d feet = mc.player.getEntityPos();
        if (spot != null && !standable(spot)) {
            badSpots.add(spot.asLong());
            spot = null;
        }
        int radius = Math.min(SPOT_RADIUS * (1 + spotSearches / 2), MAX_SPOT_RADIUS);
        if (wear() < CRITICAL_WEAR) radius = MAX_SPOT_RADIUS;
        if (spot == null && ticks - spotTick >= (radius > SPOT_RADIUS ? SPOT_SEARCH_TICKS * 2 : SPOT_SEARCH_TICKS)) {
            spotTick = ticks;
            spotSearches++;
            spot = findSpot(landToMend ? feet : new Vec3d(goalX, feet.y, goalZ), radius);
            spotProgress = Double.MAX_VALUE;
            spotProgressTick = ticks;
        }

        if (spot == null) {
            boolean lastTry = radius >= MAX_SPOT_RADIUS && spotSearches > 6;
            if (!landToMend && lastTry) {
                error("No safe ground near the destination, take over.");
                toggle();
                return;
            }
            if (!warnedSpot) warning(landToMend ? "No safe ground below, flying on until there is some." : "No safe ground at the destination yet, looking wider.");
            warnedSpot = true;
            if (landToMend && wear() < CRITICAL_WEAR && !warnedCritical) {
                warning("The elytra is almost broken and there is still no safe ground.");
                warnedCritical = true;
            }
            if (landToMend && horizontalDistance() > ARRIVE_RADIUS) {
                trackProgress(horizontalDistance());
                ElytraFly.pilot(nether() ? netherMove() : openMove());
            } else {
                ElytraFly.pilot(Vec3d.ZERO);
            }
            return;
        }

        ElytraFly fly = Modules.get().get(ElytraFly.class);
        Vec3d target = Vec3d.ofBottomCenter(spot);
        double dx = target.x - feet.x;
        double dz = target.z - feet.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        double height = feet.y - target.y;

        double left = horizontal + Math.max(height, 0);
        if (left < spotProgress - 0.5) {
            spotProgress = left;
            spotProgressTick = ticks;
        } else if (ticks - spotProgressTick > STUCK_TICKS / 2) {
            badSpots.add(spot.asLong());
            spot = null;
            return;
        }

        double x = 0;
        double z = 0;
        double y;
        if (horizontal > 0.3) {
            double speed = Math.min(fly.horizontalSpeed(), horizontal);
            x = dx / horizontal * speed;
            z = dz / horizontal * speed;
            y = 0;
        } else {
            x = dx;
            z = dz;
            if (height > SLOW_ZONE) y = -Math.min(fly.downSpeed(), height - SLOW_ZONE + landSpeed.get());
            else y = -Math.min(landSpeed.get(), Math.max(height, 0) + 0.1);
        }
        ElytraFly.pilot(new Vec3d(x, y, z));
    }

    private void mend() {
        if (!mc.player.isOnGround()) {
            if (mc.player.isGliding()) beginLanding(true);
            return;
        }

        ItemStack chest = mc.player.getEquippedStack(EquipmentSlot.CHEST);
        if (!chest.isOf(Items.ELYTRA)) {
            error("The elytra is gone from the chest slot.");
            toggle();
            return;
        }
        if (!chest.isDamaged()) {
            info("Elytra repaired, flying on.");
            state = State.Takeoff;
            takeoffTick = ticks;
            jumps = 0;
            return;
        }
        if (!hasMending(chest)) {
            error("This elytra has no Mending, so it can't be repaired with bottles.");
            toggle();
            return;
        }

        if (warnPlayers.get() && !warnedPlayer) {
            for (PlayerEntity player : mc.world.getPlayers()) {
                if (player == mc.player || Friends.get().isFriend(player) || player.distanceTo(mc.player) > 48) continue;
                warning("%s is near while you mend.", player.getName().getString());
                warnedPlayer = true;
                break;
            }
        }

        int bottle = InventoryManager.get().find(stack -> stack.isOf(Items.EXPERIENCE_BOTTLE), 0, InventoryManager.MAIN_END);
        if (bottle == -1) {
            if (!warnedXp) warning("Out of experience bottles, waiting here.");
            warnedXp = true;
            return;
        }
        warnedXp = false;

        RotationManager rotations = RotationManager.get();
        rotations.request(ROTATE_PRIORITY, mc.player.getYaw(), 90, 180, false);
        if (ticks - throwTick < THROW_TICKS) return;
        if (!rotations.isLookingAt(new Box(mc.player.getBlockPos().down()), 3)) return;

        InventoryManager.get().withItem(bottle, InventoryManager.SwitchMode.Silent, () -> {
            float pitch = mc.player.getPitch();
            mc.player.setPitch(90);
            mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
            mc.player.setPitch(pitch);
        });
        throwTick = ticks;
    }

    private void arrive() {
        BlockPos exact = new BlockPos(MathHelper.floor(goalX), goalY != null ? goalY : mc.player.getBlockY(), MathHelper.floor(goalZ));
        double left = Math.sqrt(mc.player.getBlockPos().getSquaredDistance(exact.getX(), mc.player.getBlockY(), exact.getZ()));
        if (walk.get() && left > 1.5 && BaritoneGoal.walk(exact, goalY != null)) info("Landed, Baritone walks the last %.0f blocks.", left);
        else info("Arrived.");
        hasGoal = false;
        toggle();
    }

    private void deploy() {
        if (ticks - deployTick < 2 || !hasUsableElytra() || mc.player.hasVehicle() || mc.player.getAbilities().flying) return;
        if (mc.player.checkGliding()) {
            mc.getNetworkHandler().sendPacket(new ClientCommandC2SPacket(mc.player, ClientCommandC2SPacket.Mode.START_FALL_FLYING));
            deployTick = ticks;
        }
    }

    private void trackProgress(double distance) {
        if (distance < bestDistance - 2) {
            bestDistance = distance;
            bestTick = ticks;
            extraHeight = 0;
            warnedStuck = false;
            if (deadEnd != null && mc.player.getEntityPos().distanceTo(deadEnd) > 48) {
                deadEnd = null;
                deadEnds = 0;
            }
            return;
        }
        if (ticks - bestTick < STUCK_TICKS) return;

        bestTick = ticks;
        if (nether()) planTick = -1000;
        else extraHeight = Math.min(extraHeight + 16, 64);
        if (!warnedStuck) warning("Not getting closer, trying another way.");
        warnedStuck = true;
    }

    // Open dimensions: cruise above the terrain ahead

    private Vec3d openMove() {
        ElytraFly fly = Modules.get().get(ElytraFly.class);
        Vec3d feet = mc.player.getEntityPos();
        double dx = goalX - feet.x;
        double dz = goalZ - feet.z;
        double distance = Math.sqrt(dx * dx + dz * dz);
        if (distance < 1.0E-3) return Vec3d.ZERO;
        double ux = dx / distance;
        double uz = dz / distance;

        double unloaded = Double.MAX_VALUE;
        int terrain = mc.world.getBottomY();
        scan:
        for (double step = 0; step <= Math.min(LOOKAHEAD, distance + 8); step += 2) {
            for (int side = -1; side <= 1; side++) {
                int x = MathHelper.floor(feet.x + ux * step - uz * side * 1.5);
                int z = MathHelper.floor(feet.z + uz * step + ux * side * 1.5);
                if (!loaded(x, z)) {
                    unloaded = step;
                    break scan;
                }
                terrain = Math.max(terrain, mc.world.getTopY(Heightmap.Type.MOTION_BLOCKING, x, z));
            }
        }

        int cruise = mc.world.getRegistryKey() == World.END ? endY.get() : overworldY.get();
        double targetY = Math.min(Math.max(cruise, terrain + clearance.get()) + extraHeight, mc.world.getTopYInclusive() - 2);

        double horizontal = Math.min(fly.horizontalSpeed(), distance);
        double below = targetY - feet.y;
        if (below > 3) horizontal = Math.min(horizontal, Math.max(0.3, fly.horizontalSpeed() * 3 / below));
        if (unloaded != Double.MAX_VALUE) horizontal = Math.min(horizontal, Math.max(0, (unloaded - UNLOADED_MARGIN) / 4));

        double y = MathHelper.clamp(below, -fly.downSpeed(), fly.upSpeed());
        return new Vec3d(ux * horizontal, y, uz * horizontal);
    }

    // Nether: follow the nether-pathfinder route

    private Vec3d netherMove() {
        if (route == null) return Vec3d.ZERO;

        Vec3d feet = mc.player.getEntityPos();
        if (backtracking) return backtrack(feet);

        plan();
        if (trail.isEmpty() || trail.getLast().distanceTo(feet) >= 1.2) {
            trail.addLast(feet);
            if (trail.size() > 256) trail.removeFirst();
        }

        boolean atEnd = !path.isEmpty() && pathIndex >= path.size() - 1 && point(path.size() - 1).distanceTo(feet) < 2.5;
        if (atEnd && pathFinished) {
            beginLanding(false);
            return Vec3d.ZERO;
        }
        if (path.isEmpty() || atEnd) {
            if (endTick == -1) endTick = ticks;
        } else {
            endTick = -1;
        }
        if (failures >= 2 || endTick != -1 && ticks - endTick > 40) {
            startBacktrack(feet);
            return Vec3d.ZERO;
        }
        if (path.isEmpty()) return Vec3d.ZERO;

        while (pathIndex < path.size() - 1 && point(pathIndex).distanceTo(feet) < 2.5) pathIndex++;

        int target = pathIndex;
        for (int i = pathIndex + 1; i < Math.min(path.size(), pathIndex + 8); i++) {
            if (!free(feet, point(i))) break;
            target = i;
        }
        pathIndex = target;

        Vec3d delta = point(target).subtract(feet);
        double length = delta.length();
        if (length < 1.0E-3) return Vec3d.ZERO;

        ElytraFly fly = Modules.get().get(ElytraFly.class);
        double speed = Math.min(fly.horizontalSpeed(), length);
        double unloaded = unloadedAhead(feet, delta.multiply(1 / length));
        if (unloaded != Double.MAX_VALUE) speed = Math.min(speed, Math.max(0, (unloaded - UNLOADED_MARGIN) / 4));

        Vec3d move = delta.multiply(speed / length);
        return new Vec3d(move.x, MathHelper.clamp(move.y, -fly.downSpeed(), fly.upSpeed()), move.z);
    }

    private void startBacktrack(Vec3d feet) {
        deadEnd = feet;
        if (++deadEnds > MAX_DEAD_ENDS || trail.size() < 2) {
            error("Can't find a way on from here, take over.");
            toggle();
            return;
        }

        backtracking = true;
        crumbTick = ticks;
        crumbBest = Double.MAX_VALUE;
        if (planning != null) planning.cancel(false);
        planning = null;
        detail = "backtrack";
    }

    /** Flies back along where it came from, out of a dead end, then plans again from there with tight routes allowed. */
    private Vec3d backtrack(Vec3d feet) {
        while (!trail.isEmpty() && trail.getLast().distanceTo(feet) < 1.0) {
            trail.removeLast();
            crumbBest = Double.MAX_VALUE;
            crumbTick = ticks;
        }
        if (trail.isEmpty() || feet.distanceTo(deadEnd) >= BACKTRACK_DISTANCE) {
            backtracking = false;
            failures = 1;
            endTick = -1;
            planTick = -1000;
            path = List.of();
            detail = "replanning";
            return Vec3d.ZERO;
        }

        Vec3d crumb = trail.getLast();
        double length = crumb.distanceTo(feet);
        if (length < crumbBest - 0.3) {
            crumbBest = length;
            crumbTick = ticks;
        } else if (ticks - crumbTick > 20) {
            trail.removeLast();
            crumbBest = Double.MAX_VALUE;
            crumbTick = ticks;
            return Vec3d.ZERO;
        }

        ElytraFly fly = Modules.get().get(ElytraFly.class);
        Vec3d move = crumb.subtract(feet).multiply(Math.min(fly.horizontalSpeed(), length) / length);
        return new Vec3d(move.x, MathHelper.clamp(move.y, -fly.downSpeed(), fly.upSpeed()), move.z);
    }

    private void plan() {
        if (planning != null) {
            if (!planning.isDone()) return;
            NetherRoute.Path result = null;
            try {
                result = planning.getNow(null);
            } catch (Exception ignored) {
            }
            planning = null;

            boolean useful = result != null && result.points().size() > 1;
            boolean atGoal = false;
            if (useful) {
                Vec3d last = Vec3d.ofBottomCenter(result.points().getLast());
                double dx = last.x - goalX;
                double dz = last.z - goalZ;
                atGoal = dx * dx + dz * dz < 16 * 16;
                boolean nowhere = last.distanceTo(mc.player.getEntityPos()) < 4 || deadEnd != null && last.distanceTo(deadEnd) < 4;
                if (!atGoal && nowhere) useful = false;
            }

            if (useful) {
                path = result.points();
                pathIndex = nearest(path, mc.player.getEntityPos());
                pathFinished = result.finished() && atGoal;
                failures = 0;
                endTick = -1;
                detail = null;
            } else {
                failures++;
                detail = "no route";
            }
        }

        boolean ending = !path.isEmpty() && !pathFinished && pathIndex >= path.size() - 2;
        boolean due = path.isEmpty() || ending || ticks - planTick > REPLAN_TICKS || blockedAhead();
        if (!due || ticks - planTick < 10) return;

        planTick = ticks;
        int x = MathHelper.floor(goalX);
        int z = MathHelper.floor(goalZ);
        planning = route.find(mc.player.getBlockPos(), new BlockPos(x, goalY != null ? goalY : openY(x, mc.player.getBlockY(), z), z), failures % 2 == 1);
    }

    /** An air height near the given one in the destination column, so the route doesn't end inside netherrack. */
    private int openY(int x, int y, int z) {
        if (!loaded(x, z)) return y;
        BlockPos.Mutable pos = new BlockPos.Mutable();
        for (int offset = 0; offset < NetherRoute.HEIGHT; offset++) {
            for (int sign = 1; sign >= -1; sign -= 2) {
                int at = y + offset * sign;
                if (at < 1 || at > NetherRoute.HEIGHT - 3) continue;
                if (passable(pos.set(x, at, z)) && passable(pos.set(x, at + 1, z))) return at;
            }
        }
        return y;
    }

    private boolean blockedAhead() {
        for (int i = pathIndex; i < Math.min(path.size(), pathIndex + 3); i++) {
            BlockPos pos = path.get(i);
            if (loaded(pos.getX(), pos.getZ()) && !mc.world.getBlockState(pos).getCollisionShape(mc.world, pos).isEmpty()) return true;
        }
        return false;
    }

    private static int nearest(List<BlockPos> points, Vec3d to) {
        int best = 0;
        double bestDistance = Double.MAX_VALUE;
        for (int i = 0; i < points.size(); i++) {
            double distance = Vec3d.ofBottomCenter(points.get(i)).squaredDistanceTo(to);
            if (distance < bestDistance) {
                best = i;
                bestDistance = distance;
            }
        }
        return best;
    }

    private Vec3d point(int index) {
        return Vec3d.ofBottomCenter(path.get(index));
    }

    private boolean free(Vec3d from, Vec3d to) {
        Vec3d delta = to.subtract(from);
        int steps = Math.max(1, MathHelper.ceil(delta.length() / 0.5));
        Box box = mc.player.getBoundingBox();
        for (int i = 1; i <= steps; i++) {
            Vec3d at = from.add(delta.multiply(i / (double) steps));
            if (!loaded(MathHelper.floor(at.x), MathHelper.floor(at.z))) return false;
            if (!mc.world.isSpaceEmpty(mc.player, box.offset(at.subtract(from)))) return false;
        }
        return true;
    }

    private double unloadedAhead(Vec3d from, Vec3d direction) {
        for (double step = 4; step <= LOOKAHEAD; step += 4) {
            Vec3d at = from.add(direction.multiply(step));
            if (!loaded(MathHelper.floor(at.x), MathHelper.floor(at.z))) return step;
        }
        return Double.MAX_VALUE;
    }

    private void openRoute() {
        if (route == null) route = new NetherRoute();
        route.open();
        path = List.of();
        pathIndex = 0;
        planning = null;
        planTick = -1000;
        failures = 0;
        endTick = -1;
        trail.clear();
        backtracking = false;
        deadEnd = null;
        deadEnds = 0;
        detail = null;
        queued.clear();
        chunkQueue.clear();

        int radius = mc.options.getClampedViewDistance() + 1;
        ChunkPos center = mc.player.getChunkPos();
        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) queueChunk(center.x + x, center.z + z);
        }
    }

    private void closeRoute() {
        if (planning != null) planning.cancel(false);
        planning = null;
        path = List.of();
        queued.clear();
        chunkQueue.clear();
        if (route != null) route.close();
    }

    private void queueChunk(int x, int z) {
        long key = ChunkPos.toLong(x, z);
        if (queued.add(key)) chunkQueue.enqueue(key);
    }

    private void feedChunks() {
        if (route == null || !nether()) return;

        for (int i = 0; i < CHUNKS_PER_TICK && !chunkQueue.isEmpty(); i++) {
            long key = chunkQueue.dequeueLong();
            queued.remove(key);
            int x = ChunkPos.getPackedX(key);
            int z = ChunkPos.getPackedZ(key);
            if (mc.world.getChunkManager().isChunkLoaded(x, z)) route.insert(mc.world.getChunk(x, z));
        }

        if (ticks - cullTick > 200) {
            cullTick = ticks;
            route.cull(mc.player.getChunkPos().x, mc.player.getChunkPos().z, 2048);
        }
    }

    @EventHandler
    private void onChunk(ChunkDataEvent event) {
        if (route != null && nether()) queueChunk(event.chunk().getPos().x, event.chunk().getPos().z);
    }

    @EventHandler
    private void onPacket(PacketApplyEvent.Post event) {
        if (route == null || mc.world == null || !nether()) return;

        switch (event.packet) {
            case BlockUpdateS2CPacket packet -> queueChunk(packet.getPos().getX() >> 4, packet.getPos().getZ() >> 4);
            case ChunkDeltaUpdateS2CPacket packet -> packet.visitUpdates((pos, state) -> queueChunk(pos.getX() >> 4, pos.getZ() >> 4));
            default -> {}
        }
    }

    @EventHandler
    private void onWorldReset(WorldResetEvent event) {
        if (!isActive()) return;
        info("World changed, stopping. Start again with .pilot in this dimension.");
        toggle();
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        if (isActive()) toggle();
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (!render.get()) return;

        if (nether() && path.size() > pathIndex) {
            Vec3d last = mc.player.getEntityPos();
            for (int i = pathIndex; i < path.size(); i++) {
                Vec3d next = point(i);
                event.renderer.line(last.x, last.y, last.z, next.x, next.y, next.z, color.get());
                last = next;
            }
        }
        if (spot != null && state == State.Land) event.renderer.box(spot, color.get(), color.get(), ShapeMode.Lines, 0);
    }

    // Landing spots

    private BlockPos findSpot(Vec3d around, int radius) {
        Vec3d feet = mc.player.getEntityPos();
        int top = MathHelper.floor(feet.y);
        int bottom = Math.max(mc.world.getBottomY() + 1, top - SPOT_DEPTH);
        int cx = MathHelper.floor(around.x);
        int cz = MathHelper.floor(around.z);

        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        BlockPos.Mutable pos = new BlockPos.Mutable();

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                double distance = Math.sqrt(dx * dx + dz * dz);
                if (distance > radius || distance >= bestScore) continue;
                int x = cx + dx;
                int z = cz + dz;
                if (!loaded(x, z)) continue;

                for (int y = top; y >= bottom; y--) {
                    pos.set(x, y, z);
                    if (!passable(pos)) break;
                    pos.set(x, y - 1, z);
                    if (passable(pos)) continue;

                    BlockPos stand = new BlockPos(x, y, z);
                    if (!badSpots.contains(stand.asLong()) && standable(stand)) {
                        double score = distance + (top - y) * 0.05;
                        if (score < bestScore && free(feet, new Vec3d(x + 0.5, feet.y, z + 0.5))) {
                            best = stand;
                            bestScore = score;
                        }
                    }
                    break;
                }
            }
        }

        return best;
    }

    private boolean passable(BlockPos pos) {
        BlockState state = mc.world.getBlockState(pos);
        return state.getCollisionShape(mc.world, pos).isEmpty() && state.getFluidState().isEmpty();
    }

    private boolean standable(BlockPos stand) {
        BlockPos ground = stand.down();
        BlockState state = mc.world.getBlockState(ground);
        if (DANGER.contains(state.getBlock()) || !state.getFluidState().isEmpty()) return false;
        if (!state.isSideSolidFullSquare(mc.world, ground, Direction.UP)) return false;
        if (!passable(stand) || !passable(stand.up())) return false;

        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dy = -1; dy <= 1; dy++) {
                    BlockState near = mc.world.getBlockState(stand.add(dx, dy, dz));
                    if (near.getFluidState().isIn(FluidTags.LAVA) || near.isOf(Blocks.FIRE) || near.isOf(Blocks.SOUL_FIRE)) return false;
                }
            }
        }
        return true;
    }

    // Elytra

    private boolean hasUsableElytra() {
        return LivingEntity.canGlideWith(mc.player.getEquippedStack(EquipmentSlot.CHEST), EquipmentSlot.CHEST);
    }

    private double wear() {
        return wear(mc.player.getEquippedStack(EquipmentSlot.CHEST));
    }

    private static double wear(ItemStack stack) {
        if (!stack.isDamageable()) return 1;
        return (stack.getMaxDamage() - stack.getDamage()) / (double) stack.getMaxDamage();
    }

    private boolean needsMend() {
        return mc.player.getEquippedStack(EquipmentSlot.CHEST).isOf(Items.ELYTRA) && wear() <= mendAt.get() / 100;
    }

    private boolean swapSpare() {
        if (!spare.get()) return false;

        ScreenHandler handler = mc.player.playerScreenHandler;
        if (mc.player.currentScreenHandler != handler || !handler.getCursorStack().isEmpty()) return false;

        int best = -1;
        double bestWear = mendAt.get() / 100 + 0.05;
        for (int i = 0; i < InventoryManager.MAIN_END; i++) {
            ItemStack stack = InventoryManager.get().stack(i);
            if (!stack.isOf(Items.ELYTRA) || !LivingEntity.canGlideWith(stack, EquipmentSlot.CHEST)) continue;
            double w = wear(stack);
            if (w > bestWear) {
                best = i;
                bestWear = w;
            }
        }
        if (best == -1) return false;

        int slot = InventoryManager.get().handlerSlot(handler, best);
        if (slot == -1) return false;
        mc.interactionManager.clickSlot(handler.syncId, slot, 0, SlotActionType.PICKUP, mc.player);
        mc.interactionManager.clickSlot(handler.syncId, CHEST_SLOT, 0, SlotActionType.PICKUP, mc.player);
        mc.interactionManager.clickSlot(handler.syncId, slot, 0, SlotActionType.PICKUP, mc.player);
        info("Put on a spare elytra (%.0f%%).", bestWear * 100);
        return true;
    }

    private static boolean hasMending(ItemStack stack) {
        for (var entry : stack.getEnchantments().getEnchantmentEntries()) {
            if (entry.getKey().matchesKey(Enchantments.MENDING)) return true;
        }
        return false;
    }

    // Helpers

    private static boolean routesAvailable() {
        return FabricLoader.getInstance().isModLoaded("dev_babbaj_nether-pathfinder") && NetherRoute.available();
    }

    private double horizontalDistance() {
        if (mc.player == null) return 0;
        double dx = goalX - mc.player.getX();
        double dz = goalZ - mc.player.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    private boolean nether() {
        return mc.world != null && mc.world.getRegistryKey() == World.NETHER;
    }

    private boolean loaded(int x, int z) {
        return mc.world.getChunkManager().isChunkLoaded(x >> 4, z >> 4);
    }

    /** Talks to Baritone by reflection, so the addon still runs without it. */
    private static final class BaritoneGoal {
        static boolean walk(BlockPos pos, boolean exactY) {
            try {
                Class<?> api = Class.forName("baritone.api.BaritoneAPI");
                Object provider = api.getMethod("getProvider").invoke(null);
                Object baritone = Class.forName("baritone.api.IBaritoneProvider").getMethod("getPrimaryBaritone").invoke(provider);
                Object process = Class.forName("baritone.api.IBaritone").getMethod("getCustomGoalProcess").invoke(baritone);

                Class<?> goalType = Class.forName("baritone.api.pathing.goals.Goal");
                Object goal = exactY
                    ? Class.forName("baritone.api.pathing.goals.GoalBlock").getConstructor(int.class, int.class, int.class).newInstance(pos.getX(), pos.getY(), pos.getZ())
                    : Class.forName("baritone.api.pathing.goals.GoalXZ").getConstructor(int.class, int.class).newInstance(pos.getX(), pos.getZ());
                Class.forName("baritone.api.process.ICustomGoalProcess").getMethod("setGoalAndPath", goalType).invoke(process, goal);
                return true;
            } catch (Throwable ignored) {
                return false;
            }
        }
    }
}
