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

public class ElytraFly extends Module {

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgSpeed = settings.createGroup("Speed");

    private final Setting<Boolean> fluids = sgGeneral.add(new BoolSetting.Builder()
        .name("fluids")
        .description("Keeps the same control in water and lava instead of swimming, and keeps flying when you come out.")
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

    private boolean fluidFlight;

    public ElytraFly() {
        super(Coyduck.CATEGORY, "elytra-fly+", "Flies with an elytra at a set speed in any direction, through water and lava too.");
    }

    @Override
    public void onActivate() {
        Module meteor = Modules.get().get(meteordevelopment.meteorclient.systems.modules.movement.elytrafly.ElytraFly.class);
        if (meteor != null && meteor.isActive()) {
            meteor.toggle();
            info("Disabled %s, it controls the same flight.", meteor.title);
        }

        fluidFlight = false;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;

        if (mc.player.isOnGround()) {
            fluidFlight = false;
            return;
        }
        if (inFluid() || mc.player.isGliding() || !fluidFlight) return;

        fluidFlight = false;
        if (canTakeoff() && mc.player.checkGliding()) {
            mc.getNetworkHandler().sendPacket(new ClientCommandC2SPacket(mc.player, ClientCommandC2SPacket.Mode.START_FALL_FLYING));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    private void onMove(PlayerMoveEvent event) {
        if (event.type != MovementType.SELF || mc.player == null || !hasElytra()) return;

        boolean fluid = fluids.get() && inFluid();
        if (!fluid && !mc.player.isGliding()) return;
        if (fluid && !mc.player.isOnGround()) fluidFlight = true;

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
        return hasElytra() && !mc.player.hasVehicle() && !mc.player.getAbilities().flying;
    }

    private boolean hasElytra() {
        return LivingEntity.canGlideWith(mc.player.getEquippedStack(EquipmentSlot.CHEST), EquipmentSlot.CHEST);
    }

    private boolean inFluid() {
        return mc.player.isTouchingWater() || mc.player.isInLava();
    }
}
