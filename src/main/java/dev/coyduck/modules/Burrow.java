package dev.coyduck.modules;

import dev.coyduck.Coyduck;
import dev.coyduck.events.TickEvent;
import dev.coyduck.managers.CrystalTracker;
import dev.coyduck.managers.HurtTracker;
import dev.coyduck.managers.InteractionManager;
import dev.coyduck.managers.InventoryManager;
import dev.coyduck.managers.LatencyManager;
import dev.coyduck.managers.MiningManager;
import dev.coyduck.managers.RotationManager;
import dev.coyduck.events.WorldResetEvent;
import dev.coyduck.utils.BlockRules;
import dev.coyduck.utils.ExplosionDamage;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import dev.coyduck.utils.Trace;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityPose;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.util.Util;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.List;

public class Burrow extends Module {
    private static final double[] JUMP = {0.42, 0.7532, 1.0013, 1.1661};
    private static final long REFILL_WINDOW = 1_500;
    private static final int MAX_FAILS = 2;
    private static final long FAIL_PAUSE = 5_000;
    private static final float LOW_HEALTH = 12;
    private static final float POP_MARGIN = 1.5f;
    private static final float SHIELD_MARGIN = 0.5f;

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
        .description("Block to burrow into, falls back to the other.")
        .defaultValue(Block.Obsidian)
        .build()
    );

    private final Setting<Double> rubberband = sgGeneral.add(new DoubleSetting.Builder()
        .name("rubberband")
        .description("Height of the setback move in blocks.")
        .defaultValue(12)
        .sliderRange(-10, 30)
        .build()
    );

    private final Setting<Boolean> keep = sgGeneral.add(new BoolSetting.Builder()
        .name("keep")
        .description("Burrows again when the block is gone.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> retryDelay = sgGeneral.add(new IntSetting.Builder()
        .name("retry-delay")
        .description("Milliseconds between attempts while keep is on.")
        .defaultValue(500)
        .range(100, 3000)
        .sliderRange(100, 2000)
        .visible(keep::get)
        .build()
    );

    private long lastTry;
    private long pausedUntil;
    private int fails;
    private BlockPos spot;
    private BlockPos topped;
    private boolean warned;

    public Burrow() {
        super(Coyduck.CATEGORY, "burrow+", "Burrows you into a block.");
    }

    @Override
    public void onActivate() {
        reset();
        if (!keep.get()) toggle();
        if (mc.player == null || mc.world == null) return;
        burrow(true);
    }

    @EventHandler
    private void onWorldReset(WorldResetEvent event) {
        reset();
    }

    private void reset() {
        lastTry = 0;
        pausedUntil = 0;
        fails = 0;
        spot = null;
        topped = null;
        warned = false;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (!keep.get() || mc.player == null || mc.world == null || !mc.player.isAlive() || mc.player.isUsingItem()) return;
        long now = Util.getMeasuringTimeMs();
        long delay = Math.max(retryDelay.get(), 2L * LatencyManager.get().rtt());
        if (now - lastTry < delay || now < pausedUntil) return;
        if (moving()) {
            fails = 0;
            return;
        }
        BlockPos feet = mc.player.getBlockPos();
        if (spot != null && !feet.equals(spot)) {
            if (feet.getX() == spot.getX() && feet.getZ() == spot.getZ() && feet.getY() > spot.getY()) {
                topped = spot.toImmutable();
                if (!warned) {
                    warned = true;
                    warning("The server put you on top of the block instead of inside it, so keep stops here instead of building a tower. Burrowing may not work at this spot or on this server.");
                }
                return;
            }
            spot = null;
            fails = 0;
        }
        if (!mc.world.getBlockState(feet).isReplaceable()) {
            fails = 0;
            return;
        }
        if (spot != null && now - lastTry < REFILL_WINDOW + delay && ++fails >= MAX_FAILS) {
            fails = 0;
            pausedUntil = now + FAIL_PAUSE;
            Trace.log("burrow keep pauses, the block at {} keeps disappearing", feet);
            return;
        }
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
        BlockState inFeet = mc.world.getBlockState(feet);
        if (!inFeet.isReplaceable() && softBlock(feet, inFeet)) {
            mc.interactionManager.attackBlock(feet, Direction.UP);
            MiningManager.get().foreignBreak();
            Trace.log("burrow breaks the {} in its cell", inFeet.getBlock().getName().getString());
            inFeet = mc.world.getBlockState(feet);
        }
        if (!inFeet.isReplaceable()) {
            if (verbose) error(inFeet.getCollisionShape(mc.world, feet).isEmpty() ? "Something without collision is in your feet block, break it first." : "Already burrowed.");
            return;
        }
        if (!mc.player.isOnGround()) {
            if (verbose) error("Stand on the ground to burrow.");
            return;
        }
        if (feet.down().equals(topped)) {
            if (verbose) error("You already landed on top of a burrow block here.");
            return;
        }
        BlockState floor = mc.world.getBlockState(feet.down());
        if (floor.hasBlockEntity() || BlockRules.isInteractable(floor.getBlock())) {
            if (verbose) error("The block under you would be used instead of placed on.");
            return;
        }
        Box standing = mc.player.getDimensions(EntityPose.STANDING).getBoxAt(mc.player.getEntityPos());
        double top = JUMP[JUMP.length - 1];
        Box path = standing.offset(0, top, 0).stretch(0, rubberband.get() - top, 0);
        if (!mc.world.isSpaceEmpty(standing.offset(0, top, 0)) || !mc.world.isSpaceEmpty(path)) {
            if (verbose) error("Not enough room above you for the jump and the rubberband.");
            return;
        }
        List<Entity> inCell = InteractionManager.get().obstructions(feet);
        inCell.removeIf(entity -> entity == mc.player || entity instanceof EndCrystalEntity && CrystalTracker.get().isDead(entity));
        for (Entity entity : inCell) {
            if (!(entity instanceof EndCrystalEntity crystal)) {
                if (verbose) error("Something is standing in your feet block.");
                return;
            }
            if (!safeToBreak(crystal)) {
                if (verbose) error("A crystal in your feet block would hurt you too much to break now.");
                return;
            }
        }
        for (Entity entity : inCell) {
            if (!InteractionManager.get().attack(entity, InventoryManager.SwitchMode.Silent, InteractionManager.SwingMode.Packet)) return;
            CrystalTracker.get().explodeAt(entity.getEntityPos());
            Trace.log("burrow breaks a crystal in its cell");
        }

        InventoryManager inventory = InventoryManager.get();
        Item wanted = block.get().item;
        Item other = wanted == Items.OBSIDIAN ? Items.ENDER_CHEST : Items.OBSIDIAN;
        int slot = inventory.find(stack -> stack.isOf(wanted), 0, InventoryManager.MAIN_END);
        if (slot == -1) {
            slot = inventory.find(stack -> stack.isOf(other), 0, InventoryManager.MAIN_END);
            if (slot != -1 && verbose) info("No %s, using %s.", wanted.getName().getString(), other.getName().getString());
        }
        if (slot == -1) {
            if (verbose) error("No obsidian or ender chest.");
            return;
        }

        double x = feet.getX() + 0.5;
        double y = mc.player.getY();
        double z = feet.getZ() + 0.5;
        float yaw = RotationManager.get().serverYaw();
        boolean collision = mc.player.horizontalCollision;

        BlockHitResult hit = new BlockHitResult(new Vec3d(x, y, z), Direction.UP, feet.down(), false);
        boolean sent = InventoryManager.get().withItem(slot, InventoryManager.SwitchMode.Silent, () -> {
            mc.player.setPosition(x, y, z);
            for (double offset : JUMP) {
                mc.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.Full(x, y + offset, z, yaw, 90, false, collision));
            }
            InteractionManager.get().place(feet, hit, InteractionManager.SwingMode.Packet);
            mc.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.Full(x, y + rubberband.get(), z, yaw, 90, false, collision));
        });
        if (!sent) {
            if (verbose) error("Could not switch to the block right now.");
            return;
        }
        spot = feet.toImmutable();
        warned = false;
        Trace.log("burrow sent at {}", feet);
        if (verbose) info("Sent. If you end up on top of the block instead of inside it, burrowing does not work at this spot or on this server.");
    }

    private boolean softBlock(BlockPos pos, BlockState state) {
        if (!state.getCollisionShape(mc.world, pos).isEmpty() || state.getHardness(mc.world, pos) < 0 || mc.interactionManager.isBreakingBlock()) return false;
        MiningManager mining = MiningManager.get();
        return (mining.pos() == null || mining.wasBroken()) && state.calcBlockBreakingDelta(mc.player, mc.world, pos) >= 1;
    }

    private boolean safeToBreak(EndCrystalEntity crystal) {
        float raw = ExplosionDamage.crystalRaw(mc.player, mc.player.getEntityPos(), mc.player.getBoundingBox(), crystal.getEntityPos(), mc.world);
        long arrival = Util.getMeasuringTimeMs() + LatencyManager.get().rtt() / 2;
        float health = AutoTotem.popInFlight() ? 1.0f : mc.player.getHealth() + mc.player.getAbsorptionAmount();
        boolean worst = mc.player.isInsideWall() || health <= LOW_HEALTH;
        float bound = worst ? Math.max(raw, ExplosionDamage.rawBound(mc.player, mc.player.getEntityPos(), crystal.getEntityPos(), ExplosionDamage.CRYSTAL_POWER)) : raw;
        float full = ExplosionDamage.mitigate(mc.player, bound);
        float effective = HurtTracker.get().effectiveOnSelf(bound, arrival);
        if (effective >= health - POP_MARGIN) return false;
        return full < health - POP_MARGIN || effective + SHIELD_MARGIN < full;
    }
}
