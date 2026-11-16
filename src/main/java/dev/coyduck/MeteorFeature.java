package dev.coyduck;

import dev.coyduck.feature.Feature;
import dev.coyduck.feature.Shell;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.nbt.NbtCompound;

public final class MeteorFeature extends Module implements Shell {
    private final Feature feature;

    public MeteorFeature(Feature feature) {
        super(Coyduck.CATEGORY, feature.name, feature.description);
        this.feature = feature;
        runInMainMenu = feature.runsInMainMenu();
        MeteorSettings.mirror(feature.settings, settings);
        feature.bind(this);
    }

    public Feature feature() {
        return feature;
    }

    @Override
    public void onActivate() {
        MeteorClient.EVENT_BUS.subscribe(feature);
        feature.onActivate();
    }

    @Override
    public void onDeactivate() {
        MeteorClient.EVENT_BUS.unsubscribe(feature);
        feature.onDeactivate();
    }

    @Override
    public void displace(String reason, String... rivals) {
        for (String rival : rivals) {
            Module module = Modules.get().get(rival);
            if (module == null || module == this || !module.isActive()) continue;
            module.toggle();
            info("Disabled %s, %s", module.title, reason);
        }
    }

    @Override
    public String getInfoString() {
        return feature.getInfoString();
    }

    @Override
    public Module fromTag(NbtCompound tag) {
        feature.onLoad();
        return super.fromTag(tag);
    }
}
