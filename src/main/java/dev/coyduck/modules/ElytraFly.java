package dev.coyduck.modules;

import dev.coyduck.Coyduck;
import dev.coyduck.managers.RotationManager;
import meteordevelopment.meteorclient.events.entity.player.PlayerMoveEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.mixininterface.IVec3d;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.MovementType;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.util.PlayerInput;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

public class ElytraFly extends Module {

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgSpeed = settings.createGroup("Speed");

    private final Setting<Boolean> fluids = sgGeneral.add(new BoolSetting.Builder()
        .name("fluids")
        .description("Also forces the flight controls in water and lava instead of swimming normally. Experimental, the server may pull you back.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> wallGuard = sgGeneral.add(new BoolSetting.Builder()
        .name("wall-guard")
        .description("Shortens a move that would slam you into a wall, so you stop right in front of it instead of taking elytra crash damage.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> horizontal = sgSpeed.add(new DoubleSetting.Builder()
        .name("horizontal-speed")
        .description("Blocks moved horizontally per tick.")
        .defaultValue(2.9)
        .min(0)
        .sliderRange(0, 5)
        .build()
    );

    private final Setting<Double> up = sgSpeed.add(new DoubleSetting.Builder()
        .name("up-speed")
        .description("Blocks moved up per tick while holding jump.")
        .defaultValue(2.4)
        .min(0)
        .sliderRange(0, 5)
        .build()
    );

    private final Setting<Double> down = sgSpeed.add(new DoubleSetting.Builder()
        .name("down-speed")
        .description("Blocks moved down per tick while holding sneak.")
        .defaultValue(2.4)
        .min(0)
        .sliderRange(0, 5)
        .build()
    );

    private static final int TAKEOFF_TRIES = 5;
    private static final int TAKEOFF_WINDOW = 40;

    private static Vec3d pilot;

    private boolean fluidFlight;
    private int takeoffTries;
    private int airTicks;
    private boolean wasGliding;

    public ElytraFly() {
        super(Coyduck.CATEGORY, "elytra-fly+", "Flies with an elytra at a set speed in any direction and picks the flight back up after water or lava.");
    }

    @Override
    public void onActivate() {
        Module meteor = Modules.get().get(meteordevelopment.meteorclient.systems.modules.movement.elytrafly.ElytraFly.class);
        if (meteor != null && meteor.isActive()) {
            meteor.toggle();
            info("Disabled %s, it controls the same flight.", meteor.title);
        }

        fluidFlight = false;
        wasGliding = false;
        takeoffTries = 0;
        airTicks = 0;
    }

    /** Overrides the keyboard for the next move with a world-space movement per tick, null to give it back. */
    public static void pilot(Vec3d movement) {
        pilot = movement;
    }

    public double horizontalSpeed() {
        return horizontal.get();
    }

    public double upSpeed() {
        return up.get();
    }

    public double downSpeed() {
        return down.get();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;

        if (inFluid()) {
            if (wasGliding || fluidFlight) {
                fluidFlight = true;
                takeoffTries = TAKEOFF_TRIES;
            }
            airTicks = 0;
            return;
        }
        if (mc.player.isOnGround()) {
            fluidFlight = false;
            wasGliding = false;
            return;
        }

        wasGliding = mc.player.isGliding();
        if (!fluidFlight) return;
        if (++airTicks > TAKEOFF_WINDOW || takeoffTries <= 0) {
            fluidFlight = false;
            return;
        }
        if (wasGliding) return;

        takeoffTries--;
        if (canTakeoff() && mc.player.checkGliding()) {
            mc.getNetworkHandler().sendPacket(new ClientCommandC2SPacket(mc.player, ClientCommandC2SPacket.Mode.START_FALL_FLYING));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    private void onMove(PlayerMoveEvent event) {
        if (event.type != MovementType.SELF || mc.player == null || !hasElytra()) return;

        boolean fluid = fluids.get() && inFluid();
        if (!fluid && !mc.player.isGliding()) return;
        if (fluid) {
            if (!mc.player.isOnGround()) fluidFlight = true;
            mc.player.setSprinting(false);
            mc.player.setSwimming(false);
        }

        PlayerInput input = mc.player.input.playerInput;
        double forward = (input.forward() ? 1 : 0) - (input.backward() ? 1 : 0);
        double strafe = (input.left() ? 1 : 0) - (input.right() ? 1 : 0);

        double x = 0;
        double z = 0;
        if (forward != 0 || strafe != 0) {
            float base = RotationManager.get().physicsYaw(mc.player, mc.player.getYaw());
            double angle = Math.toRadians(base) + Math.atan2(-strafe, forward);
            x = -Math.sin(angle) * horizontal.get();
            z = Math.cos(angle) * horizontal.get();
        }

        double y = 0;
        if (input.jump() && !input.sneak()) y = up.get();
        else if (input.sneak() && !input.jump()) y = -down.get();

        if (pilot != null && Modules.get().isActive(ElytraPilot.class)) {
            x = pilot.x;
            y = pilot.y;
            z = pilot.z;
        }

        if (wallGuard.get() && (x != 0 || z != 0)) {
            x *= freeScale(x, 0, y);
            z *= freeScale(0, z, y);
            double both = freeScale(x, z, y);
            x *= both;
            z *= both;
        }

        ((IVec3d) event.movement).meteor$set(x, y, z);
        mc.player.setVelocity(0, 0, 0);
    }

    private double freeScale(double x, double z, double y) {
        if (x == 0 && z == 0) return 1;
        Box box = mc.player.getBoundingBox().offset(0, y, 0);
        if (!mc.world.isSpaceEmpty(mc.player, box)) box = mc.player.getBoundingBox();
        if (mc.world.isSpaceEmpty(mc.player, box.offset(x, 0, z))) return 1;

        double low = 0;
        double high = 1;
        for (int i = 0; i < 6; i++) {
            double mid = (low + high) / 2;
            if (mc.world.isSpaceEmpty(mc.player, box.offset(x * mid, 0, z * mid))) low = mid;
            else high = mid;
        }
        return low;
    }

    private boolean canTakeoff() {
        return hasElytra() && !mc.player.hasVehicle() && !mc.player.getAbilities().flying;
    }

    private boolean hasElytra() {
        return LivingEntity.canGlideWith(mc.player.getEquippedStack(EquipmentSlot.CHEST), EquipmentSlot.CHEST);
    }

    private boolean inFluid() {
        return mc.player.isTouchingWater() || mc.player.isInLava();
    }
}
