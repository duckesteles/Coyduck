package dev.coyduck.modules;

import dev.coyduck.Coyduck;
import dev.coyduck.managers.InteractionManager;
import dev.coyduck.managers.InventoryManager;
import dev.coyduck.managers.RotationManager;
import dev.coyduck.utils.Targets;
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
import net.minecraft.util.Util;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

public class MaceStrike extends Module {
    private static final int PRIORITY = 85;
    private static final float ROTATE_SPEED = 180.0f;
    private static final double AIM_INSET = 0.05;
    private static final double SMASH_FALL = 1.5;
    private static final double ALIGN = 0.5;
    private static final double HOVER_SLACK = 1.0;
    private static final double LEAD_TICKS = 2;
    private static final int MAX_FALL_TICKS = 30;

    private enum Phase {
        Follow,
        Fall
    }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Double> range = sgGeneral.add(new DoubleSetting.Builder()
        .name("range")
        .description("Reach from your eyes to the closest point of the target's hitbox for the hit.")
        .defaultValue(3.0)
        .range(1, 6)
        .sliderRange(1, 6)
        .build()
    );

    private final Setting<Double> hover = sgGeneral.add(new DoubleSetting.Builder()
        .name("hover")
        .description("How far above the target's feet you wait before dropping. Higher falls hit harder but take longer.")
        .defaultValue(8.0)
        .range(4, 16)
        .sliderRange(4, 16)
        .build()
    );

    private final Setting<Double> followRange = sgGeneral.add(new DoubleSetting.Builder()
        .name("follow-range")
        .description("Targets this far away are chased.")
        .defaultValue(32)
        .range(5, 128)
        .sliderRange(5, 64)
        .build()
    );

    private final Setting<Integer> interval = sgGeneral.add(new IntSetting.Builder()
        .name("interval")
        .description("Least milliseconds between two drops.")
        .defaultValue(300)
        .range(0, 3000)
        .sliderRange(0, 2000)
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
        .description("Faces the target while falling onto it.")
        .defaultValue(true)
        .build()
    );

    private Phase phase = Phase.Follow;
    private PlayerEntity target;
    private int fallTicks;
    private long lastDrop;

    public MaceStrike() {
        super(Coyduck.CATEGORY, "mace-strike", "While you fly with elytra-fly+, stays over the target, stops gliding, falls onto it and smashes with a mace from your hotbar or inventory, then glides back up. Real falls only, no fake positions.");
    }

    @Override
    public void onActivate() {
        phase = Phase.Follow;
        target = null;
    }

    @Override
    public void onDeactivate() {
        if (phase == Phase.Fall) regain();
        phase = Phase.Follow;
        target = null;
    }

    @Override
    public String getInfoString() {
        return target == null ? null : target.getName().getString();
    }

    @EventHandler(priority = EventPriority.LOW)
    private void onTick(TickEvent.Pre event) {
        if (!canAct()) {
            phase = Phase.Follow;
            target = null;
            return;
        }

        if (phase == Phase.Fall) fall();
        else follow();
    }

    private void follow() {
        if (!mc.player.isGliding() || !Modules.get().isActive(ElytraFly.class) || maceSlot() == -1) {
            target = null;
            return;
        }

        target = choose();
        if (target == null) return;

        Vec3d goal = target.getEntityPos().add(target.getVelocity().multiply(LEAD_TICKS)).add(0, hover.get(), 0);
        steer(goal);

        double dx = target.getX() - mc.player.getX();
        double dz = target.getZ() - mc.player.getZ();
        double height = mc.player.getY() - target.getY();
        if (dx * dx + dz * dz > ALIGN * ALIGN || Math.abs(height - hover.get()) > HOVER_SLACK) return;
        if (Util.getMeasuringTimeMs() - lastDrop < interval.get() || !clearBelow()) return;

        mc.getNetworkHandler().sendPacket(new ClientCommandC2SPacket(mc.player, ClientCommandC2SPacket.Mode.START_FALL_FLYING));
        mc.player.stopGliding();
        lastDrop = Util.getMeasuringTimeMs();
        fallTicks = 0;
        phase = Phase.Fall;
    }

    private void fall() {
        fallTicks++;
        if (mc.player.isOnGround() || mc.player.isGliding() || fallTicks > MAX_FALL_TICKS || target == null || !target.isAlive() || target.isRemoved()) {
            regain();
            return;
        }

        Box box = Targets.box(target);
        Vec3d eye = mc.player.getEyePos();
        if (rotate.get()) RotationManager.get().request(PRIORITY, aim(box, eye), ROTATE_SPEED, false);
        if (mc.player.fallDistance <= SMASH_FALL) return;
        if (box.squaredMagnitude(eye) > range.get() * range.get()) return;
        if (rotate.get() && !RotationManager.get().isLookingAt(box, range.get())) return;

        int slot = maceSlot();
        if (slot == -1) {
            regain();
            return;
        }

        PlayerEntity victim = target;
        InventoryManager.get().withItem(slot, switchMode.get(), () -> {
            mc.interactionManager.attackEntity(mc.player, victim);
            InteractionManager.get().swing(InteractionManager.SwingMode.Packet);
        });
        regain();
    }

    private void regain() {
        phase = Phase.Follow;
        if (mc.player == null || mc.player.isOnGround() || mc.player.isGliding()) return;
        if (mc.player.checkGliding()) {
            mc.getNetworkHandler().sendPacket(new ClientCommandC2SPacket(mc.player, ClientCommandC2SPacket.Mode.START_FALL_FLYING));
        }
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

    private boolean clearBelow() {
        Box victim = Targets.box(target);
        double drop = mc.player.getY() - victim.maxY;
        if (drop <= 0) return false;
        return mc.world.isSpaceEmpty(mc.player, mc.player.getBoundingBox().stretch(0, -drop, 0));
    }

    private boolean canAct() {
        if (mc.player == null || mc.world == null || mc.interactionManager == null || mc.getNetworkHandler() == null || !mc.player.isAlive()) return false;
        if (mc.player.hasVehicle() || mc.player.isSpectator()) return false;
        if (mc.player.isTouchingWater() || mc.player.isInLava()) return false;
        return !InventoryManager.get().isEating() && !mc.player.isUsingItem();
    }

    private int maceSlot() {
        return InventoryManager.get().find(stack -> stack.isOf(Items.MACE), 0, InventoryManager.MAIN_END);
    }

    private PlayerEntity choose() {
        PlayerEntity best = null;
        double bestScore = Double.MAX_VALUE;
        double distance = followRange.get();
        for (PlayerEntity player : mc.world.getPlayers()) {
            if (player == mc.player || !Targets.valid(player, distance + 3)) continue;
            if (player.squaredDistanceTo(mc.player) > distance * distance) continue;

            double score = player == target ? -1 : player.getHealth() + player.getAbsorptionAmount();
            if (score < bestScore) {
                best = player;
                bestScore = score;
            }
        }
        return best;
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
