package dev.coyduck;

import dev.coyduck.managers.CrystalTracker;
import dev.coyduck.managers.HurtTracker;
import dev.coyduck.managers.InteractionManager;
import dev.coyduck.managers.LatencyManager;
import dev.coyduck.managers.MiningManager;
import dev.coyduck.managers.MiningTracker;
import dev.coyduck.managers.MotionTracker;
import dev.coyduck.managers.PopManager;
import dev.coyduck.managers.RotationManager;
import dev.coyduck.modules.AntiSurround;
import dev.coyduck.modules.AutoCity;
import dev.coyduck.modules.AutoCrystal;
import dev.coyduck.modules.AutoTotem;
import dev.coyduck.modules.CevBreaker;
import dev.coyduck.modules.Surround;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.addons.GithubRepo;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.item.Items;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Coyduck extends MeteorAddon {
    public static final Logger LOG = LoggerFactory.getLogger("Coyduck");
    public static final Category CATEGORY = new Category("Coyduck", Items.END_CRYSTAL.getDefaultStack());

    @Override
    public void onInitialize() {
        LOG.info("Initializing Coyduck");

        MeteorClient.EVENT_BUS.subscribe(LatencyManager.get());
        MeteorClient.EVENT_BUS.subscribe(PopManager.get());
        MeteorClient.EVENT_BUS.subscribe(InteractionManager.get());
        MeteorClient.EVENT_BUS.subscribe(MiningTracker.get());
        MeteorClient.EVENT_BUS.subscribe(CrystalTracker.get());
        MeteorClient.EVENT_BUS.subscribe(MotionTracker.get());
        MeteorClient.EVENT_BUS.subscribe(HurtTracker.get());
        MeteorClient.EVENT_BUS.subscribe(RotationManager.get());
        MeteorClient.EVENT_BUS.subscribe(MiningManager.get());

        Modules.get().add(new AutoTotem());
        Modules.get().add(new Surround());
        Modules.get().add(new AutoCrystal());
        Modules.get().add(new AutoCity());
        Modules.get().add(new AntiSurround());
        Modules.get().add(new CevBreaker());
    }

    @Override
    public void onRegisterCategories() {
        Modules.registerCategory(CATEGORY);
    }

    @Override
    public String getPackage() {
        return "dev.coyduck";
    }

    @Override
    public GithubRepo getRepo() {
        return new GithubRepo("duckesteles", "Coyduck");
    }
}
