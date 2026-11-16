package dev.coyduck.core;

import dev.coyduck.managers.CrystalTracker;
import dev.coyduck.managers.FightTracker;
import dev.coyduck.managers.HurtTracker;
import dev.coyduck.managers.InteractionManager;
import dev.coyduck.managers.InventoryManager;
import dev.coyduck.managers.LatencyManager;
import dev.coyduck.managers.MiningManager;
import dev.coyduck.managers.MiningTracker;
import dev.coyduck.managers.MotionTracker;
import dev.coyduck.managers.PopTracker;
import dev.coyduck.managers.RotationManager;
import dev.coyduck.managers.TickRate;
import meteordevelopment.orbit.IEventBus;
import net.minecraft.client.MinecraftClient;

public final class Core {
    public static final MinecraftClient mc = MinecraftClient.getInstance();

    private static Host host = Host.NONE;

    private Core() {}

    public static void start(IEventBus bus, Host environment) {
        host = environment;
        Bus.use(bus, environment);
        Bus.subscribe(TickRate.get());
        Bus.subscribe(LatencyManager.get());
        Bus.subscribe(InventoryManager.get());
        Bus.subscribe(InteractionManager.get());
        Bus.subscribe(MiningTracker.get());
        Bus.subscribe(CrystalTracker.get());
        Bus.subscribe(MotionTracker.get());
        Bus.subscribe(HurtTracker.get());
        Bus.subscribe(RotationManager.get());
        Bus.subscribe(MiningManager.get());
        Bus.subscribe(PopTracker.get());
        Bus.subscribe(FightTracker.get());
    }

    public static Host host() {
        return host;
    }
}
