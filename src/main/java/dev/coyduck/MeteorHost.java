package dev.coyduck;

import dev.coyduck.core.Host;
import dev.coyduck.feature.Features;
import dev.coyduck.modules.AirMine;
import dev.coyduck.modules.AutoCity;
import dev.coyduck.modules.AutoCrystal;
import dev.coyduck.modules.AutoTotem;
import dev.coyduck.modules.ClickGui;
import dev.coyduck.modules.MaceStrike;
import dev.coyduck.modules.NoRotate;
import dev.coyduck.modules.Shaders;
import dev.coyduck.render.Argb;
import meteordevelopment.meteorclient.systems.config.Config;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.player.ChestSwap;
import meteordevelopment.meteorclient.systems.modules.world.Timer;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import net.minecraft.block.BlockState;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.List;

public final class MeteorHost implements Host {
    private record Entry(Module module) implements Listing {
        @Override
        public String name() {
            return module.name;
        }

        @Override
        public String title() {
            return module.title;
        }

        @Override
        public boolean active() {
            return module.isActive();
        }

        @Override
        public String info() {
            return module.getInfoString();
        }

        @Override
        public boolean ours() {
            return module.category == Coyduck.CATEGORY;
        }

        @Override
        public boolean hidden() {
            return Config.get().hiddenModules.get().contains(module);
        }
    }

    private List<Entry> modules;
    private final Color scratch = new Color();

    @Override
    public boolean isFriend(PlayerEntity player) {
        return Friends.get().isFriend(player);
    }

    @Override
    public boolean anyModuleActive() {
        return Modules.get().getGroup(Coyduck.CATEGORY).stream().anyMatch(Module::isActive);
    }

    @Override
    public boolean holdsMovement() {
        return MaceStrike.blocksMoves();
    }

    @Override
    public boolean freezesRotation() {
        return NoRotate.frozen();
    }

    @Override
    public boolean spoofsGround() {
        return AirMine.spoofing();
    }

    @Override
    public boolean keepsBlock(BlockState state) {
        return AutoCity.isFiller(state);
    }

    @Override
    public boolean canAffordPop() {
        return AutoTotem.canAffordPop();
    }

    @Override
    public int totemSlot() {
        return AutoTotem.totemSlot();
    }

    @Override
    public PlayerEntity preferredTarget() {
        AutoCrystal autoCrystal = Features.get(AutoCrystal.class);
        return autoCrystal == null ? null : autoCrystal.target();
    }

    @Override
    public double keepHealth() {
        AutoCrystal autoCrystal = Features.get(AutoCrystal.class);
        return autoCrystal == null ? 0 : autoCrystal.minHealth();
    }

    @Override
    public int tint(int argb) {
        return Shaders.tint(argb);
    }

    @Override
    public int playerColor(PlayerEntity player, int fallback) {
        scratch.r = Argb.r(fallback);
        scratch.g = Argb.g(fallback);
        scratch.b = Argb.b(fallback);
        scratch.a = Argb.a(fallback);
        return PlayerUtils.getPlayerColor(player, scratch).getPacked();
    }

    @Override
    public void timer(double multiplier) {
        Modules.get().get(Timer.class).setOverride(multiplier);
    }

    @Override
    public void swapChest() {
        ChestSwap swap = Modules.get().get(ChestSwap.class);
        if (swap != null) swap.swap();
    }

    @Override
    public void notify(int id, Text message) {
        ChatUtils.sendMsg(id, "Coyduck", Formatting.LIGHT_PURPLE, message);
    }

    @Override
    public int accent() {
        return ClickGui.accentColor();
    }

    @Override
    public List<Entry> modules() {
        if (modules == null) modules = Modules.get().getAll().stream().map(Entry::new).toList();
        return modules;
    }
}
