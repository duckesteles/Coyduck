package dev.coyduck.modules;

import dev.coyduck.Coyduck;
import meteordevelopment.meteorclient.events.entity.player.SendMovementPacketsEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.world.Timer;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;

import java.util.ArrayList;
import java.util.List;

public class StepPlus extends Module {
    private static final double DEFAULT_STEP = 0.6;
    private static final double MIN_PACKET_STEP = 0.6;

    public enum Mode {
        Packet,
        Vanilla
    }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Mode> mode = sgGeneral.add(new EnumSetting.Builder<Mode>()
        .name("mode")
        .description("Packet tells the server you jumped up the blocks with a row of fake positions before the step. Vanilla only raises your step height.")
        .defaultValue(Mode.Packet)
        .build()
    );

    private final Setting<Double> height = sgGeneral.add(new DoubleSetting.Builder()
        .name("height")
        .description("Highest wall you walk up as if it were flat ground.")
        .defaultValue(3.0)
        .range(0.6, 4.0)
        .sliderRange(0.6, 4.0)
        .build()
    );

    private final Setting<Boolean> timer = sgGeneral.add(new BoolSetting.Builder()
        .name("timer")
        .description("Slows the game for the tick of the step so the extra packets don't push your packet rate above normal.")
        .defaultValue(true)
        .visible(() -> mode.get() == Mode.Packet)
        .build()
    );

    private final Setting<Boolean> sneakOff = sgGeneral.add(new BoolSetting.Builder()
        .name("sneak-off")
        .description("Doesn't step while you sneak.")
        .defaultValue(true)
        .build()
    );

    private boolean wasGround;
    private boolean slowed;

    public StepPlus() {
        super(Coyduck.CATEGORY, "step+", "Walks up walls up to a few blocks high as if they were flat. Packet mode sends the server a jump-like row of positions for each step.");
    }

    @Override
    public void onDeactivate() {
        restore();
        resetTimer();
    }

    @EventHandler(priority = EventPriority.HIGH)
    private void onTick(TickEvent.Pre event) {
        resetTimer();
        if (mc.player == null) return;

        EntityAttributeInstance step = mc.player.getAttributeInstance(EntityAttributes.STEP_HEIGHT);
        if (step == null) return;
        step.setBaseValue(allowed() ? height.get() : DEFAULT_STEP);
        wasGround = mc.player.isOnGround();
    }

    @EventHandler(priority = EventPriority.LOWEST)
    private void onSendMovement(SendMovementPacketsEvent.Pre event) {
        if (mode.get() != Mode.Packet || mc.player == null || mc.getNetworkHandler() == null || !wasGround || !allowed()) return;

        double rise = mc.player.getY() - mc.player.lastY;
        if (rise <= MIN_PACKET_STEP || rise > height.get() + 1.0E-3) return;

        List<Double> offsets = offsets(rise);
        for (double offset : offsets) {
            mc.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.PositionAndOnGround(mc.player.lastX, mc.player.lastY + offset, mc.player.lastZ, false, mc.player.horizontalCollision));
        }
        if (timer.get() && !offsets.isEmpty()) {
            Modules.get().get(Timer.class).setOverride(1.0 / (offsets.size() + 1));
            slowed = true;
        }
    }

    private boolean allowed() {
        if (mc.player.isGliding() || mc.player.hasVehicle() || mc.player.getAbilities().flying) return false;
        if (mc.player.isTouchingWater() || mc.player.isInLava() || mc.player.isClimbing()) return false;
        return !(sneakOff.get() && mc.player.isSneaking());
    }

    private void restore() {
        if (mc.player == null) return;
        EntityAttributeInstance step = mc.player.getAttributeInstance(EntityAttributes.STEP_HEIGHT);
        if (step != null) step.setBaseValue(DEFAULT_STEP);
    }

    private void resetTimer() {
        if (!slowed) return;
        Modules.get().get(Timer.class).setOverride(Timer.OFF);
        slowed = false;
    }

    static List<Double> offsets(double rise) {
        double[] known = known(rise);
        List<Double> out = new ArrayList<>();
        if (known != null) {
            for (double offset : known) out.add(offset);
            return out;
        }

        double base = 0;
        while (rise - base > 1.0 + 1.0E-4) {
            out.add(base + 0.42);
            out.add(base + 0.753);
            out.add(base + 1.0);
            base += 1.0;
        }
        double rest = rise - base;
        if (rest > 0.8) {
            out.add(base + 0.42);
            out.add(base + 0.753);
        } else if (rest > MIN_PACKET_STEP) {
            out.add(base + 0.39);
            out.add(base + Math.min(0.7, rest - 0.05));
        }
        return out;
    }

    private static double[] known(double rise) {
        if (near(rise, 1.0) || near(rise, 0.75)) return new double[] {0.42, 0.753};
        if (near(rise, 0.8125) || near(rise, 0.875)) return new double[] {0.39, 0.7};
        if (near(rise, 1.5)) return new double[] {0.42, 0.75, 1.0, 1.16, 1.23, 1.2};
        if (near(rise, 2.0)) return new double[] {0.42, 0.78, 0.63, 0.51, 0.9, 1.21, 1.45, 1.43};
        if (near(rise, 2.5)) return new double[] {0.425, 0.821, 0.699, 0.599, 1.022, 1.372, 1.652, 1.869, 2.019, 1.907};
        return null;
    }

    private static boolean near(double a, double b) {
        return Math.abs(a - b) < 0.01;
    }
}
