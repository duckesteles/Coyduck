package dev.coyduck.modules;

import dev.coyduck.Coyduck;
import dev.coyduck.managers.InteractionManager;
import dev.coyduck.managers.InventoryManager;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

/**
 * Experimental. Fakes a jump with packets, places a block where you stood, then sends an impossible move so the server
 * sets you back. Whether the setback lands you inside the block depends on the server's anti-cheat; on stock Grim it
 * usually puts you on top. The fake jump carries a downward look so the place passes rotation checks.
 */
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

    public Burrow() {
        super(Coyduck.CATEGORY, "burrow+", "Experimental: puts a block where you stand and tries to get the server to set you back inside it. Toggle it to burrow once.");
    }

    @Override
    public void onActivate() {
        toggle();
        if (mc.player == null || mc.world == null) return;

        BlockPos feet = mc.player.getBlockPos();
        if (!mc.world.getBlockState(feet).isReplaceable()) {
            error("Already burrowed.");
            return;
        }
        if (!mc.player.isOnGround()) {
            error("Stand on the ground to burrow.");
            return;
        }
        if (!mc.world.isSpaceEmpty(mc.player.getBoundingBox().offset(0, JUMP[JUMP.length - 1], 0))) {
            error("Not enough room above you.");
            return;
        }

        InventoryManager inventory = InventoryManager.get();
        Item wanted = block.get().item;
        Item other = wanted == Items.OBSIDIAN ? Items.ENDER_CHEST : Items.OBSIDIAN;
        int slot = inventory.find(stack -> stack.isOf(wanted), 0, InventoryManager.MAIN_END);
        if (slot == -1) slot = inventory.find(stack -> stack.isOf(other), 0, InventoryManager.MAIN_END);
        if (slot == -1) {
            error("No obsidian or ender chest.");
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
        info("Sent. If you are standing on the block instead of in it, try another rubberband value.");
    }
}
