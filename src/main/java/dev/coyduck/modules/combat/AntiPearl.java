package dev.coyduck.modules.combat;

import dev.coyduck.Coyduck;
import dev.coyduck.managers.InteractionManager;
import dev.coyduck.managers.InventoryManager;
import dev.coyduck.managers.LatencyManager;
import dev.coyduck.managers.RotationManager;
import dev.coyduck.utils.Trace;
import meteordevelopment.meteorclient.events.entity.EntityAddedEvent;
import meteordevelopment.meteorclient.settings.BlockListSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.thrown.EnderPearlEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.util.List;

public class AntiPearl extends Module {
    private static final int PRIORITY = 109;
    private static final double GRAVITY = 0.03;
    private static final double DRAG = 0.99;
    private static final int FLIGHT_TICKS = 40;
    private static final double OWNER_REACH = 3;
    private static final double PLACE_REACH = 4.45;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<List<Block>> blocks = sgGeneral.add(new BlockListSetting.Builder()
        .name("blocks")
        .description("Blocks to put in the pearl's way, first one you have wins.")
        .defaultValue(Blocks.OBSIDIAN, Blocks.CRYING_OBSIDIAN, Blocks.NETHERRACK)
        .build()
    );

    private final Setting<Double> targetRange = sgGeneral.add(new DoubleSetting.Builder()
        .name("target-range")
        .description("Only pearls thrown by players this close.")
        .defaultValue(10)
        .range(2, 32)
        .sliderRange(4, 20)
        .build()
    );

    private final Setting<Integer> extraLead = sgGeneral.add(new IntSetting.Builder()
        .name("extra-lead")
        .description("Ticks added on top of your ping when picking where along the flight to block.")
        .defaultValue(1)
        .range(0, 6)
        .sliderRange(0, 6)
        .build()
    );

    private final Setting<InventoryManager.SwitchMode> switchMode = sgGeneral.add(new EnumSetting.Builder<InventoryManager.SwitchMode>()
        .name("switch")
        .description("How to switch to the block.")
        .defaultValue(InventoryManager.SwitchMode.Silent)
        .build()
    );

    private final Setting<InteractionManager.SwingMode> swing = sgGeneral.add(new EnumSetting.Builder<InteractionManager.SwingMode>()
        .name("swing")
        .description("How to swing when placing.")
        .defaultValue(InteractionManager.SwingMode.Packet)
        .build()
    );

    public AntiPearl() {
        super(Coyduck.CATEGORY, "anti-pearl", "Puts a block in the path of an enemy's ender pearl the moment it is thrown, so they land where they stood. With 200 ms ping it only reaches pearls flying past you.");
    }

    @EventHandler
    private void onEntityAdded(EntityAddedEvent event) {
        if (!(event.entity instanceof EnderPearlEntity pearl) || mc.player == null || mc.world == null) return;
        PlayerEntity owner = owner(pearl);
        if (owner == null || owner == mc.player || Friends.get().isFriend(owner) || owner.squaredDistanceTo(mc.player) > targetRange.get() * targetRange.get()) return;

        int slot = InventoryManager.get().find(stack -> stack.getItem() instanceof BlockItem item && blocks.get().contains(item.getBlock()), 0, InventoryManager.MAIN_END);
        if (slot == -1) return;

        int lead = (int) Math.ceil(LatencyManager.get().rtt() / 50.0) + extraLead.get();
        BlockPos spot = spot(pearl, lead);
        if (spot == null) return;

        InteractionManager interaction = InteractionManager.get();
        BlockHitResult hit = interaction.hit(spot, true);
        if (hit == null) return;
        boolean[] placed = {false};
        InventoryManager.get().withItem(slot, switchMode.get(), () -> {
            if (!RotationManager.get().lookNow(hit.getPos(), PRIORITY)) return;
            interaction.place(spot, hit, swing.get());
            placed[0] = true;
        });
        if (placed[0]) Trace.log("anti pearl blocks {}'s pearl at {} lead {}t", owner.getName().getString(), spot, lead);
    }

    private PlayerEntity owner(EnderPearlEntity pearl) {
        if (pearl.getOwner() instanceof PlayerEntity player) return player;
        PlayerEntity best = null;
        double closest = OWNER_REACH * OWNER_REACH;
        for (PlayerEntity player : mc.world.getPlayers()) {
            double distance = player.getEyePos().squaredDistanceTo(pearl.getEntityPos());
            if (distance < closest) {
                closest = distance;
                best = player;
            }
        }
        return best;
    }

    private BlockPos spot(EnderPearlEntity pearl, int lead) {
        InteractionManager interaction = InteractionManager.get();
        Vec3d eye = mc.player.getEyePos();
        Vec3d position = pearl.getEntityPos();
        Vec3d velocity = pearl.getVelocity();
        for (int tick = 0; tick < FLIGHT_TICKS; tick++) {
            Vec3d next = position.add(velocity);
            HitResult hit = mc.world.raycast(new RaycastContext(position, next, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, pearl));
            if (hit.getType() != HitResult.Type.MISS) return null;
            if (tick >= lead) {
                BlockPos pos = BlockPos.ofFloored(next);
                if (interaction.canPlace(pos) && !interaction.wasRejected(pos) && free(pos) && new Box(pos).squaredMagnitude(eye) <= PLACE_REACH * PLACE_REACH && InteractionManager.centerReach(pos)) return pos;
                if (Vec3d.ofCenter(pos).squaredDistanceTo(eye) > 64 && next.squaredDistanceTo(eye) > position.squaredDistanceTo(eye)) return null;
            }
            position = next;
            velocity = velocity.multiply(DRAG).subtract(0, GRAVITY, 0);
        }
        return null;
    }

    private boolean free(BlockPos pos) {
        for (Entity entity : InteractionManager.get().obstructions(pos)) {
            if (!(entity instanceof EnderPearlEntity)) return false;
        }
        return true;
    }
}
