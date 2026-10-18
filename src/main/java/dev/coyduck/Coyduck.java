package dev.coyduck;

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
