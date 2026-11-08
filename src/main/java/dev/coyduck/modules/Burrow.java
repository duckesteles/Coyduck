package dev.coyduck.modules;

import dev.coyduck.Coyduck;
import dev.coyduck.managers.InteractionManager;
import dev.coyduck.managers.InventoryManager;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import dev.coyduck.utils.Trace;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.Util;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

public class Burrow extends Module {
    private static final double[] JUMP = {0.42, 0.7532, 1.0013, 1.1661};

    public enum Block {
        Obsidian(Items.OBSIDIAN),
        EnderChest(Items.ENDER_CHEST);

        final Item item;

        Block(Item item) {
            this.item = item;
        }
    }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Block> block = sgGeneral.add(new EnumSetting.Builder<Block>()
        .name("block")
        .description("Block to burrow into. Falls back to the other one if you have none.")
        .defaultValue(Block.Obsidian)
        .build()
    );

    private final Setting<Double> rubberband = sgGeneral.add(new DoubleSetting.Builder()
        .name("rubberband")
        .description("Height of the impossible move that makes the server set you back. Try other values if you end up on top of the block.")
        .defaultValue(12)
        .sliderRange(-10, 30)
        .build()
    );

    private final Setting<Boolean> keep = sgGeneral.add(new BoolSetting.Builder()
        .name("keep")
        .description("Stays on and burrows again whenever the block in your feet is gone, for example when an enemy keeps breaking it. Only tries while you stand still on the ground and press no movement keys.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> retryDelay = sgGeneral.add(new IntSetting.Builder()
        .name("retry-delay")
        .description("Milliseconds between burrow attempts while keep is on, so the server has time to set you back into the block.")
        .defaultValue(500)
        .range(100, 3000)
        .sliderRange(100, 2000)
        .visible(keep::get)
        .build()
    );

    private long lastTry;
    private BlockPos spot;
    private boolean warned;

    public Burrow() {
        super(Coyduck.CATEGORY, "burrow+", "Experimental: puts a block where you stand and tries to get the server to set you back inside it. Toggle it to burrow once, or turn on keep to burrow again every time the block is broken.");
    }

    @Override
    public void onActivate() {
        lastTry = 0;
        spot = null;
        warned = false;
        if (!keep.get()) toggle();
        if (mc.player == null || mc.world == null) return;
        burrow(true);
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (!keep.get() || mc.player == null || mc.world == null) return;
        if (Util.getMeasuringTimeMs() - lastTry < retryDelay.get() || moving()) return;
        BlockPos feet = mc.player.getBlockPos();
        if (spot != null && !feet.equals(spot)) {
            if (feet.getX() == spot.getX() && feet.getZ() == spot.getZ() && feet.getY() > spot.getY()) {
                if (!warned) {
                    warned = true;
                    warning("The server put you on top of the block instead of inside it, so keep stops here instead of building a tower. Step back down or try another rubberband value.");
                }
                return;
            }
            spot = null;
        }
        if (!mc.world.getBlockState(feet).isReplaceable()) return;
        burrow(false);
    }

    private boolean moving() {
        double speed = mc.player.getVelocity().horizontalLength();
        return speed > 0.05 || mc.options.forwardKey.isPressed() || mc.options.backKey.isPressed() || mc.options.leftKey.isPressed()
            || mc.options.rightKey.isPressed() || mc.options.jumpKey.isPressed();
    }

    private void burrow(boolean verbose) {
        lastTry = Util.getMeasuringTimeMs();
        BlockPos feet = mc.player.getBlockPos();
        if (!mc.world.getBlockState(feet).isReplaceable()) {
            if (verbose) error("Already burrowed.");
            return;
        }
        if (!mc.player.isOnGround()) {
            if (verbose) error("Stand on the ground to burrow.");
            return;
        }
        if (!mc.world.isSpaceEmpty(mc.player.getBoundingBox().offset(0, JUMP[JUMP.length - 1], 0))) {
            if (verbose) error("Not enough room above you.");
            return;
        }

        InventoryManager inventory = InventoryManager.get();
        Item wanted = block.get().item;
        Item other = wanted == Items.OBSIDIAN ? Items.ENDER_CHEST : Items.OBSIDIAN;
        int slot = inventory.find(stack -> stack.isOf(wanted), 0, InventoryManager.MAIN_END);
        if (slot == -1) slot = inventory.find(stack -> stack.isOf(other), 0, InventoryManager.MAIN_END);
        if (slot == -1) {
            if (verbose) error("No obsidian or ender chest.");
            return;
        }

        double x = feet.getX() + 0.5;
        double y = mc.player.getY();
        double z = feet.getZ() + 0.5;
        float yaw = mc.player.getYaw();
        boolean collision = mc.player.horizontalCollision;

        mc.player.setPosition(x, y, z);
        for (double offset : JUMP) {
            mc.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.Full(x, y + offset, z, yaw, 90, false, collision));
        }

        BlockHitResult hit = new BlockHitResult(new Vec3d(x, y, z), Direction.UP, feet.down(), false);
        InventoryManager.get().withItem(slot, InventoryManager.SwitchMode.Silent, () -> {
            mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hit);
            InteractionManager.get().swing(InteractionManager.SwingMode.Packet);
        });

        mc.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.Full(x, y + rubberband.get(), z, yaw, 90, false, collision));
        spot = feet.toImmutable();
        warned = false;
        Trace.log("burrow sent at {}", feet);
        if (verbose) info("Sent. If you are standing on the block instead of in it, try another rubberband value.");
    }
}
