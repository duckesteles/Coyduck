package dev.coyduck.modules;

import dev.coyduck.Coyduck;
import dev.coyduck.utils.ExplosionDamage;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.Entity;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.packet.s2c.play.PlayerPositionLookS2CPacket;
import net.minecraft.util.Util;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec2f;
import net.minecraft.util.math.Vec3d;

public class Step extends Module {
    public enum Mode { Auto, Instant, Jump }

    private static final float VANILLA = 0.6f;
    private static final double JUMP_REACH = 1.25;
    private static final double SCAN_LIMIT = 2.5;
    private static final double SCAN_STEP = 1.0 / 16.0;
    private static final double PROBE = 0.3;
    private static final long REJECT_WINDOW = 800;
    private static final long REJECT_RESET = 10_000;
    private static final long FALLBACK_TIME = 15_000;
    private static final int REJECTS_TO_FALL_BACK = 2;
    private static final double GUARD_RANGE = 12;
    private static final double CAMERA_SPEED = 14;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Mode> mode = sgGeneral.add(new EnumSetting.Builder<Mode>()
        .name("mode")
        .description("Instant walks straight up. Jump uses a real jump. Auto walks up and switches to jumping for a while when the server pulls you back.")
        .defaultValue(Mode.Auto)
        .build()
    );

    private final Setting<Double> height = sgGeneral.add(new DoubleSetting.Builder()
        .name("height")
        .description("The highest ledge to walk up. Jump can reach 1.25 at most without jump boost.")
        .defaultValue(1.0)
        .range(0.6, SCAN_LIMIT)
        .sliderRange(0.6, SCAN_LIMIT)
        .build()
    );

    private final Setting<Boolean> holeGuard = sgGeneral.add(new BoolSetting.Builder()
        .name("hole-guard")
        .description("Keeps you from stepping out of a hole while an enemy is close.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> safe = sgGeneral.add(new BoolSetting.Builder()
        .name("safe")
        .description("Won't step up to a spot where a nearby crystal would kill you.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> pauseSneak = sgGeneral.add(new BoolSetting.Builder()
        .name("pause-sneak")
        .description("Doesn't step while you sneak.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> smoothCamera = sgGeneral.add(new BoolSetting.Builder()
        .name("smooth-camera")
        .description("Eases the camera up instead of snapping when you walk up a ledge. Only you see it.")
        .defaultValue(true)
        .build()
    );

    private double lastY;
    private boolean wasOnGround;
    private long steppedAt;
    private long lastReject;
    private int rejects;
    private long fallbackUntil;
    private boolean steppingUp;

    private boolean smoothing;
    private double cameraY;
    private long lastFrame;

    public Step() {
        super(Coyduck.CATEGORY, "step+", "Walks up ledges in the direction you move, never from knockback, and backs off when the server disagrees.");
    }

    public static double cameraY(double y) {
        Modules modules = Modules.get();
        if (modules == null) return y;
        Step module = modules.get(Step.class);
        if (module == null || !module.isActive() || !module.smoothCamera.get()) return y;
        return module.smooth(y);
    }

    private double smooth(double y) {
        long now = System.nanoTime();
        double delta = (now - lastFrame) / 1_000_000_000.0;
        lastFrame = now;

        if (!smoothing || Math.abs(y - cameraY) > SCAN_LIMIT + 0.5) {
            smoothing = false;
            cameraY = y;
            return y;
        }

        cameraY += (y - cameraY) * (1 - Math.exp(-delta * CAMERA_SPEED));
        if (Math.abs(y - cameraY) < 0.005) {
            smoothing = false;
            cameraY = y;
        }
        return cameraY;
    }

    @Override
    public void onActivate() {
        Module meteor = Modules.get().get(meteordevelopment.meteorclient.systems.modules.movement.Step.class);
        if (meteor != null && meteor.isActive()) {
            meteor.toggle();
            info("Disabled %s, it sets the same step height.", meteor.title);
        }

        rejects = 0;
        fallbackUntil = 0;
        steppedAt = 0;
        smoothing = false;
        steppingUp = false;
        if (mc.player != null) {
            lastY = mc.player.getY();
            wasOnGround = mc.player.isOnGround();
            cameraY = lastY;
        }
    }

    @Override
    public void onDeactivate() {
        smoothing = false;
        setStepHeight(VANILLA);
    }

    @Override
    public String getInfoString() {
        return jumping() ? "Jump" : mode.get() == Mode.Instant ? "Instant" : null;
    }

    private boolean jumping() {
        return mode.get() == Mode.Jump || (mode.get() == Mode.Auto && Util.getMeasuringTimeMs() < fallbackUntil);
    }

    @EventHandler
    private void onReceive(PacketEvent.Receive event) {
        if (!(event.packet instanceof PlayerPositionLookS2CPacket)) return;
        mc.execute(this::onPulledBack);
    }

    private void onPulledBack() {
        smoothing = false;
        long now = Util.getMeasuringTimeMs();
        if (mode.get() != Mode.Auto || now - steppedAt > REJECT_WINDOW) return;

        if (now - lastReject > REJECT_RESET) rejects = 0;
        lastReject = now;
        if (++rejects < REJECTS_TO_FALL_BACK) return;

        rejects = 0;
        if (now >= fallbackUntil) info("The server is rejecting steps, jumping up ledges for a while.");
        fallbackUntil = now + FALLBACK_TIME;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null) return;

        trackStep();

        double ledge = allowed() ? ledgeAhead() : -1;
        boolean climb = ledge > VANILLA && !blocked(ledge);

        if (jumping()) {
            setStepHeight(VANILLA);
            if (climb && ledge <= Math.min(height.get(), jumpReach()) && mc.player.isOnGround() && mc.player.horizontalCollision) mc.player.jump();
            return;
        }

        steppingUp = climb && ledge <= height.get();
        setStepHeight(steppingUp ? height.get().floatValue() : VANILLA);
    }

    private void trackStep() {
        double y = mc.player.getY();
        double rise = y - lastY;
        boolean onGround = mc.player.isOnGround();

        if (steppingUp && wasOnGround && onGround && rise > VANILLA && rise <= height.get() + 0.1) {
            steppedAt = Util.getMeasuringTimeMs();
            if (smoothCamera.get()) smoothing = true;
        }

        lastY = y;
        wasOnGround = onGround;
    }

    private double jumpReach() {
        var boost = mc.player.getStatusEffect(StatusEffects.JUMP_BOOST);
        return JUMP_REACH + (boost == null ? 0 : (boost.getAmplifier() + 1) * 0.5);
    }

    private boolean allowed() {
        if (mc.player.getAbilities().flying || mc.player.isGliding() || mc.player.hasVehicle()) return false;
        if (mc.player.isTouchingWater() || mc.player.isInLava() || mc.player.isClimbing()) return false;
        return !pauseSneak.get() || !mc.player.isSneaking();
    }

    private double ledgeAhead() {
        Vec2f input = mc.player.input.getMovementInput();
        if (input.lengthSquared() < 0.01f) return -1;

        float yaw = mc.player.getYaw() * MathHelper.RADIANS_PER_DEGREE;
        float sin = MathHelper.sin(yaw);
        float cos = MathHelper.cos(yaw);
        double x = input.x * cos - input.y * sin;
        double z = input.y * cos + input.x * sin;
        double length = Math.sqrt(x * x + z * z);
        x = x / length * PROBE;
        z = z / length * PROBE;

        Box box = mc.player.getBoundingBox();
        if (mc.world.isSpaceEmpty(mc.player, box.offset(x, 0, z))) return -1;

        for (double h = SCAN_STEP; h <= SCAN_LIMIT; h += SCAN_STEP) {
            if (!mc.world.isSpaceEmpty(mc.player, box.offset(0, h, 0))) return -1;
            if (mc.world.isSpaceEmpty(mc.player, box.offset(x, h, z))) return h;
        }
        return -1;
    }

    private boolean blocked(double ledge) {
        if (holeGuard.get() && inHole() && enemyClose()) return true;
        return safe.get() && lethalAt(ledge);
    }

    private boolean inHole() {
        BlockPos feet = mc.player.getBlockPos();
        for (Direction direction : Direction.Type.HORIZONTAL) {
            BlockPos side = feet.offset(direction);
            if (mc.world.getBlockState(side).getCollisionShape(mc.world, side).isEmpty()) return false;
        }
        return true;
    }

    private boolean enemyClose() {
        for (PlayerEntity player : mc.world.getPlayers()) {
            if (player == mc.player || !player.isAlive() || Friends.get().isFriend(player)) continue;
            if (mc.player.squaredDistanceTo(player) <= GUARD_RANGE * GUARD_RANGE) return true;
        }
        return false;
    }

    private boolean lethalAt(double ledge) {
        float health = mc.player.getHealth() + mc.player.getAbsorptionAmount();
        Vec3d here = mc.player.getEntityPos();
        Vec3d up = here.add(0, ledge, 0);
        Box box = mc.player.getBoundingBox();
        Box upBox = box.offset(0, ledge, 0);

        for (Entity entity : mc.world.getEntities()) {
            if (!(entity instanceof EndCrystalEntity) || !entity.isAlive()) continue;
            if (entity.squaredDistanceTo(up) > ExplosionDamage.CRYSTAL_RANGE * ExplosionDamage.CRYSTAL_RANGE) continue;

            Vec3d crystal = entity.getEntityPos();
            float above = ExplosionDamage.crystal(mc.player, up, upBox, crystal, mc.world);
            if (above >= health && above > ExplosionDamage.crystal(mc.player, here, box, crystal, mc.world)) return true;
        }
        return false;
    }

    private void setStepHeight(float value) {
        if (mc.player == null) return;
        EntityAttributeInstance attribute = mc.player.getAttributeInstance(EntityAttributes.STEP_HEIGHT);
        if (attribute != null && attribute.getBaseValue() != value) attribute.setBaseValue(value);
    }
}
