package dev.coyduck.modules;

import dev.coyduck.events.GameLeftEvent;
import dev.coyduck.events.TickEvent;
import dev.coyduck.events.WorldResetEvent;
import dev.coyduck.utils.Trace;
import dev.coyduck.Coyduck;
import dev.coyduck.managers.InteractionManager;
import dev.coyduck.managers.InventoryManager;
import dev.coyduck.managers.LatencyManager;
import dev.coyduck.managers.MiningManager;
import dev.coyduck.managers.RotationManager;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.Entity;
import net.minecraft.block.BlockState;
import net.minecraft.entity.decoration.AbstractDecorationEntity;
import net.minecraft.entity.projectile.thrown.EnderPearlEntity;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.util.Util;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.RaycastContext;

import java.util.ArrayList;
import java.util.List;

public class PearlPhase extends Module {
    private static final int ROTATE_PRIORITY = 130;
    private static final double SPEED = 1.5;
    private static final double GRAVITY = 0.03;
    private static final double DRAG = 0.99;
    private static final double WIDTH = 0.3;
    private static final double STANDING = 1.8;
    private static final double CRAWLING = 0.6;
    private static final double CRAWL_EYE = 0.4;
    private static final float CRAWL_LOWEST = -30;
    private static final float BLIND_PITCH = -10;
    private static final int MAX_TICKS = 8;
    private static final int AIM_TICKS = 10;
    private static final int ROBUST_CANDIDATES = 60;
    private static final float[][] SPREAD = {{0.5f, 0}, {-0.5f, 0}, {0, 0.5f}, {0, -0.5f}};
    private static final double EYE_CLEARANCE = 0.6 * 0.8 + 0.02;
    private static final double MIN_OVERLAP = 0.005;
    private static final double FULL_OVERLAP = 0.03;
    private static final float MIN_HEALTH = 3;
    private static final int CLEAR_TICKS = 16;
    private static final int CLEAR_EVERY = 2;
    private static final double CLEAR_REACH = 3;
    private static final int MINE_PRIORITY = 160;
    private static final int CELL_TICKS = 20;
    private static final int WALL_TICKS = 10;
    private static final long AUTO_GAP = 1000;
    private static final long VERIFY_MIN = 600;
    private static final int MAX_RETRIES = 1;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Double> depth = sgGeneral.add(new DoubleSetting.Builder()
        .name("depth")
        .description("Preferred depth into each wall in blocks.")
        .defaultValue(0.22)
        .range(0.05, 0.28)
        .sliderRange(0.05, 0.28)
        .build()
    );

    private final Setting<Boolean> eyeInWall = sgGeneral.add(new BoolSetting.Builder()
        .name("eye-in-wall")
        .description("Allows landing with your eyes in a wall.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> corner = sgGeneral.add(new BoolSetting.Builder()
        .name("prefer-corner")
        .description("Phases into two walls at once when possible.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> clearCell = sgGeneral.add(new BoolSetting.Builder()
        .name("clear-cell")
        .description("Breaks climbables in your cell before throwing.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> placeWall = sgGeneral.add(new BoolSetting.Builder()
        .name("place-wall")
        .description("Places obsidian to phase into if no wall is beside you.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> auto = sgGeneral.add(new BoolSetting.Builder()
        .name("auto")
        .description("Phases by itself when you walk into a wall.")
        .defaultValue(false)
        .build()
    );

    private enum State { Idle, Cell, Decoration, Wall, Aim, Verify }

    private State state = State.Idle;
    private boolean blind;
    private float targetYaw;
    private float targetPitch;
    private int slot;
    private int aimTicks;
    private int clearTicks;
    private int retries;
    private long verifyUntil;
    private long thrownAt;
    private long nextAuto;
    private BlockPos cell;
    private BlockPos wall;
    private Vec3d planned;

    public PearlPhase() {
        super(Coyduck.CATEGORY, "pearl-phase", "Phases you into walls with a pearl.");
    }

    @Override
    public void onActivate() {
        if (mc.player == null || mc.world == null) {
            toggle();
            return;
        }
        state = State.Idle;
        retries = 0;
        nextAuto = 0;
        if (auto.get()) {
            if (pearlSlot() == -1) {
                error("No ender pearls.");
                toggle();
            }
            return;
        }
        start();
    }

    @Override
    public void onDeactivate() {
        MiningManager.get().release(this);
        state = State.Idle;
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        toggle();
    }

    @EventHandler
    private void onWorldReset(WorldResetEvent event) {
        toggle();
    }

    private int pearlSlot() {
        return InventoryManager.get().find(stack -> stack.isOf(Items.ENDER_PEARL), 0, InventoryManager.MAIN_END);
    }

    private boolean coolingDown() {
        return mc.player.getItemCooldownManager().isCoolingDown(Items.ENDER_PEARL.getDefaultStack());
    }

    private void start() {
        if (mc.player.getHealth() + mc.player.getAbsorptionAmount() <= MIN_HEALTH) {
            fail("Too low for a pearl, it would hurt you.");
            return;
        }
        if (coolingDown()) {
            fail("Pearl is on cooldown.");
            return;
        }
        slot = pearlSlot();
        if (slot == -1) {
            fail("No ender pearls.");
            return;
        }
        aimTicks = 0;
        clearTicks = 0;
        blind = false;
        prepare(clearCell.get());
    }

    private void prepare(boolean breakCell) {
        cell = breakCell ? climbable() : null;
        if (cell != null && MiningManager.get().mine(this, MINE_PRIORITY, cell, true, InventoryManager.SwitchMode.Silent, InteractionManager.SwingMode.Packet)) {
            Trace.log("phase breaks a {} in its own cell", mc.world.getBlockState(cell).getBlock().getName().getString());
            clearTicks = CELL_TICKS;
            state = State.Cell;
            return;
        }
        if (plan()) {
            state = State.Aim;
            return;
        }
        if (decoration() != null) {
            clearTicks = CLEAR_TICKS;
            state = State.Decoration;
            return;
        }
        wall = placeWall.get() && walls() == 0 ? wallSpot() : null;
        if (wall != null) {
            clearTicks = WALL_TICKS;
            state = State.Wall;
            return;
        }
        if (enclosed()) {
            blindThrow();
            return;
        }
        Trace.log("phase found no throw: crawling {} pose {} height {} walls {}", mc.player.isCrawling(), mc.player.getPose(), mc.player.getBoundingBox().getLengthY(), walls());
        fail(reason());
    }

    private boolean enclosed() {
        return walls() == 4 && mc.player.getBoundingBox().getLengthY() < 1.5;
    }

    private void blindThrow() {
        Vec3d pos = mc.player.getEntityPos();
        double dx = pos.x - (Math.floor(pos.x) + 0.5);
        double dz = pos.z - (Math.floor(pos.z) + 0.5);
        double sx = Math.abs(dx) < 1.0E-3 ? 1 : Math.signum(dx);
        double sz = Math.abs(dz) < 1.0E-3 ? 1 : Math.signum(dz);
        targetYaw = (float) Math.toDegrees(Math.atan2(-sx, sz));
        targetPitch = BLIND_PITCH;
        planned = pos;
        blind = true;
        state = State.Aim;
        Trace.log("phase found no landing in a closed cell, throws blind yaw {} pitch {} pose {} height {}", targetYaw, targetPitch, mc.player.getPose(), mc.player.getBoundingBox().getLengthY());
    }

    private void fail(String message) {
        MiningManager.get().release(this);
        if (auto.get()) {
            Trace.log("phase auto skipped: {}", message);
            state = State.Idle;
            nextAuto = Util.getMeasuringTimeMs() + AUTO_GAP;
            return;
        }
        error(message);
        toggle();
    }

    private void done() {
        if (auto.get()) {
            state = State.Idle;
            nextAuto = Util.getMeasuringTimeMs() + AUTO_GAP;
            return;
        }
        toggle();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;

        RotationManager rotations = RotationManager.get();
        switch (state) {
            case Idle -> {
                if (wantsAuto()) {
                    retries = 0;
                    start();
                }
            }
            case Cell -> breakCell();
            case Decoration -> {
                if (clear(rotations)) state = State.Aim;
            }
            case Wall -> buildWall();
            case Aim -> aim(rotations);
            case Verify -> verify();
        }
    }

    private boolean wantsAuto() {
        if (!auto.get() || Util.getMeasuringTimeMs() < nextAuto) return false;
        if (!mc.player.isOnGround() || !mc.player.horizontalCollision || mc.player.input.getMovementInput().lengthSquared() < 0.01) return false;
        if (coolingDown() || pearlSlot() == -1 || mc.player.isUsingItem()) return false;
        return mc.world.isSpaceEmpty(mc.player, mc.player.getBoundingBox().contract(0.01, 0, 0.01));
    }

    private void breakCell() {
        MiningManager mining = MiningManager.get();
        boolean gone = !mc.world.getBlockState(cell).isIn(BlockTags.CLIMBABLE);
        if (gone || mining.owns(this) && mining.wasBroken() || --clearTicks <= 0 || !mining.owns(this)) {
            mining.release(this);
            if (!gone) Trace.log("phase could not break its cell in time, throws anyway");
            cell = null;
            prepare(false);
        }
    }

    private void buildWall() {
        if (plan()) {
            Trace.log("phase placed its own wall");
            state = State.Aim;
            return;
        }
        if (--clearTicks <= 0) {
            fail("Could not place a wall next to you.");
            return;
        }
        InteractionManager interaction = InteractionManager.get();
        if (!interaction.canPlace(wall) || interaction.isPending(wall)) return;
        int obsidian = InventoryManager.get().find(stack -> stack.isOf(Items.OBSIDIAN) || stack.isOf(Items.CRYING_OBSIDIAN), 0, InventoryManager.MAIN_END);
        if (obsidian == -1) {
            fail("No wall next to your feet, and no obsidian to place one.");
            return;
        }
        BlockHitResult hit = interaction.hit(wall, false);
        if (hit == null || !interaction.obstructions(wall).isEmpty()) {
            fail("No wall next to your feet, and no spot to place one.");
            return;
        }
        if (!interaction.ready(wall, false, true, ROTATE_PRIORITY)) return;
        InventoryManager.get().withItemWhileEating(obsidian, InventoryManager.SwitchMode.Silent, () -> interaction.place(wall, hit, InteractionManager.SwingMode.Packet, true, ROTATE_PRIORITY));
    }

    private void aim(RotationManager rotations) {
        rotations.request(ROTATE_PRIORITY, targetYaw, targetPitch, 180, false);
        boolean aimed = NoRotate.frozen() || Math.abs(MathHelper.wrapDegrees(rotations.serverYaw() - targetYaw)) < 0.5 && Math.abs(rotations.serverPitch() - targetPitch) < 0.5;
        if (!aimed) {
            if (++aimTicks < AIM_TICKS) return;
            fail("Could not turn to the throw angle in time, so no pearl was thrown.");
            return;
        }
        if (!blind && mc.player.getEntityPos().squaredDistanceTo(planned) > 0.05 * 0.05 && !plan()) {
            fail("You moved and there is no wall to phase into now.");
            return;
        }

        slot = pearlSlot();
        if (slot == -1) {
            fail("No ender pearls.");
            return;
        }

        boolean thrown = InventoryManager.get().withItem(slot, InventoryManager.SwitchMode.Silent, () -> {
            float yaw = mc.player.getYaw();
            float pitch = mc.player.getPitch();
            mc.player.setYaw(targetYaw);
            mc.player.setPitch(targetPitch);
            mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
            mc.player.setYaw(yaw);
            mc.player.setPitch(pitch);
        });
        if (!thrown) {
            fail("Couldn't switch to the pearl while eating.");
            return;
        }
        Trace.log("phase pearl thrown yaw {} pitch {} from {}", targetYaw, targetPitch, mc.player.getEntityPos());
        thrownAt = Util.getMeasuringTimeMs();
        verifyUntil = thrownAt + Math.max(VERIFY_MIN, 2L * LatencyManager.get().rtt() + 150);
        state = State.Verify;
    }

    private void verify() {
        if (!mc.world.getOtherEntities(mc.player, mc.player.getBoundingBox().expand(4), entity -> entity instanceof EnderPearlEntity pearl && pearl.age < 40 && pearl.getOwner() == mc.player).isEmpty()) {
            Trace.log("phase pearl accepted after {}ms", Util.getMeasuringTimeMs() - thrownAt);
            done();
            return;
        }
        if (!coolingDown()) {
            Trace.log("phase pearl refused by the server after {}ms, retry {}", Util.getMeasuringTimeMs() - thrownAt, retries);
            if (retries++ < MAX_RETRIES) {
                start();
                return;
            }
            fail("The server refused the pearl.");
            return;
        }
        if (Util.getMeasuringTimeMs() > verifyUntil) {
            Trace.log("phase saw no pearl within {}ms", Util.getMeasuringTimeMs() - thrownAt);
            done();
        }
    }

    private boolean clear(RotationManager rotations) {
        clearTicks--;
        if (plan()) {
            clearTicks = 0;
            Trace.log("phase path cleared");
            return true;
        }
        Entity decoration = decoration();
        if (decoration == null || clearTicks <= 0) {
            fail(decoration == null ? reason() : "Could not break the item frame or painting in the way.");
            return false;
        }
        Box box = decoration.getBoundingBox();
        rotations.request(ROTATE_PRIORITY, box.getCenter(), 180, true);
        if (clearTicks % CLEAR_EVERY == 0 && rotations.isLookingAt(box, CLEAR_REACH)) {
            mc.interactionManager.attackEntity(mc.player, decoration);
            mc.player.swingHand(Hand.MAIN_HAND);
            Trace.log("phase hits a {} in the pearl's way", decoration.getType().getUntranslatedName());
        }
        return false;
    }

    private BlockPos climbable() {
        Box box = mc.player.getBoundingBox();
        MiningManager mining = MiningManager.get();
        BlockPos best = null;
        int fastest = CELL_TICKS;
        for (BlockPos pos : BlockPos.iterate(MathHelper.floor(box.minX), MathHelper.floor(box.minY), MathHelper.floor(box.minZ), MathHelper.floor(box.maxX - 1.0E-7), MathHelper.floor(box.maxY - 1.0E-7), MathHelper.floor(box.maxZ - 1.0E-7))) {
            BlockState state = mc.world.getBlockState(pos);
            if (!state.isIn(BlockTags.CLIMBABLE) || state.getHardness(mc.world, pos) < 0) continue;
            float delta = mining.delta(state, pos, InventoryManager.get().stack(mining.bestTool(state)));
            int ticks = delta <= 0 ? Integer.MAX_VALUE : (int) Math.ceil(1 / delta);
            if (ticks < fastest) {
                fastest = ticks;
                best = pos.toImmutable();
            }
        }
        return best;
    }

    private int walls() {
        BlockPos feet = mc.player.getBlockPos();
        int walls = 0;
        for (Direction direction : Direction.Type.HORIZONTAL) {
            BlockPos side = feet.offset(direction);
            if (!mc.world.getBlockState(side).getCollisionShape(mc.world, side).isEmpty()) walls++;
        }
        return walls;
    }

    private BlockPos wallSpot() {
        BlockPos feet = mc.player.getBlockPos();
        Vec3d pos = mc.player.getEntityPos();
        InteractionManager interaction = InteractionManager.get();
        BlockPos best = null;
        double closest = Double.MAX_VALUE;
        for (Direction direction : Direction.Type.HORIZONTAL) {
            BlockPos side = feet.offset(direction);
            if (!interaction.canPlace(side) || !interaction.obstructions(side).isEmpty() || interaction.hit(side, false) == null) continue;
            if (mc.player.getBoundingBox().intersects(new Box(side))) continue;
            double gap = Math.abs((direction.getOffsetX() != 0 ? pos.x - (feet.getX() + 0.5 + direction.getOffsetX() * 0.5) : pos.z - (feet.getZ() + 0.5 + direction.getOffsetZ() * 0.5)));
            if (gap < closest) {
                closest = gap;
                best = side;
            }
        }
        return best;
    }

    private Entity decoration() {
        Box around = mc.player.getBoundingBox().expand(1.5, 0.5, 1.5).stretch(0, 0.5, 0);
        Vec3d eye = mc.player.getEyePos();
        Entity best = null;
        double closest = CLEAR_REACH * CLEAR_REACH;
        for (Entity entity : mc.world.getOtherEntities(mc.player, around, entity -> entity instanceof AbstractDecorationEntity && entity.isAlive())) {
            double distance = entity.getBoundingBox().squaredMagnitude(eye);
            if (distance < closest) {
                closest = distance;
                best = entity;
            }
        }
        return best;
    }

    private boolean plan() {
        Vec3d feet = mc.player.getEntityPos();
        planned = feet;
        Vec3d spawn = new Vec3d(feet.x, mc.player.getEyeY() - 0.1, feet.z);
        Vec3d shooter = mc.player.isOnGround() ? new Vec3d(mc.player.getVelocity().x, 0, mc.player.getVelocity().z) : mc.player.getVelocity();

        List<float[]> candidates = new ArrayList<>();
        float lowest = mc.player.isCrawling() ? CRAWL_LOWEST : 40;
        for (float pitch = lowest; pitch <= 90; pitch += 0.5f) {
            for (float yaw = -180; yaw < 180; yaw += 1.5f) {
                double score = landingScore(spawn, shooter, feet, yaw, pitch);
                if (score > 0) candidates.add(new float[] {yaw, pitch, (float) score});
            }
        }
        candidates.sort((a, b) -> Float.compare(b[2], a[2]));

        double best = 0;
        for (int i = 0; i < Math.min(candidates.size(), ROBUST_CANDIDATES); i++) {
            float[] candidate = candidates.get(i);
            double worst = candidate[2];
            for (float[] offset : SPREAD) {
                worst = Math.min(worst, landingScore(spawn, shooter, feet, candidate[0] + offset[0], candidate[1] + offset[1]));
                if (worst <= best) break;
            }
            if (worst > best) {
                best = worst;
                targetYaw = candidate[0];
                targetPitch = candidate[1];
            }
        }
        return best > 0;
    }

    private double landingScore(Vec3d spawn, Vec3d shooter, Vec3d feet, float yaw, float pitch) {
        Vec3d landing = simulate(spawn, shooter, yaw, MathHelper.clamp(pitch, -90, 90));
        if (landing == null || landing.y < feet.y - 0.5 || landing.y > feet.y + 2 || landing.squaredDistanceTo(feet) > 1.5) return 0;
        return score(landing);
    }

    private Vec3d simulate(Vec3d start, Vec3d shooter, float yaw, float pitch) {
        double radYaw = Math.toRadians(yaw);
        double radPitch = Math.toRadians(pitch);
        Vec3d velocity = new Vec3d(-Math.sin(radYaw) * Math.cos(radPitch), -Math.sin(radPitch), Math.cos(radYaw) * Math.cos(radPitch)).normalize().multiply(SPEED).add(shooter);

        Vec3d position = start;
        for (int tick = 0; tick < MAX_TICKS; tick++) {
            velocity = new Vec3d(velocity.x, velocity.y - GRAVITY, velocity.z).multiply(DRAG);
            Vec3d next = position.add(velocity);
            HitResult hit = mc.world.raycast(new RaycastContext(position, next, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, mc.player));
            Vec3d end = hit.getType() != HitResult.Type.MISS ? hit.getPos() : next;
            if (blockedByEntity(position, end)) return null;
            if (hit.getType() != HitResult.Type.MISS) return position;
            position = next;
        }
        return null;
    }

    private boolean blockedByEntity(Vec3d from, Vec3d to) {
        Box path = new Box(from, to).expand(1);
        for (Entity entity : mc.world.getOtherEntities(mc.player, path, entity -> entity.isAlive() && entity.canBeHitByProjectile())) {
            if (entity.getBoundingBox().expand(0.3).raycast(from, to).isPresent()) return true;
        }
        return false;
    }

    private double score(Vec3d landing) {
        double tall = STANDING;
        double eye = mc.player.getStandingEyeHeight();
        if (!mc.world.isSpaceEmpty(new Box(landing.x - 0.05, landing.y + 0.05, landing.z - 0.05, landing.x + 0.05, landing.y + STANDING - 0.05, landing.z + 0.05))) {
            tall = CRAWLING;
            eye = CRAWL_EYE;
        }
        Box box = new Box(landing.x - WIDTH, landing.y, landing.z - WIDTH, landing.x + WIDTH, landing.y + tall, landing.z + WIDTH);
        Box middle = new Box(landing.x - 0.05, landing.y + 0.05, landing.z - 0.05, landing.x + 0.05, landing.y + tall - 0.05, landing.z + 0.05);
        if (!mc.world.isSpaceEmpty(middle)) return 0;
        if (!eyeInWall.get() && !mc.world.isSpaceEmpty(Box.of(landing.add(0, eye, 0), EYE_CLEARANCE, 1.0E-6, EYE_CLEARANCE))) return 0;

        double want = depth.get();
        double total = 0;
        int cells = 0;
        for (BlockPos pos : BlockPos.iterate(MathHelper.floor(box.minX), MathHelper.floor(box.minY), MathHelper.floor(box.minZ), MathHelper.floor(box.maxX), MathHelper.floor(box.maxY), MathHelper.floor(box.maxZ))) {
            VoxelShape shape = mc.world.getBlockState(pos).getCollisionShape(mc.world, pos);
            if (shape.isEmpty()) continue;

            double deepest = 0;
            for (Box part : shape.offset(pos.getX(), pos.getY(), pos.getZ()).getBoundingBoxes()) {
                if (!part.intersects(box) || part.maxY <= landing.y + 0.01) continue;
                Box overlap = part.intersection(box);
                boolean fullX = overlap.getLengthX() > 2 * WIDTH - 0.01;
                boolean fullZ = overlap.getLengthZ() > 2 * WIDTH - 0.01;
                if (fullX && fullZ) return 0;
                double into = Math.min(fullX ? Double.MAX_VALUE : overlap.getLengthX(), fullZ ? Double.MAX_VALUE : overlap.getLengthZ());
                deepest = Math.max(deepest, into);
            }
            if (deepest < MIN_OVERLAP) continue;

            double sure = Math.min(deepest / FULL_OVERLAP, 1);
            double near = Math.max(0, 1 - Math.abs(deepest - want) / want);
            double weight = pos.getY() == MathHelper.floor(landing.y + 0.01) ? 1.0 : 0.6;
            total += weight * (sure + 0.5 * near);
            cells++;
        }

        if (cells == 0) return 0;
        return corner.get() ? total : total / cells;
    }

    private String reason() {
        BlockPos feet = mc.player.getBlockPos();
        int walls = 0;
        int faces = 0;
        for (Direction direction : Direction.Type.HORIZONTAL) {
            if (!mc.world.getBlockState(feet.offset(direction)).getCollisionShape(mc.world, feet.offset(direction)).isEmpty()) walls++;
            if (!mc.world.getBlockState(feet.up().offset(direction)).getCollisionShape(mc.world, feet.up().offset(direction)).isEmpty()) faces++;
        }
        if (walls == 0) return "No wall next to your feet to phase into.";
        if (faces == 4 && !eyeInWall.get()) return "Every head level cell is blocked, which leaves only a sliver for your eyes and no throw hits it. Open one head level side and try again.";
        return "No throw lands you in a wall from here: a crystal or player is in the way, or the pearl would hit a wall first.";
    }
}
