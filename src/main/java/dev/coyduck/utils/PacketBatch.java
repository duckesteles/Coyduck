package dev.coyduck.utils;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public final class PacketBatch {
    private static int depth;

    private PacketBatch() {}

    public static boolean active() {
        return depth > 0 && mc.isOnThread();
    }

    public static void run(Runnable action) {
        depth++;
        try {
            action.run();
        } finally {
            if (--depth == 0 && mc.getNetworkHandler() != null) mc.getNetworkHandler().getConnection().flush();
        }
    }
}
