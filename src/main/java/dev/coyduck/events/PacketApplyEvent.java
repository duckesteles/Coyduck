package dev.coyduck.events;

import meteordevelopment.meteorclient.events.Cancellable;
import net.minecraft.network.packet.Packet;

public final class PacketApplyEvent {
    private PacketApplyEvent() {}

    public static final class Pre extends Cancellable {
        private static final Pre INSTANCE = new Pre();

        public Packet<?> packet;

        public static Pre get(Packet<?> packet) {
            INSTANCE.setCancelled(false);
            INSTANCE.packet = packet;
            return INSTANCE;
        }
    }

    public static final class Post {
        private static final Post INSTANCE = new Post();

        public Packet<?> packet;

        public static Post get(Packet<?> packet) {
            INSTANCE.packet = packet;
            return INSTANCE;
        }
    }
}
