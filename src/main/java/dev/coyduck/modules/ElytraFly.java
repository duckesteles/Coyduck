package dev.coyduck.modules;

import dev.coyduck.Coyduck;
import dev.coyduck.events.PacketApplyEvent;
import dev.coyduck.managers.LatencyManager;
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
import net.minecraft.network.packet.s2c.play.EntityTrackerUpdateS2CPacket;
import net.minecraft.util.Util;
import net.minecraft.util.PlayerInput;

public class ElytraFly extends Module {

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgSpeed = settings.createGroup("Speed");

    private final Setting<Boolean> fluids = sgGeneral.add(new BoolSetting.Builder()
        .name("fluids")
        .description("Also forces the flight controls in water and lava instead of swimming normally. Experimental, the server may pull you back.")
        .defaultValue(false)
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

    private static final int FLUID_GRACE = 40;
    private static final int FLAGS = 0;

    private boolean fluidFlight;
    private boolean wasGliding;
    private int ticks;
    private int fluidSince;
    private long takeoffUntil;

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
        takeoffUntil = 0;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        ticks++;
        if (mc.player == null) return;

        if (takeoffUntil != 0 && Util.getMeasuringTimeMs() > takeoffUntil) {
            takeoffUntil = 0;
            if (mc.player.isGliding()) mc.player.stopGliding();
        }

        if (mc.player.isOnGround()) {
            fluidFlight = false;
            wasGliding = false;
            return;
        }
        if (inFluid()) {
            if (wasGliding && !fluidFlight) {
                fluidFlight = true;
                fluidSince = ticks;
                wasGliding = false;
            }
            if (fluidFlight && ticks - fluidSince > FLUID_GRACE) fluidFlight = false;
            return;
        }

        wasGliding = mc.player.isGliding();
        if (wasGliding || !fluidFlight) return;

        fluidFlight = false;
        if (canTakeoff() && mc.player.checkGliding()) {
            mc.getNetworkHandler().sendPacket(new ClientCommandC2SPacket(mc.player, ClientCommandC2SPacket.Mode.START_FALL_FLYING));
            takeoffUntil = Util.getMeasuringTimeMs() + LatencyManager.get().timeout();
        }
    }

    @EventHandler
    private void onPacket(PacketApplyEvent.Post event) {
        if (takeoffUntil == 0 || mc.player == null) return;
        if (event.packet instanceof EntityTrackerUpdateS2CPacket packet && packet.id() == mc.player.getId()
            && packet.trackedValues().stream().anyMatch(entry -> entry.id() == FLAGS)) takeoffUntil = 0;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    private void onMove(PlayerMoveEvent event) {
        if (event.type != MovementType.SELF || mc.player == null || !hasElytra()) return;

        if (mc.player.isOnGround() || takeoffUntil != 0) return;

        boolean fluid = inFluid();
        if (fluid ? !fluids.get() : !mc.player.isGliding()) return;
        if (fluid) {
            fluidFlight = true;
            fluidSince = ticks;
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

        ((IVec3d) event.movement).meteor$set(x, y, z);
        mc.player.setVelocity(0, 0, 0);
    }

    private boolean canTakeoff() {
        return hasElytra() && !mc.player.hasVehicle() && !mc.player.getAbilities().flying && !mc.player.isClimbing();
    }

    private boolean hasElytra() {
        return LivingEntity.canGlideWith(mc.player.getEquippedStack(EquipmentSlot.CHEST), EquipmentSlot.CHEST);
    }

    private boolean inFluid() {
        return mc.player.isTouchingWater() || mc.player.isInLava();
    }
}
