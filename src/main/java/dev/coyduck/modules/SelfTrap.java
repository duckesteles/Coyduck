package dev.coyduck.modules;

import dev.coyduck.Coyduck;
import dev.coyduck.events.PacketApplyEvent;
import dev.coyduck.managers.CrystalTracker;
import dev.coyduck.managers.HurtTracker;
import dev.coyduck.managers.InteractionManager;
import dev.coyduck.managers.InventoryManager;
import dev.coyduck.managers.LatencyManager;
import dev.coyduck.managers.MiningManager;
import dev.coyduck.utils.DuckRender;
import dev.coyduck.utils.ExplosionDamage;
import dev.coyduck.utils.Targets;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import meteordevelopment.meteorclient.events.entity.EntityAddedEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.BlockListSetting;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ChunkDeltaUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.EntitiesDestroyS2CPacket;
import net.minecraft.network.packet.s2c.play.ExplosionS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerActionResponseS2CPacket;
import net.minecraft.util.Util;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;

import java.util.ArrayList;
import java.util.List;

public class SelfTrap extends Module {
    // Slack for armor, exposure and position estimates, so a crystal judged safe never pops you by a fraction.
    private static final float POP_MARGIN = 1.5f;
    private static final float LOW_HEALTH = 12;
    private static final double EDGE = 1.0E-4;
    private static final double NEAR = 4.0;
    private static final double STILL_SPEED = 0.05;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");

    private final Setting<List<Block>> blocks = sgGeneral.add(new BlockListSetting.Builder()
        .name("blocks")
        .description("Blocks used for the trap, in order of preference.")
        .defaultValue(Blocks.OBSIDIAN, Blocks.CRYING_OBSIDIAN, Blocks.NETHERITE_BLOCK)
        .filter(block -> block.getBlastResistance() >= 600)
        .build()
    );

    private final Setting<Boolean> top = sgGeneral.add(new BoolSetting.Builder()
        .name("top")
        .description("Also covers the block above your head. Prefers a block crystals cannot be placed on, so it cannot be used to crystal you from above.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> smart = sgGeneral.add(new BoolSetting.Builder()
        .name("smart")
        .description("Only traps while an enemy is in range.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Double> enemyRange = sgGeneral.add(new DoubleSetting.Builder()
        .name("enemy-range")
        .description("Range an enemy must be in for smart mode.")
        .defaultValue(10)
        .range(1, 20)
        .sliderRange(1, 20)
        .visible(smart::get)
        .build()
    );

    private final Setting<Boolean> onlyStill = sgGeneral.add(new BoolSetting.Builder()
        .name("only-still")
        .description("Only traps while you stand still on the ground, so walking around never boxes you in.")
        .defaultValue(true)
        .build()
    );

    private final Setting<InventoryManager.SwitchMode> switchMode = sgGeneral.add(new EnumSetting.Builder<InventoryManager.SwitchMode>()
        .name("switch")
        .description("Silent selects the item's hotbar slot for a moment and sends no inventory clicks; an item in your inventory is first moved into an empty hotbar slot once. InvSwap swaps the item into your hand and back with inventory clicks, which some servers answer by resending the whole inventory. While you eat, InvSwap is always used. Your offhand totem is never touched.")
        .defaultValue(InventoryManager.SwitchMode.Silent)
        .build()
    );

    private final Setting<Boolean> airplace = sgGeneral.add(new BoolSetting.Builder()
        .name("airplace")
        .description("Places without a neighbouring block to click on.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> blocksPerTick = sgGeneral.add(new IntSetting.Builder()
        .name("blocks-per-tick")
        .description("Most blocks placed in one tick, packet reactions included.")
        .defaultValue(4)
        .range(1, 8)
        .sliderRange(1, 8)
        .build()
    );

    private final Setting<Boolean> breakCrystals = sgGeneral.add(new BoolSetting.Builder()
        .name("break-crystals")
        .description("Breaks a crystal that sits where a trap block belongs, then places the block in the same tick.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> allowPop = sgGeneral.add(new BoolSetting.Builder()
        .name("allow-pop")
        .description("Also breaks such a crystal when it pops you, once Auto Totem+ has confirmed your offhand totem and you still have a spare.")
        .defaultValue(false)
        .visible(breakCrystals::get)
        .build()
    );

    private final Setting<Boolean> rotate = sgGeneral.add(new BoolSetting.Builder()
        .name("rotate")
        .description("Turns your head toward each block before placing it and places only once the server sees you looking at it. Slower, but for servers whose anticheat checks where you look. Visible faces are always preferred.")
        .defaultValue(true)
        .build()
    );

    private final Setting<InteractionManager.SwingMode> swing = sgGeneral.add(new EnumSetting.Builder<InteractionManager.SwingMode>()
        .name("swing")
        .description("How the hand swing is shown.")
        .defaultValue(InteractionManager.SwingMode.Packet)
        .build()
    );

    private final Setting<Boolean> render = sgRender.add(new BoolSetting.Builder()
        .name("render")
        .description("Highlights placed blocks.")
        .defaultValue(true)
        .build()
    );

    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .description("How the highlight is drawn.")
        .defaultValue(ShapeMode.Both)
        .visible(render::get)
        .build()
    );

    private final Setting<SettingColor> sideColor = sgRender.add(new ColorSetting.Builder()
        .name("side-color")
        .description("Highlight side color.")
        .defaultValue(new SettingColor(110, 45, 200, 40))
        .visible(render::get)
        .build()
    );

    private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder()
        .name("line-color")
        .description("Highlight line color.")
        .defaultValue(new SettingColor(110, 45, 200, 200))
        .visible(render::get)
        .build()
    );

    private final Long2LongOpenHashMap rendered = new Long2LongOpenHashMap();
    private int budget;
    private boolean running;

    public SelfTrap() {
        super(Coyduck.CATEGORY, "self-trap+", "Boxes you in: the block under your feet, the four sides at your feet and head, and the block above your head.");
    }

    @Override
    public void onActivate() {
        Module meteor = Modules.get().get(meteordevelopment.meteorclient.systems.modules.combat.SelfTrap.class);
        if (meteor != null && meteor.isActive()) {
            meteor.toggle();
            info("Disabled %s, it places over the same blocks.", meteor.title);
        }

        rendered.clear();
        budget = blocksPerTick.get();
        pass();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        budget = blocksPerTick.get();
        pass();
    }

    @EventHandler
    private void onTickPost(TickEvent.Post event) {
        pass();
    }

    @EventHandler
    private void onPacket(PacketApplyEvent.Post event) {
        if (!canAct()) return;

        BlockPos center = mc.player.getBlockPos().up();
        boolean relevant = switch (event.packet) {
            case BlockUpdateS2CPacket packet -> packet.getPos().isWithinDistance(center, NEAR);
            case ChunkDeltaUpdateS2CPacket packet -> Targets.touches(packet, center, NEAR);
            case EntitiesDestroyS2CPacket ignored -> true;
            case ExplosionS2CPacket ignored -> true;
            case PlayerActionResponseS2CPacket ignored -> true;
            default -> false;
        };

        if (relevant) pass();
    }

    @EventHandler
    private void onEntityAdded(EntityAddedEvent event) {
        if (event.entity instanceof EndCrystalEntity && canAct() && event.entity.getBlockPos().isWithinDistance(mc.player.getBlockPos().up(), NEAR)) pass();
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (!render.get()) return;

        long now = Util.getMeasuringTimeMs();
        rendered.long2LongEntrySet().removeIf(entry -> now - entry.getLongValue() > DuckRender.LIFETIME);
        for (var entry : rendered.long2LongEntrySet()) {
            DuckRender.box(event.renderer, new Box(BlockPos.fromLong(entry.getLongKey())), sideColor.get(), lineColor.get(), shapeMode.get(), DuckRender.alpha(now - entry.getLongValue()));
        }
    }

    private void pass() {
        if (running || !canAct()) return;

        running = true;
        try {
            run();
        } finally {
            running = false;
        }
    }

    private void run() {
        if (budget <= 0 || HoleSnap.snapping()) return;
        if (smart.get() && Targets.find(enemyRange.get()) == null) return;
        if (onlyStill.get() && (!mc.player.isOnGround() || mc.player.getVelocity().horizontalLengthSquared() > STILL_SPEED * STILL_SPEED)) return;

        LongSet columns = columns();
        placeAll(blockSlot(false), ring(columns));
        if (top.get()) placeAll(blockSlot(true), roof(columns));
    }

    private void placeAll(int slot, List<BlockPos> cells) {
        if (slot == -1 || budget <= 0) return;

        InteractionManager interaction = InteractionManager.get();
        MiningManager mining = MiningManager.get();
        List<BlockPos> placeable = new ArrayList<>();
        int cost = 0;

        for (BlockPos pos : cells) {
            if (cost >= budget) break;
            if (!interaction.canPlace(pos) || mining.isMining(pos)) continue;
            if (mc.player.getBoundingBox().intersects(new Box(pos))) continue;

            int needed = cost(pos);
            if (needed == 0 || cost + needed > budget) continue;
            if (!clear(pos)) continue;

            placeable.add(pos);
            cost += needed;
        }

        if (placeable.isEmpty() || !interaction.ready(placeable.get(0), airplace.get(), rotate.get(), InteractionManager.DEFENSE_PRIORITY)) return;

        InventoryManager.get().withItemWhileEating(slot, switchMode.get(), () -> {
            for (BlockPos pos : placeable) {
                if (budget <= 0 || !isBlock(mc.player.getMainHandStack())) break;
                place(pos);
            }
        });
    }

    private LongSet columns() {
        int level = MathHelper.floor(mc.player.getY() + 0.4);
        Box box = mc.player.getBoundingBox();
        LongSet columns = new LongOpenHashSet();
        for (int x = MathHelper.floor(box.minX + EDGE); x <= MathHelper.floor(box.maxX - EDGE); x++) {
            for (int z = MathHelper.floor(box.minZ + EDGE); z <= MathHelper.floor(box.maxZ - EDGE); z++) {
                columns.add(BlockPos.asLong(x, level, z));
            }
        }
        return columns;
    }

    private List<BlockPos> ring(LongSet columns) {
        List<BlockPos> cells = new ArrayList<>();
        for (long key : columns) {
            BlockPos column = BlockPos.fromLong(key);
            for (Direction direction : Direction.Type.HORIZONTAL) {
                BlockPos side = column.offset(direction);
                if (columns.contains(side.asLong())) continue;

                if (!cells.contains(side)) cells.add(side);
            }
        }

        for (long key : columns) {
            BlockPos below = BlockPos.fromLong(key).down();
            if (!cells.contains(below)) cells.add(below);
        }

        for (long key : columns) {
            BlockPos column = BlockPos.fromLong(key);
            for (Direction direction : Direction.Type.HORIZONTAL) {
                BlockPos side = column.offset(direction);
                if (columns.contains(side.asLong())) continue;

                BlockPos head = side.up();
                if (!cells.contains(head)) cells.add(head);
            }
        }
        return cells;
    }

    private static List<BlockPos> roof(LongSet columns) {
        List<BlockPos> cells = new ArrayList<>();
        for (long key : columns) cells.add(BlockPos.fromLong(key).up(2));
        return cells;
    }

    private int cost(BlockPos pos) {
        InteractionManager interaction = InteractionManager.get();
        if (interaction.hit(pos, airplace.get()) != null) return 1;
        if (airplace.get()) return 0;

        BlockPos support = pos.down();
        return interaction.canPlace(support) && interaction.obstructions(support).isEmpty() && interaction.hit(support, false) != null ? 2 : 0;
    }

    private void place(BlockPos pos) {
        InteractionManager interaction = InteractionManager.get();
        if (!interaction.canPlace(pos)) return;

        BlockHitResult hit = interaction.hit(pos, airplace.get());
        if (hit == null && !airplace.get()) {
            BlockPos support = pos.down();
            BlockHitResult supportHit = interaction.canPlace(support) && interaction.obstructions(support).isEmpty() ? interaction.hit(support, false) : null;
            if (supportHit == null) return;

            commit(support, supportHit);
            if (budget <= 0 || !isBlock(mc.player.getMainHandStack())) return;
            hit = interaction.hit(pos, false);
        }

        if (hit != null) commit(pos, hit);
    }

    private void commit(BlockPos pos, BlockHitResult hit) {
        if (!InteractionManager.get().place(pos, hit, swing.get(), rotate.get(), InteractionManager.DEFENSE_PRIORITY)) {
            budget = 0;
            return;
        }
        rendered.put(pos.asLong(), Util.getMeasuringTimeMs());
        budget--;
    }

    private boolean clear(BlockPos pos) {
        InteractionManager interaction = InteractionManager.get();
        List<Entity> entities = interaction.obstructions(pos);
        entities.removeIf(entity -> entity instanceof EndCrystalEntity && CrystalTracker.get().isDead(entity));
        if (entities.isEmpty()) return true;
        if (!breakCrystals.get()) return false;

        EndCrystalEntity target = null;
        boolean pop = false;
        for (Entity entity : entities) {
            if (!(entity instanceof EndCrystalEntity crystal)) return false;
            if (interaction.recentlyAttacked(crystal)) return true;

            float raw = ExplosionDamage.crystalRaw(mc.player, mc.player.getEntityPos(), mc.player.getBoundingBox(), crystal.getEntityPos(), mc.world);
            float damage = HurtTracker.get().effectiveOnSelf(raw, Util.getMeasuringTimeMs() + LatencyManager.get().rtt() / 2);
            float health = AutoTotem.popInFlight() ? 1.0f : mc.player.getHealth() + mc.player.getAbsorptionAmount();

            // When low, ignore the blocks in between: against rebreak they are often gone by the time it explodes.
            boolean worst = mc.player.isInsideWall() || health <= LOW_HEALTH;
            float full = worst ? ExplosionDamage.upperBound(mc.player, mc.player.getEntityPos(), crystal.getEntityPos()) : ExplosionDamage.mitigate(mc.player, raw);
            boolean pops = damage >= health - POP_MARGIN || full >= health - POP_MARGIN;
            if (pops && !(allowPop.get() && AutoTotem.canAffordPop())) continue;
            if (target == null || (pop && !pops)) {
                target = crystal;
                pop = pops;
            }
        }

        if (target == null || !interaction.attack(target, switchMode.get(), swing.get(), true, rotate.get(), interaction.clearAim(target, pos, airplace.get()))) return false;

        CrystalTracker.get().explodeAt(target.getEntityPos());
        if (pop) AutoTotem.notePop();
        return true;
    }

    private int blockSlot(boolean roof) {
        if (roof) {
            for (Block block : blocks.get()) {
                if (Targets.isBase(block.getDefaultState())) continue;
                int slot = find(block);
                if (slot != -1) return slot;
            }
        }

        for (Block block : blocks.get()) {
            int slot = find(block);
            if (slot != -1) return slot;
        }
        return -1;
    }

    private static int find(Block block) {
        return InventoryManager.get().find(stack -> stack.getItem() instanceof BlockItem item && item.getBlock() == block, 0, InventoryManager.MAIN_END);
    }

    private boolean isBlock(ItemStack stack) {
        return stack.getItem() instanceof BlockItem item && blocks.get().contains(item.getBlock());
    }

    private boolean canAct() {
        return mc.player != null && mc.world != null && mc.interactionManager != null && mc.getNetworkHandler() != null
            && mc.player.isAlive() && !mc.player.isSpectator();
    }
}
