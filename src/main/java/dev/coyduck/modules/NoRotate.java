package dev.coyduck.modules;

import dev.coyduck.Coyduck;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;

public class NoRotate extends Module {
    public NoRotate() {
        super(Coyduck.CATEGORY, "no-rotate+", "Keeps your view where you look when the server turns your head, from a setback or a rotation packet, for example while you are inside blocks.");
    }

    public static boolean on() {
        Modules modules = Modules.get();
        if (modules == null) return false;
        NoRotate module = modules.get(NoRotate.class);
        return module != null && module.isActive();
    }

    @Override
    public void onActivate() {
        Module meteor = Modules.get().get(meteordevelopment.meteorclient.systems.modules.player.NoRotate.class);
        if (meteor != null && meteor.isActive()) {
            meteor.toggle();
            info("Disabled %s, it handles the same packets.", meteor.title);
        }
    }
}
