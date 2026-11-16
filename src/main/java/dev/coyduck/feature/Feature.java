package dev.coyduck.feature;

import dev.coyduck.core.Core;
import net.minecraft.client.MinecraftClient;

public abstract class Feature {
    protected final MinecraftClient mc = Core.mc;
    public final String name;
    public final String description;
    public final Settings settings = new Settings();
    private Shell shell = Shell.DETACHED;

    protected Feature(String name, String description) {
        this.name = name;
        this.description = description;
    }

    public final void bind(Shell target) {
        shell = target;
    }

    public final boolean isActive() {
        return shell.active();
    }

    public final void toggle() {
        shell.toggle();
    }

    public void onActivate() {}

    public void onDeactivate() {}

    public String getInfoString() {
        return null;
    }

    public final void info(String message, Object... args) {
        shell.info(message, args);
    }

    public final void warning(String message, Object... args) {
        shell.warning(message, args);
    }

    public final void error(String message, Object... args) {
        shell.error(message, args);
    }
}
