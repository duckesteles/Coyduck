package dev.coyduck.modules;

import dev.coyduck.Coyduck;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

public class HitboxDesync extends Module {
    private static final double OFFSET = 0.20000996883537;

    public HitboxDesync() {
        super(Coyduck.CATEGORY, "hitbox-desync", "Steps to the edge of your block so your hitbox pokes a hair into the next one, which makes many crystal auras pick the wrong feet block. Turns itself off after the step.");
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;
        Direction direction = mc.player.getHorizontalFacing();
        BlockPos block = BlockPos.ofFloored(mc.player.getBoundingBox().getCenter());
        double x = direction.getAxis() == Direction.Axis.X ? block.getX() + 0.5 + direction.getOffsetX() * OFFSET : mc.player.getX();
        double z = direction.getAxis() == Direction.Axis.Z ? block.getZ() + 0.5 + direction.getOffsetZ() * OFFSET : mc.player.getZ();
        if (mc.world.isSpaceEmpty(mc.player, mc.player.getBoundingBox().offset(x - mc.player.getX(), 0, z - mc.player.getZ()))) mc.player.setPosition(x, mc.player.getY(), z);
        else warning("Something is in the way.");
        toggle();
    }
}
