package dev.coyduck;

import dev.coyduck.core.Bus;
import dev.coyduck.events.BlockUpdateEvent;
import dev.coyduck.events.ChunkDataEvent;
import dev.coyduck.events.ParticleEvent;
import dev.coyduck.events.ReceiveMessageEvent;
import dev.coyduck.events.StartBreakingBlockEvent;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;

public final class MeteorEvents {
    public static final MeteorEvents BRIDGE = new MeteorEvents();

    private MeteorEvents() {}

    @EventHandler(priority = EventPriority.HIGHEST)
    private void onMessage(meteordevelopment.meteorclient.events.game.ReceiveMessageEvent event) {
        ReceiveMessageEvent ours = Bus.post(ReceiveMessageEvent.get(event.getMessage()));
        if (ours.isModified()) event.setMessage(ours.getMessage());
        if (ours.isCancelled()) event.cancel();
    }

    @EventHandler
    private void onChunk(meteordevelopment.meteorclient.events.world.ChunkDataEvent event) {
        Bus.post(new ChunkDataEvent(event.chunk()));
    }

    @EventHandler
    private void onBlock(meteordevelopment.meteorclient.events.world.BlockUpdateEvent event) {
        Bus.post(BlockUpdateEvent.get(event.pos, event.oldState, event.newState));
    }

    @EventHandler
    private void onParticle(meteordevelopment.meteorclient.events.world.ParticleEvent event) {
        if (Bus.post(ParticleEvent.get(event.particle)).isCancelled()) event.cancel();
    }

    @EventHandler
    private void onStartBreaking(meteordevelopment.meteorclient.events.entity.player.StartBreakingBlockEvent event) {
        if (Bus.post(StartBreakingBlockEvent.get(event.blockPos, event.direction)).isCancelled()) event.cancel();
    }
}
