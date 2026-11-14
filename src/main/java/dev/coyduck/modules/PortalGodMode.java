package dev.coyduck.modules;

import dev.coyduck.Coyduck;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.packet.c2s.play.TeleportConfirmC2SPacket;

import java.util.ArrayList;
import java.util.List;

public class PortalGodMode extends Module {
    private final List<TeleportConfirmC2SPacket> held = new ArrayList<>();
    private boolean releasing;

    public PortalGodMode() {
        super(Coyduck.CATEGORY, "portal-god-mode", "Holds back the teleport confirmation after going through a portal, so the server keeps you unhittable on the other side. You can't move until you turn it off. Turn it on right before the portal.");
    }

    @Override
    public void onActivate() {
        held.clear();
    }

    @Override
    public void onDeactivate() {
        if (mc.getNetworkHandler() != null) {
            releasing = true;
            for (TeleportConfirmC2SPacket packet : held) mc.getNetworkHandler().sendPacket(packet);
            releasing = false;
        }
        held.clear();
    }

    @Override
    public String getInfoString() {
        return held.isEmpty() ? null : held.size() + " held";
    }

    @EventHandler
    private void onSend(PacketEvent.Send event) {
        if (releasing || !(event.packet instanceof TeleportConfirmC2SPacket packet)) return;
        held.add(packet);
        event.cancel();
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        held.clear();
    }
}
