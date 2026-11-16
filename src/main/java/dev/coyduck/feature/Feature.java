package dev.coyduck.feature;

import dev.coyduck.core.Core;
import net.minecraft.client.MinecraftClient;

public abstract class Feature {
    protected final MinecraftClient mc = Core.mc;
    public final String name;
    public final String title;
    public final String description;
    public final Settings settings = new Settings();
    private Shell shell = Shell.DETACHED;

    protected Feature(String name, String description) {
        this.name = name;
        this.title = title(name);
        this.description = description;
    }

    static String title(String name) {
        StringBuilder out = new StringBuilder(name.length());
        for (String word : name.split("-")) {
            if (word.isEmpty()) continue;
            if (!out.isEmpty()) out.append(' ');
            out.append(Character.toUpperCase(word.charAt(0))).append(word, 1, word.length());
        }
        return out.toString();
    }

    public final void bind(Shell target) {
        shell = target;
    }

    public final boolean isActive() {
        return shell.isActive();
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
