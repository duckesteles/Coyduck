package dev.coyduck.gui;

import dev.coyduck.Coyduck;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.gui.GuiThemes;
import meteordevelopment.orbit.EventHandler;

import java.io.File;
import java.io.IOException;

public final class ThemeSelector {
    private static final String MARKER = "coyduck-theme";

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        MeteorClient.EVENT_BUS.unsubscribe(this);

        File marker = new File(MeteorClient.FOLDER, MARKER);
        if (marker.exists()) return;

        GuiThemes.select(CoyduckGuiTheme.NAME);
        try {
            marker.createNewFile();
        } catch (IOException e) {
            Coyduck.LOG.warn("Could not save the theme marker", e);
        }
    }
}
