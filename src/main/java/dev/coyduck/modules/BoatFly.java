package dev.coyduck.modules;

import dev.coyduck.Coyduck;
import meteordevelopment.meteorclient.events.entity.EntityMoveEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.mixininterface.IVec3d;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.movement.EntityControl;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.Entity;
import net.minecraft.entity.vehicle.AbstractBoatEntity;
import net.minecraft.network.packet.c2s.play.PlayerInputC2SPacket;
import net.minecraft.util.PlayerInput;
import net.minecraft.util.math.Vec3d;

public class BoatFly extends Module {
    private static final double KICK_DROP = 0.04;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Double> speed = sgGeneral.add(new DoubleSetting.Builder()
        .name("speed")
        .description("Horizontal speed in blocks per second, toward where you look.")
        .defaultValue(20)
        .min(0)
        .sliderMax(60)
        .build()
    );

    private final Setting<Double> upSpeed = sgGeneral.add(new DoubleSetting.Builder()
        .name("up-speed")
        .description("Blocks per second up while holding jump.")
        .defaultValue(10)
        .min(0)
        .sliderMax(40)
        .build()
    );

    private final Setting<Double> downSpeed = sgGeneral.add(new DoubleSetting.Builder()
        .name("down-speed")
        .description("Blocks per second straight down while holding sneak.")
        .defaultValue(10)
        .min(0)
        .sliderMax(40)
        .build()
    );

    private final Setting<Double> fallSpeed = sgGeneral.add(new DoubleSetting.Builder()
        .name("fall-speed")
        .description("Blocks per second you sink when holding neither key.")
        .defaultValue(0)
        .min(0)
        .sliderMax(10)
        .build()
    );

    private final Setting<Boolean> sneakDown = sgGeneral.add(new BoolSetting.Builder()
        .name("sneak-down")
        .description("While the boat is in the air, sneak flies down instead of getting you off the boat. On ground or water sneak dismounts as usual; let go and press it again there.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> antiKick = sgGeneral.add(new BoolSetting.Builder()
        .name("anti-kick")
        .description("Dips the boat slightly every few seconds so the server doesn't kick you for floating. Uses the normal movement, no extra packets.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> kickDelay = sgGeneral.add(new IntSetting.Builder()
        .name("kick-delay")
        .description("Ticks between dips.")
        .defaultValue(40)
        .range(5, 75)
        .sliderRange(5, 75)
        .visible(antiKick::get)
        .build()
    );

    private int floatTicks;
    private boolean dipped;

    public BoatFly() {
        super(Coyduck.CATEGORY, "boat-fly+", "Flies a boat toward where you look, with jump up and sneak straight down, without sneak throwing you off the boat in the air.");
    }

    @Override
    public void onActivate() {
        Module meteor = Modules.get().get(EntityControl.class);
        if (meteor != null && meteor.isActive()) {
            meteor.toggle();
            info("Disabled %s, it moves the same boat.", meteor.title);
        }
        floatTicks = 0;
        dipped = false;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        AbstractBoatEntity boat = boat();
        if (boat == null || !airborne(boat)) floatTicks = 0;
        else floatTicks++;
    }

    @EventHandler
    private void onMove(EntityMoveEvent event) {
        AbstractBoatEntity boat = boat();
        if (boat == null || event.entity != boat) return;

        Vec3d horizontal = PlayerUtils.getHorizontalVelocity(speed.get());
        double y = -fallSpeed.get() / 20;
        boolean up = mc.options.jumpKey.isPressed();
        boolean down = mc.options.sneakKey.isPressed();
        if (up && !down) y = upSpeed.get() / 20;
        else if (down && !up) y = -downSpeed.get() / 20;

        if (dipped) {
            y += KICK_DROP;
            dipped = false;
        } else if (antiKick.get() && floatTicks >= kickDelay.get() && y >= 0 && airborne(boat)) {
            y -= KICK_DROP;
            dipped = true;
            floatTicks = 0;
        }

        boat.setYaw(mc.player.getYaw());
        ((IVec3d) event.movement).meteor$set(horizontal.x, y, horizontal.z);
    }

    @EventHandler
    private void onSend(PacketEvent.Send event) {
        if (!sneakDown.get() || !(event.packet instanceof PlayerInputC2SPacket packet) || !packet.input().sneak()) return;

        AbstractBoatEntity boat = boat();
        if (boat == null || !airborne(boat)) return;

        // The server dismounts a rider whose input says sneak, so in the air send the same input without it.
        PlayerInput input = packet.input();
        event.cancel();
        mc.getNetworkHandler().sendPacket(new PlayerInputC2SPacket(new PlayerInput(input.forward(), input.backward(), input.left(), input.right(), input.jump(), false, input.sprint())));
    }

    private AbstractBoatEntity boat() {
        if (mc.player == null) return null;
        Entity vehicle = mc.player.getVehicle();
        return vehicle instanceof AbstractBoatEntity boat && boat.getControllingPassenger() == mc.player ? boat : null;
    }

    private static boolean airborne(AbstractBoatEntity boat) {
        return !boat.isOnGround() && !boat.isTouchingWater();
    }
}
