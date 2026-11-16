package dev.coyduck;

import dev.coyduck.core.Host;
import dev.coyduck.feature.Features;
import dev.coyduck.modules.AirMine;
import dev.coyduck.modules.AutoCity;
import dev.coyduck.modules.AutoCrystal;
import dev.coyduck.modules.AutoTotem;
import dev.coyduck.modules.MaceStrike;
import dev.coyduck.modules.NoRotate;
import dev.coyduck.modules.Shaders;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.block.BlockState;
import net.minecraft.entity.player.PlayerEntity;

public final class MeteorHost implements Host {
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
}
