package dev.coyduck;

import dev.coyduck.commands.KitCommand;
import dev.coyduck.commands.PilotCommand;
import dev.coyduck.core.Core;
import dev.coyduck.feature.Feature;
import dev.coyduck.feature.Features;
import dev.coyduck.gui.ConfigSaver;
import dev.coyduck.gui.CoyduckTheme;
import dev.coyduck.hud.ClockHud;
import dev.coyduck.hud.InfoHud;
import dev.coyduck.hud.InventoryHud;
import dev.coyduck.hud.ItemsHud;
import dev.coyduck.hud.ModuleList;
import dev.coyduck.hud.PlayersHud;
import dev.coyduck.hud.Watermark;
import dev.coyduck.modules.SuffocationGuard;
import dev.coyduck.modules.ChestSorter;
import dev.coyduck.modules.ChestSearch;
import dev.coyduck.modules.DiscordWebhook;
import dev.coyduck.modules.Regear;
import dev.coyduck.modules.HudStyle;
import dev.coyduck.modules.AntiRegear;
import dev.coyduck.modules.LogoutSpots;
import dev.coyduck.modules.PhaseEsp;
import dev.coyduck.modules.AirMine;
import dev.coyduck.modules.AutoAnchor;
import dev.coyduck.modules.CrystalChams;
import dev.coyduck.modules.AutoCity;
import dev.coyduck.modules.AutoEz;
import dev.coyduck.modules.AutoCrystal;
import dev.coyduck.modules.AutoTotem;
import dev.coyduck.modules.AutoTrap;
import dev.coyduck.modules.AutoWeb;
import dev.coyduck.modules.BreakEsp;
import dev.coyduck.modules.Burrow;
import dev.coyduck.modules.ClickGui;
import dev.coyduck.modules.CustomFov;
import dev.coyduck.modules.ElytraFly;
import dev.coyduck.modules.Esp;
import dev.coyduck.modules.ElytraPilot;
import dev.coyduck.modules.HideRich;
import dev.coyduck.modules.HoleEsp;
import dev.coyduck.modules.HoleSnap;
import dev.coyduck.modules.AntiPiston;
import dev.coyduck.modules.KillAura;
import dev.coyduck.modules.MaceStrike;
import dev.coyduck.modules.ElytraEvade;
import dev.coyduck.modules.AutoLogin;
import dev.coyduck.modules.BoatFly;
import dev.coyduck.modules.AutoXp;
import dev.coyduck.modules.HoleFill;
import dev.coyduck.modules.KillEffects;
import dev.coyduck.modules.NoExplosion;
import dev.coyduck.modules.NoClimb;
import dev.coyduck.modules.NametagsPlus;
import dev.coyduck.modules.NoRotate;
import dev.coyduck.modules.PacketMinePlus;
import dev.coyduck.modules.SpeedPlus;
import dev.coyduck.modules.LadderTrap;
import dev.coyduck.modules.PearlPhase;
import dev.coyduck.modules.PopCounter;
import dev.coyduck.modules.SelfTrap;
import dev.coyduck.modules.Shaders;
import dev.coyduck.modules.Surround;
import dev.coyduck.modules.UpdateChecker;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.addons.GithubRepo;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.commands.Commands;
import meteordevelopment.meteorclient.gui.GuiThemes;
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

        GuiThemes.add(new CoyduckTheme());
        MeteorWidget.register(Watermark.INFO);
        MeteorWidget.register(ModuleList.INFO);
        MeteorWidget.register(InfoHud.INFO);
        MeteorWidget.register(ClockHud.INFO);
        MeteorWidget.register(PlayersHud.INFO);
        MeteorWidget.register(InventoryHud.INFO);
        MeteorWidget.register(ItemsHud.INFO);

        MeteorClient.EVENT_BUS.subscribe(ConfigSaver.get());
        Core.start(MeteorClient.EVENT_BUS, new MeteorHost());
        MeteorClient.EVENT_BUS.subscribe(MeteorCanvas.BRIDGE);
        MeteorClient.EVENT_BUS.subscribe(MeteorOverlay.BRIDGE);
        MeteorClient.EVENT_BUS.subscribe(MeteorEvents.BRIDGE);

        add(new AutoTotem());
        add(new Surround());
        add(new AutoCrystal());
        add(new AutoAnchor());
        add(new AutoCity());
        add(new AutoTrap());
        add(new AutoWeb());
        add(new LadderTrap());
        add(new ElytraFly());
        add(new ElytraPilot());
        add(new PearlPhase());
        add(new Burrow());
        add(new SelfTrap());
        add(new HoleSnap());
        add(new HoleFill());
        add(new AutoXp());
        add(new AntiPiston());
        add(new KillAura());
        add(new MaceStrike());
        add(new ElytraEvade());
        add(new AutoLogin());
        add(new BoatFly());
        add(new CustomFov());
        add(new AirMine());
        add(new PopCounter());
        add(new AutoEz());
        add(new HideRich());
        Modules.get().add(new ClickGui());
        add(new HudStyle());
        add(new KillEffects());
        add(new NoExplosion());
        add(new NoRotate());
        add(new NoClimb());
        add(new SpeedPlus());
        add(new Esp());
        add(new CrystalChams());
        add(new HoleEsp());
        add(new BreakEsp());
        add(new Shaders());
        add(new UpdateChecker());
        add(new PacketMinePlus());
        add(new NametagsPlus());
        add(new PhaseEsp());
        add(new LogoutSpots());
        add(new AntiRegear());
        add(new Regear());
        add(new DiscordWebhook());
        add(new ChestSearch());
        add(new ChestSorter());
        add(new SuffocationGuard());

        Commands.add(new MeteorCommand(new PilotCommand()));
        Commands.add(new MeteorCommand(new KitCommand()));
    }

    private static void add(Feature feature) {
        Modules.get().add(new MeteorFeature(Features.add(feature)));
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
