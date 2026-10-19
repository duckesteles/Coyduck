package dev.coyduck.managers;

import dev.coyduck.Coyduck;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.game.OpenScreenEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.gui.WidgetScreen;
import meteordevelopment.meteorclient.systems.Systems;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public final class ConfigSaver {
    private static final ConfigSaver INSTANCE = new ConfigSaver();

    private static final int INTERVAL = 20 * 60 * 2;

    private int ticks;

    private ConfigSaver() {}

    public static ConfigSaver get() {
        return INSTANCE;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    private void onOpenScreen(OpenScreenEvent event) {
        if (event.isCancelled()) return;
        if (mc.currentScreen instanceof WidgetScreen && !(event.screen instanceof WidgetScreen)) save();
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        save();
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (++ticks < INTERVAL) return;
        ticks = 0;
        save();
    }

    private void save() {
        ticks = 0;
        try {
            Systems.save();
        } catch (RuntimeException e) {
            Coyduck.LOG.error("Failed to save Meteor config", e);
        }
    }
}
