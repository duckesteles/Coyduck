package dev.coyduck.events;

import net.minecraft.network.packet.Packet;

public final class PacketEvent {
    private PacketEvent() {}

    public static final class Receive extends Cancellable {
        public final Packet<?> packet;

        public Receive(Packet<?> packet) {
            this.packet = packet;
        }
    }

    public static final class Send extends Cancellable {
        public final Packet<?> packet;

        public Send(Packet<?> packet) {
            this.packet = packet;
        }
    }

    public static final class Sent {
        public final Packet<?> packet;

        public Sent(Packet<?> packet) {
            this.packet = packet;
        }
    }
}
