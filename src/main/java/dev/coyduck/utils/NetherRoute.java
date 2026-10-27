package dev.coyduck.utils;

import dev.babbaj.pathfinder.NetherPathfinder;
import dev.babbaj.pathfinder.PathSegment;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Nether routes from Baritone's bundled nether-pathfinder. Every native call runs on one worker thread,
 * because the library is not thread safe. Only touch this class after {@link #available()} returned true.
 */
public final class NetherRoute {
    public static final int HEIGHT = 128;
    private static final int TIMEOUT_MS = 3000;

    public record Path(List<BlockPos> points, boolean finished) {}

    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "coyduck-nether-route");
        thread.setDaemon(true);
        return thread;
    });
    private long context;

    public static boolean available() {
        if (!FabricLoader.getInstance().isModLoaded("dev_babbaj_nether-pathfinder")) return false;
        try {
            return NetherPathfinder.isThisSystemSupported();
        } catch (Throwable ignored) {
            return false;
        }
    }

    public void open() {
        worker.execute(() -> {
            if (context == 0) context = NetherPathfinder.newContext(0);
        });
    }

    public void close() {
        worker.execute(() -> {
            if (context == 0) return;
            NetherPathfinder.cancel(context);
            NetherPathfinder.freeContext(context);
            context = 0;
        });
    }

    public void insert(WorldChunk chunk) {
        boolean[] data = pack(chunk);
        int x = chunk.getPos().x;
        int z = chunk.getPos().z;
        worker.execute(() -> {
            if (context != 0) NetherPathfinder.insertChunkData(context, x, z, data);
        });
    }

    public void cull(int chunkX, int chunkZ, int maxDistance) {
        worker.execute(() -> {
            if (context != 0) NetherPathfinder.cullFarChunks(context, chunkX, chunkZ, maxDistance);
        });
    }

    public CompletableFuture<Path> find(BlockPos from, BlockPos to) {
        int x1 = from.getX(), y1 = clampY(from.getY()), z1 = from.getZ();
        int x2 = to.getX(), y2 = clampY(to.getY()), z2 = to.getZ();
        return CompletableFuture.supplyAsync(() -> {
            if (context == 0) return null;
            PathSegment segment = NetherPathfinder.pathFind(context, x1, y1, z1, x2, y2, z2, true, false, TIMEOUT_MS, true);
            if (segment == null) return null;

            List<BlockPos> points = new ArrayList<>(segment.packed.length);
            for (long packed : segment.packed) points.add(unpack(packed));
            return new Path(points, segment.finished);
        }, worker);
    }

    private static int clampY(int y) {
        return Math.clamp(y, 1, HEIGHT - 2);
    }

    // The library packs positions in the pre-1.14 layout: x 26 bits, y 12 bits, z 26 bits.
    private static BlockPos unpack(long packed) {
        return new BlockPos((int) (packed >> 38), (int) ((packed << 26) >> 52), (int) ((packed << 38) >> 38));
    }

    private static boolean[] pack(WorldChunk chunk) {
        boolean[] data = new boolean[16 * 16 * HEIGHT];
        ChunkSection[] sections = chunk.getSectionArray();
        int bottom = chunk.getBottomSectionCoord();

        for (int index = 0; index < sections.length; index++) {
            int base = (bottom + index) << 4;
            if (base < 0 || base >= HEIGHT) continue;

            ChunkSection section = sections[index];
            if (section == null || section.isEmpty()) continue;

            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        if (!section.getBlockState(x, y, z).isAir()) data[(base + y) << 8 | z << 4 | x] = true;
                    }
                }
            }
        }

        return data;
    }
}
