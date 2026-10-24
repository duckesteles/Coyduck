package dev.coyduck;

import dev.coyduck.managers.ConfigSaver;
import dev.coyduck.managers.CrystalTracker;
import dev.coyduck.managers.HurtTracker;
import dev.coyduck.managers.InteractionManager;
import dev.coyduck.managers.InventoryManager;
import dev.coyduck.managers.LatencyManager;
import dev.coyduck.managers.MiningManager;
import dev.coyduck.managers.MiningTracker;
import dev.coyduck.managers.MotionTracker;
import dev.coyduck.managers.RotationManager;
import dev.coyduck.modules.AutoAnchor;
import dev.coyduck.modules.AutoCity;
import dev.coyduck.modules.AutoCrystal;
import dev.coyduck.modules.AutoTotem;
import dev.coyduck.modules.AutoTrap;
import dev.coyduck.modules.AutoWeb;
import dev.coyduck.modules.CustomFov;
import dev.coyduck.modules.ElytraFly;
import dev.coyduck.modules.HoleSnap;
import dev.coyduck.modules.LadderTrap;
import dev.coyduck.modules.SelfTrap;
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

        MeteorClient.EVENT_BUS.subscribe(ConfigSaver.get());
        MeteorClient.EVENT_BUS.subscribe(LatencyManager.get());
        MeteorClient.EVENT_BUS.subscribe(InventoryManager.get());
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
        Modules.get().add(new AutoAnchor());
        Modules.get().add(new AutoCity());
        Modules.get().add(new AutoTrap());
        Modules.get().add(new AutoWeb());
        Modules.get().add(new LadderTrap());
        Modules.get().add(new ElytraFly());
        Modules.get().add(new SelfTrap());
        Modules.get().add(new HoleSnap());
        Modules.get().add(new CustomFov());
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
