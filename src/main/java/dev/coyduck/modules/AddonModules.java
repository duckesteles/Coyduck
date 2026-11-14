package dev.coyduck.modules;

import dev.coyduck.managers.PopTracker;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.systems.modules.Modules;

public final class AddonModules {
    private AddonModules() {}

    public static void register(Modules modules) {
        MeteorClient.EVENT_BUS.subscribe(PopTracker.get());
    }
}
