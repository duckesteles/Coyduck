package dev.coyduck.modules;

import dev.coyduck.Coyduck;
import dev.coyduck.managers.InteractionManager;
import dev.coyduck.managers.InventoryManager;
import dev.coyduck.managers.RotationManager;
import dev.coyduck.utils.Targets;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.util.Util;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

public class MaceStrike extends Module {
    private static final int PRIORITY = 85;
    private static final float ROTATE_SPEED = 180.0f;
    private static final double MIN_DROP = 2.0;
    private static final double HEIGHT_STEP = 0.5;
    private static final double AIM_INSET = 0.05;
    private static final double STANDING_EYE = 1.62;
    private static final double GLIDING_EYE = 0.4;
    private static final double STRIKE_GAP = 0.25;
    private static final double OVER_RANGE = 0.6;
    private static final double LEAD_TICKS = 2;

    private enum Phase {
        Idle,
        Lift,
        Ground,
        Dive
    }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgAir = settings.createGroup("Elytra");

    private final Setting<Double> range = sgGeneral.add(new DoubleSetting.Builder()
        .name("range")
        .description("Reach from your eyes to the closest point of the target's hitbox at the moment of the hit.")
        .defaultValue(3.0)
        .range(1, 6)
        .sliderRange(1, 6)
        .build()
    );

    private final Setting<Double> height = sgGeneral.add(new DoubleSetting.Builder()
        .name("height")
        .description("On foot: how far up the server is told you went before the hit. The fall back down is what the mace smash counts. Lowered on its own when blocks are above you.")
        .defaultValue(8.0)
        .range(MIN_DROP, 9.5)
        .sliderRange(MIN_DROP, 9.5)
        .build()
    );

    private final Setting<Double> step = sgGeneral.add(new DoubleSetting.Builder()
        .name("step")
        .description("Longest move in one packet. The trip down and back is cut into packets of at most this length, all sent in the same tick.")
        .defaultValue(2.0)
        .range(0.5, 10)
        .sliderRange(0.5, 5)
        .build()
    );

    private final Setting<Integer> interval = sgGeneral.add(new IntSetting.Builder()
        .name("interval")
        .description("Milliseconds between smashes.")
        .defaultValue(500)
        .range(100, 3000)
        .sliderRange(100, 2000)
        .build()
    );

    private final Setting<InventoryManager.SwitchMode> switchMode = sgGeneral.add(new EnumSetting.Builder<InventoryManager.SwitchMode>()
        .name("switch")
        .description("How the mace gets into your hand for the hit. Your hand goes back right after.")
        .defaultValue(InventoryManager.SwitchMode.Silent)
        .build()
    );

    private final Setting<Boolean> rotate = sgGeneral.add(new BoolSetting.Builder()
        .name("rotate")
        .description("Faces the target before the smash starts.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> follow = sgAir.add(new BoolSetting.Builder()
        .name("follow")
        .description("While you fly with elytra-fly+, steers you over the target and keeps you there, then dives for the hit and comes back up.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> hover = sgAir.add(new DoubleSetting.Builder()
        .name("hover")
        .description("How far above the target's feet you stay while following.")
        .defaultValue(10.0)
        .range(5, 11.5)
        .sliderRange(5, 11.5)
        .visible(follow::get)
        .build()
    );

    private final Setting<Double> followRange = sgAir.add(new DoubleSetting.Builder()
        .name("follow-range")
        .description("Targets this far away are chased.")
        .defaultValue(32)
        .range(5, 128)
        .sliderRange(5, 64)
        .visible(follow::get)
        .build()
    );

    private final Setting<Boolean> stopGlide = sgAir.add(new BoolSetting.Builder()
        .name("stop-glide")
        .description("Tells the server you touched ground one tick before the dive. Stopping the glide got setbacks on 6b6t, so it is off by default.")
        .defaultValue(false)
        .visible(follow::get)
        .build()
    );

    private Phase phase = Phase.Idle;
    private PlayerEntity target;
    private double lift;
    private double strikeY;
    private long lastStrike;
    private boolean sending;

    public MaceStrike() {
        super(Coyduck.CATEGORY, "mace-strike", "Smashes players with a mace from your hotbar or inventory. On foot the server is told you went up and fell back down. While flying with elytra-fly+ it stays over the target and dives for each hit. The trip is sent as short moves inside one tick.");
    }

    @Override
    public void onDeactivate() {
        phase = Phase.Idle;
        target = null;
    }

    @Override
    public String getInfoString() {
        return target == null ? null : target.getName().getString();
    }

    @EventHandler(priority = EventPriority.LOW)
    private void onTickPre(TickEvent.Pre event) {
        if (phase != Phase.Idle) return;
        if (!canAct() || maceSlot() == -1) {
            target = null;
            return;
        }

        if (flying()) airTick();
        else groundTick();
    }

    private void groundTick() {
        if (mc.player.isGliding()) {
            target = null;
            return;
        }

        target = choose(range.get(), true);
        if (target == null) return;

        Box box = Targets.box(target);
        if (rotate.get()) {
            RotationManager rotations = RotationManager.get();
            rotations.request(PRIORITY, aim(box, mc.player.getEyePos()), ROTATE_SPEED, true);
            if (!rotations.isLookingAt(box, range.get())) return;
        }
        if (!due()) return;

        lift = headroom();
        if (lift < MIN_DROP) return;
        phase = Phase.Lift;
    }

    private void airTick() {
        target = choose(followRange.get(), false);
        if (target == null) return;

        Vec3d goal = target.getEntityPos().add(target.getVelocity().multiply(LEAD_TICKS)).add(0, hover.get(), 0);
        steer(goal);

        double dx = target.getX() - mc.player.getX();
        double dz = target.getZ() - mc.player.getZ();
        if (dx * dx + dz * dz > OVER_RANGE * OVER_RANGE) return;

        double y = strikeHeight();
        if (Double.isNaN(y)) return;

        Box box = Targets.box(target);
        Vec3d eye = new Vec3d(mc.player.getX(), y + STANDING_EYE, mc.player.getZ());
        float[] rotation = rotationTo(eye, aim(box, eye));
        RotationManager.get().request(PRIORITY, rotation[0], rotation[1], ROTATE_SPEED, false);
        if (!looksAt(eye, box) || !due()) return;

        strikeY = y;
        phase = stopGlide.get() ? Phase.Ground : Phase.Dive;
    }

    private void steer(Vec3d goal) {
        ElytraFly fly = Modules.get().get(ElytraFly.class);
        Vec3d delta = goal.subtract(mc.player.getEntityPos());

        double horizontal = Math.sqrt(delta.x * delta.x + delta.z * delta.z);
        double speed = Math.min(horizontal, fly.horizontalSpeed());
        double x = horizontal < 1.0E-4 ? 0 : delta.x / horizontal * speed;
        double z = horizontal < 1.0E-4 ? 0 : delta.z / horizontal * speed;
        double y = MathHelper.clamp(delta.y, -fly.downSpeed(), fly.upSpeed());
        ElytraFly.pilot(new Vec3d(x, y, z));
    }

    private double strikeHeight() {
        Box box = mc.player.getBoundingBox();
        double top = mc.player.getY() - MIN_DROP;
        double reach = range.get();
        Box victim = Targets.box(target);

        for (double y = victim.maxY + STRIKE_GAP; y <= top; y += HEIGHT_STEP) {
            Box path = box.stretch(0, y - mc.player.getY(), 0);
            if (!mc.world.isSpaceEmpty(mc.player, path)) continue;

            Vec3d standing = new Vec3d(mc.player.getX(), y + STANDING_EYE, mc.player.getZ());
            Vec3d gliding = new Vec3d(mc.player.getX(), y + GLIDING_EYE, mc.player.getZ());
            if (victim.squaredMagnitude(standing) > reach * reach || victim.squaredMagnitude(gliding) > reach * reach) return Double.NaN;
            return y;
        }
        return Double.NaN;
    }

    private boolean looksAt(Vec3d eye, Box box) {
        if (!rotate.get()) return true;
        RotationManager rotations = RotationManager.get();
        Vec3d end = eye.add(Vec3d.fromPolar(rotations.serverPitch(), rotations.serverYaw()).multiply(range.get() + 1));
        return box.raycast(eye, end).isPresent();
    }

    private static float[] rotationTo(Vec3d eye, Vec3d point) {
        double dx = point.x - eye.x;
        double dy = point.y - eye.y;
        double dz = point.z - eye.z;
        float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90.0f;
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        return new float[] {yaw, pitch};
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    private void onSend(PacketEvent.Send event) {
        if (!sending && phase != Phase.Idle && event.packet instanceof PlayerMoveC2SPacket) event.cancel();
    }

    @EventHandler(priority = EventPriority.LOW)
    private void onTickPost(TickEvent.Post event) {
        if (phase == Phase.Idle || mc.player == null || mc.getNetworkHandler() == null) {
            phase = Phase.Idle;
            return;
        }

        double x = mc.player.getX();
        double y = mc.player.getY();
        double z = mc.player.getZ();
        boolean collision = mc.player.horizontalCollision;

        switch (phase) {
            case Lift -> {
                phase = Phase.Idle;
                if (!ready()) return;
                path(x, y, y + lift, z, collision);
                path(x, y + lift, y, z, collision);
                strike();
            }
            case Ground -> {
                move(new PlayerMoveC2SPacket.PositionAndOnGround(x, y, z, true, collision));
                phase = Phase.Dive;
            }
            case Dive -> {
                phase = Phase.Idle;
                if (!ready()) {
                    move(new PlayerMoveC2SPacket.PositionAndOnGround(x, y, z, false, collision));
                    return;
                }
                path(x, y, strikeY, z, collision);
                strike();
                path(x, strikeY, y, z, collision);
                if (stopGlide.get() && mc.player.isGliding()) {
                    mc.getNetworkHandler().sendPacket(new ClientCommandC2SPacket(mc.player, ClientCommandC2SPacket.Mode.START_FALL_FLYING));
                }
            }
            default -> phase = Phase.Idle;
        }
    }

    private void path(double x, double from, double to, double z, boolean collision) {
        int steps = Math.max(1, (int) Math.ceil(Math.abs(to - from) / step.get()));
        for (int i = 1; i <= steps; i++) {
            move(new PlayerMoveC2SPacket.PositionAndOnGround(x, from + (to - from) * i / steps, z, false, collision));
        }
    }

    private boolean ready() {
        return maceSlot() != -1 && target != null && target.isAlive() && !target.isRemoved();
    }

    private void strike() {
        PlayerEntity victim = target;
        InventoryManager.get().withItem(maceSlot(), switchMode.get(), () -> {
            mc.interactionManager.attackEntity(mc.player, victim);
            InteractionManager.get().swing(InteractionManager.SwingMode.Packet);
        });
        lastStrike = Util.getMeasuringTimeMs();
    }

    private void move(PlayerMoveC2SPacket packet) {
        sending = true;
        try {
            mc.getNetworkHandler().sendPacket(packet);
        } finally {
            sending = false;
        }
    }

    private boolean due() {
        return Util.getMeasuringTimeMs() - lastStrike >= interval.get();
    }

    private boolean flying() {
        return follow.get() && mc.player.isGliding() && Modules.get().isActive(ElytraFly.class);
    }

    private boolean canAct() {
        if (mc.player == null || mc.world == null || mc.interactionManager == null || !mc.player.isAlive()) return false;
        if (mc.player.hasVehicle() || mc.player.isSpectator()) return false;
        if (mc.player.isTouchingWater() || mc.player.isInLava() || mc.player.isClimbing()) return false;
        return !InventoryManager.get().isEating() && !mc.player.isUsingItem();
    }

    private int maceSlot() {
        return InventoryManager.get().find(stack -> stack.isOf(Items.MACE), 0, InventoryManager.MAIN_END);
    }

    private PlayerEntity choose(double distance, boolean reachOnly) {
        PlayerEntity best = null;
        double bestScore = Double.MAX_VALUE;
        for (PlayerEntity player : mc.world.getPlayers()) {
            if (player == mc.player || !Targets.valid(player, distance + 3)) continue;
            if (reachOnly && !inReach(player)) continue;
            if (!reachOnly && player.squaredDistanceTo(mc.player) > distance * distance) continue;

            double score = player == target ? -1 : player.getHealth() + player.getAbsorptionAmount();
            if (score < bestScore) {
                best = player;
                bestScore = score;
            }
        }
        return best;
    }

    private boolean inReach(PlayerEntity player) {
        double reach = range.get();
        return Targets.box(player).squaredMagnitude(mc.player.getEyePos()) <= reach * reach;
    }

    private double headroom() {
        Box box = mc.player.getBoundingBox();
        for (double h = height.get(); h >= MIN_DROP; h -= HEIGHT_STEP) {
            if (mc.world.isSpaceEmpty(mc.player, box.stretch(0, h, 0))) return h;
        }
        return 0;
    }

    private static Vec3d aim(Box box, Vec3d eye) {
        if (box.contains(eye)) return box.getCenter();
        Box inner = box.contract(AIM_INSET);
        return new Vec3d(
            MathHelper.clamp(eye.x, inner.minX, inner.maxX),
            MathHelper.clamp(eye.y, inner.minY, inner.maxY),
            MathHelper.clamp(eye.z, inner.minZ, inner.maxZ)
        );
    }
}
