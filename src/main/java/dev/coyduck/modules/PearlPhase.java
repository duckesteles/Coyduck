package dev.coyduck.modules;

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

/**
 * Throws an ender pearl at your feet so it lands you half inside the walls of your hole. A pearl teleports you to
 * where it was one tick before it hit, so the throw is solved by simulating the pearl's flight, not by aiming at a
 * block. It is a plain pearl teleport, nothing the server has to be fooled about.
 */
public class PearlPhase extends Module {
    private static final int ROTATE_PRIORITY = 115;
    private static final double SPEED = 1.5;
    private static final double GRAVITY = 0.03;
    private static final double DRAG = 0.99;
    private static final double WIDTH = 0.3;
    private static final int MAX_TICKS = 8;
    private static final int AIM_TICKS = 10;
    private static final int ROBUST_CANDIDATES = 40;
    private static final float[][] SPREAD = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
    private static final double EYE_CLEARANCE = 0.54;
    private static final float MIN_HEALTH = 3;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Double> depth = sgGeneral.add(new DoubleSetting.Builder()
        .name("depth")
        .description("How far into each wall you end up. Walls at eye height only allow a sliver, so you take no suffocation damage.")
        .defaultValue(0.22)
        .range(0.05, 0.28)
        .sliderRange(0.05, 0.28)
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
        super(Coyduck.CATEGORY, "pearl-phase", "Throws a pearl at your feet so you land half inside the walls next to you. Toggle it when you want to phase.");
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
            error("No throw lands you in a wall: no wall next to you, or a crystal or player is in the way.");
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
        if (!aimed && ++aimTicks < AIM_TICKS) return;
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

        // The use packet carries the camera angles, and the server aims the pearl with those.
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
        toggle();
    }

    private boolean plan() {
        Vec3d feet = mc.player.getEntityPos();
        planned = feet;
        Vec3d spawn = new Vec3d(feet.x, mc.player.getEyeY() - 0.1, feet.z);
        Vec3d shooter = mc.player.isOnGround() ? new Vec3d(mc.player.getVelocity().x, 0, mc.player.getVelocity().z) : mc.player.getVelocity();

        List<float[]> candidates = new ArrayList<>();
        for (float pitch = 50; pitch <= 90; pitch += 1) {
            for (float yaw = -180; yaw < 180; yaw += 2) {
                double score = landingScore(spawn, shooter, feet, yaw, pitch);
                if (score > 0) candidates.add(new float[] {yaw, pitch, (float) score});
            }
        }
        candidates.sort((a, b) -> Float.compare(b[2], a[2]));

        // A pearl leaves with a small random spread, about half a degree; keep the throw whose neighbours also work.
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

    /** Where the pearl puts you: its position at the start of the tick in which it hits something. */
    private Vec3d simulate(Vec3d start, Vec3d shooter, float yaw, float pitch) {
        double radYaw = Math.toRadians(yaw);
        double radPitch = Math.toRadians(pitch);
        Vec3d velocity = new Vec3d(-Math.sin(radYaw) * Math.cos(radPitch), -Math.sin(radPitch), Math.cos(radYaw) * Math.cos(radPitch)).normalize().multiply(SPEED).add(shooter);

        Vec3d position = start;
        for (int tick = 0; tick < MAX_TICKS; tick++) {
            velocity = new Vec3d(velocity.x, velocity.y - GRAVITY, velocity.z).multiply(DRAG);
            Vec3d next = position.add(velocity);
            HitResult hit = mc.world.raycast(new RaycastContext(position, next, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, mc.player));
            // A crystal or player in the way catches the pearl first and teleports you somewhere else.
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

    /** How well a landing tucks you into the walls: depth into each wall, zero if your eyes or middle end up inside one. */
    private double score(Vec3d landing) {
        Box box = new Box(landing.x - WIDTH, landing.y, landing.z - WIDTH, landing.x + WIDTH, landing.y + 1.8, landing.z + WIDTH);
        Box middle = new Box(landing.x - 0.05, landing.y + 0.05, landing.z - 0.05, landing.x + 0.05, landing.y + 1.75, landing.z + 0.05);
        if (!mc.world.isSpaceEmpty(middle)) return 0;
        // The game counts you in a wall when a box 0.8 of your width around your eyes touches one; keep that clear.
        if (!mc.world.isSpaceEmpty(Box.of(landing.add(0, mc.player.getStandingEyeHeight(), 0), EYE_CLEARANCE, 1.0E-6, EYE_CLEARANCE))) return 0;

        double x = 0;
        double z = 0;
        for (BlockPos pos : BlockPos.iterate(MathHelper.floor(box.minX), MathHelper.floor(box.minY), MathHelper.floor(box.minZ), MathHelper.floor(box.maxX), MathHelper.floor(box.maxY), MathHelper.floor(box.maxZ))) {
            VoxelShape shape = mc.world.getBlockState(pos).getCollisionShape(mc.world, pos);
            if (shape.isEmpty()) continue;
            for (Box part : shape.offset(pos.getX(), pos.getY(), pos.getZ()).getBoundingBoxes()) {
                if (!part.intersects(box) || part.maxY <= landing.y + 0.01) continue;
                Box overlap = part.intersection(box);
                boolean fullX = overlap.getLengthX() > 2 * WIDTH - 0.01;
                boolean fullZ = overlap.getLengthZ() > 2 * WIDTH - 0.01;
                if (fullX && fullZ) return 0;
                if (!fullX) x = Math.max(x, overlap.getLengthX());
                if (!fullZ) z = Math.max(z, overlap.getLengthZ());
            }
        }

        double want = depth.get();
        double sx = x > 0 ? 1 - Math.abs(x - want) / want : 0;
        double sz = z > 0 ? 1 - Math.abs(z - want) / want : 0;
        sx = Math.max(sx, 0);
        sz = Math.max(sz, 0);
        return corner.get() ? sx + sz + (sx > 0 && sz > 0 ? 1 : 0) : Math.max(sx, sz);
    }
}
