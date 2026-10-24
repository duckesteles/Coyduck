package dev.coyduck.modules;

import dev.coyduck.Coyduck;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;

public class AirMine extends Module {
    private static final double SAFE_FALL = 2.5;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> falling = sgGeneral.add(new BoolSetting.Builder()
        .name("falling")
        .description("Also mines at full speed while falling or jumping, as long as the fall is too short to hurt you. Gliding with an elytra always counts.")
        .defaultValue(true)
        .build()
    );

    private boolean restore;

    public AirMine() {
        super(Coyduck.CATEGORY, "air-mine", "Mines at ground speed while you fly or fall, by telling the server you are on the ground only for the moment a block breaks.");
    }

    public static boolean spoofing() {
        AirMine module = Modules.get().get(AirMine.class);
        return module != null && module.isActive() && module.applies();
    }

    @Override
    public void onDeactivate() {
        restore = false;
    }

    private boolean applies() {
        if (mc.player == null || mc.player.isOnGround() || mc.player.hasVehicle() || mc.player.getAbilities().flying) return false;
        if (mc.player.isGliding()) return true;
        return falling.get() && mc.player.fallDistance <= SAFE_FALL && !mc.player.isTouchingWater();
    }

    @EventHandler
    private void onSend(PacketEvent.Send event) {
        if (!(event.packet instanceof PlayerActionC2SPacket packet) || !breaking(packet) || !applies()) return;

        mc.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.OnGroundOnly(true, mc.player.horizontalCollision));
        restore = true;
    }

    @EventHandler
    private void onSent(PacketEvent.Sent event) {
        if (!restore || !(event.packet instanceof PlayerActionC2SPacket packet) || !breaking(packet)) return;

        restore = false;
        if (mc.getNetworkHandler() != null && mc.player != null) {
            mc.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.OnGroundOnly(false, mc.player.horizontalCollision));
        }
    }

    private static boolean breaking(PlayerActionC2SPacket packet) {
        return packet.getAction() == PlayerActionC2SPacket.Action.START_DESTROY_BLOCK || packet.getAction() == PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK;
    }
}
