package dev.coyduck.events;

import net.minecraft.world.chunk.WorldChunk;

public record ChunkDataEvent(WorldChunk chunk) {}
