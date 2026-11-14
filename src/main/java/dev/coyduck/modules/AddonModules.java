package dev.coyduck.modules;

import dev.coyduck.hud.ArmorHud;
import dev.coyduck.hud.InventoryHud;
import dev.coyduck.hud.ItemsHud;
import dev.coyduck.managers.PopTracker;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.systems.hud.Hud;
import meteordevelopment.meteorclient.systems.modules.Modules;

public final class AddonModules {
    private AddonModules() {}

    public static void register(Modules modules) {
        MeteorClient.EVENT_BUS.subscribe(PopTracker.get());
        Hud.get().register(InventoryHud.INFO);
        Hud.get().register(ItemsHud.INFO);
        Hud.get().register(ArmorHud.INFO);

        modules.add(new Pusher());
        modules.add(new AntiPearl());
    }
}
