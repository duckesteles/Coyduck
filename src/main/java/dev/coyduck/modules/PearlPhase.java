package dev.coyduck.modules;

import dev.coyduck.utils.Trace;
import dev.coyduck.Coyduck;
import dev.coyduck.managers.InventoryManager;
import dev.coyduck.managers.RotationManager;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.Entity;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
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
    private static final int MAX_TICKS = 8;
    private static final int AIM_TICKS = 10;
    private static final int ROBUST_CANDIDATES = 60;
    private static final float[][] SPREAD = {{0.5f, 0}, {-0.5f, 0}, {0, 0.5f}, {0, -0.5f}};
    private static final double EYE_CLEARANCE = 0.6 * 0.8 + 0.02;
    private static final double MIN_OVERLAP = 0.005;
    private static final double FULL_OVERLAP = 0.03;
    private static final float MIN_HEALTH = 3;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Double> depth = sgGeneral.add(new DoubleSetting.Builder()
        .name("depth")
        .description("Preferred depth into each wall. Any overlap already stops crystals being placed in that cell. Without eye-in-wall, walls at eye height only allow a sliver so you never suffocate.")
        .defaultValue(0.22)
        .range(0.05, 0.28)
        .sliderRange(0.05, 0.28)
        .build()
    );

    private final Setting<Boolean> eyeInWall = sgGeneral.add(new BoolSetting.Builder()
        .name("eye-in-wall")
        .description("Allows landings that put your eyes in a wall, so it also phases from a closed 1x1 hole. You take 1 suffocation damage every half second there, like the players you fight; gapples cover it.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> corner = sgGeneral.add(new BoolSetting.Builder()
        .name("prefer-corner")
        .description("Goes into two walls at once where it can, which covers you from more sides.")
        .defaultValue(true)
        .build()
    );

    private float targetYaw;
    private float targetPitch;
    private int slot;
    private int aimTicks;
    private Vec3d planned;

    public PearlPhase() {
        super(Coyduck.CATEGORY, "pearl-phase", "Throws a pearl at your feet so you land half inside the walls next to you. Also works while crawling in a one block high gap, scoring landings for the crawling body and trying flatter throws. Toggle it when you want to phase.");
    }

    @Override
    public void onActivate() {
        if (mc.player == null || mc.world == null) {
            toggle();
            return;
        }
        if (mc.player.getHealth() + mc.player.getAbsorptionAmount() <= MIN_HEALTH) {
            error("Too low for a pearl, it would hurt you.");
            toggle();
            return;
        }
        if (mc.player.getItemCooldownManager().isCoolingDown(Items.ENDER_PEARL.getDefaultStack())) {
            error("Pearl is on cooldown.");
            toggle();
            return;
        }
        slot = InventoryManager.get().find(stack -> stack.isOf(Items.ENDER_PEARL), 0, InventoryManager.MAIN_END);
        if (slot == -1) {
            error("No ender pearls.");
            toggle();
            return;
        }
        if (!plan()) {
            error(reason());
            toggle();
            return;
        }
        aimTicks = 0;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;

        RotationManager rotations = RotationManager.get();
        rotations.request(ROTATE_PRIORITY, targetYaw, targetPitch, 180, false);
        boolean aimed = Math.abs(MathHelper.wrapDegrees(rotations.serverYaw() - targetYaw)) < 0.5 && Math.abs(rotations.serverPitch() - targetPitch) < 0.5;
        if (!aimed) {
            if (++aimTicks < AIM_TICKS) return;
            error("Could not turn to the throw angle in time, so no pearl was thrown.");
            toggle();
            return;
        }
        if (mc.player.getEntityPos().squaredDistanceTo(planned) > 0.05 * 0.05 && !plan()) {
            error("You moved and there is no wall to phase into now.");
            toggle();
            return;
        }

        slot = InventoryManager.get().find(stack -> stack.isOf(Items.ENDER_PEARL), 0, InventoryManager.MAIN_END);
        if (slot == -1) {
            error("No ender pearls.");
            toggle();
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
        if (!thrown) error("Couldn't switch to the pearl while eating.");
        else Trace.log("phase pearl thrown yaw {} pitch {} from {}", targetYaw, targetPitch, mc.player.getEntityPos());
        toggle();
    }

    private boolean plan() {
        Vec3d feet = mc.player.getEntityPos();
        planned = feet;
        Vec3d spawn = new Vec3d(feet.x, mc.player.getEyeY() - 0.1, feet.z);
        Vec3d shooter = mc.player.isOnGround() ? new Vec3d(mc.player.getVelocity().x, 0, mc.player.getVelocity().z) : mc.player.getVelocity();

        List<float[]> candidates = new ArrayList<>();
        float lowest = mc.player.isCrawling() ? 0 : 40;
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
        for (net.minecraft.util.math.Direction direction : net.minecraft.util.math.Direction.Type.HORIZONTAL) {
            if (!mc.world.getBlockState(feet.offset(direction)).getCollisionShape(mc.world, feet.offset(direction)).isEmpty()) walls++;
            if (!mc.world.getBlockState(feet.up().offset(direction)).getCollisionShape(mc.world, feet.up().offset(direction)).isEmpty()) faces++;
        }
        if (walls == 0) return "No wall next to your feet to phase into.";
        if (faces == 4 && !eyeInWall.get()) return "Every head level cell is blocked, which leaves only a sliver for your eyes and no throw hits it. Open one head level side and try again.";
        return "No throw lands you in a wall from here: a crystal or player is in the way, or the pearl would hit a wall first.";
    }
}
