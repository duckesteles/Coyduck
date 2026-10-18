package dev.coyduck.modules;

import dev.coyduck.Coyduck;
import dev.coyduck.events.PacketApplyEvent;
import dev.coyduck.managers.CrystalTracker;
import dev.coyduck.managers.InteractionManager;
import dev.coyduck.managers.InventoryManager;
import dev.coyduck.managers.MiningTracker;
import dev.coyduck.utils.ExplosionDamage;
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
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.s2c.play.BlockBreakingProgressS2CPacket;
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

public class Surround extends Module {
    public enum Disable {
        Never,
        Leave
    }

    private static final long HOT_TIME = 2_000;
    private static final long RENDER_TIME = 500;
    private static final double EDGE = 1.0E-4;
    private static final double NEAR = 5 * 5;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgProtect = settings.createGroup("Protect");
    private final SettingGroup sgRender = settings.createGroup("Render");

    private final Setting<List<Block>> blocks = sgGeneral.add(new BlockListSetting.Builder()
        .name("blocks")
        .description("Blocks to surround with, in order of preference.")
        .defaultValue(Blocks.OBSIDIAN, Blocks.CRYING_OBSIDIAN, Blocks.ENDER_CHEST)
        .filter(block -> block.getBlastResistance() >= 600)
        .build()
    );

    private final Setting<InventoryManager.SwitchMode> switchMode = sgGeneral.add(new EnumSetting.Builder<InventoryManager.SwitchMode>()
        .name("switch")
        .description("Silent changes your held slot for the placement. InvSwap swaps the block into your hand and back, so it never stops you eating.")
        .defaultValue(InventoryManager.SwitchMode.InvSwap)
        .build()
    );

    private final Setting<Boolean> airplace = sgGeneral.add(new BoolSetting.Builder()
        .name("airplace")
        .description("Places blocks without a neighbouring block to click on.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> blocksPerTick = sgGeneral.add(new IntSetting.Builder()
        .name("blocks-per-tick")
        .description("Most blocks placed in one tick, including reactions between ticks.")
        .defaultValue(10)
        .range(1, 30)
        .sliderRange(1, 30)
        .build()
    );

    private final Setting<Boolean> floor = sgGeneral.add(new BoolSetting.Builder()
        .name("floor")
        .description("Fills the blocks under your feet.")
        .defaultValue(true)
        .build()
    );

    private final Setting<InteractionManager.SwingMode> swing = sgGeneral.add(new EnumSetting.Builder<InteractionManager.SwingMode>()
        .name("swing")
        .description("How the hand swing is shown.")
        .defaultValue(InteractionManager.SwingMode.Packet)
        .build()
    );

    private final Setting<Disable> disable = sgGeneral.add(new EnumSetting.Builder<Disable>()
        .name("disable")
        .description("Leave turns the module off once you walk out of the hole. Jumping and knockback never do.")
        .defaultValue(Disable.Leave)
        .build()
    );

    private final Setting<Boolean> breakCrystals = sgProtect.add(new BoolSetting.Builder()
        .name("break-crystals")
        .description("Breaks crystals that sit where a surround block belongs, then places the block in the same tick.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> maxSelfDamage = sgProtect.add(new DoubleSetting.Builder()
        .name("max-self-damage")
        .description("Most damage a crystal break may deal to you.")
        .defaultValue(10)
        .range(0, 40)
        .sliderRange(0, 40)
        .visible(breakCrystals::get)
        .build()
    );

    private final Setting<Boolean> allowPop = sgProtect.add(new BoolSetting.Builder()
        .name("allow-pop")
        .description("Breaks a crystal in your surround even when it pops you, as long as your offhand holds a totem. The enemy would break it anyway.")
        .defaultValue(true)
        .visible(breakCrystals::get)
        .build()
    );

    private final Setting<Boolean> blocker = sgProtect.add(new BoolSetting.Builder()
        .name("blocker")
        .description("Covers a surround block that is being mined or was just broken.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> blockerTop = sgProtect.add(new BoolSetting.Builder()
        .name("blocker-top")
        .description("Also covers the top of that block, so no crystal can be placed on it.")
        .defaultValue(true)
        .visible(blocker::get)
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
        .defaultValue(new SettingColor(251, 192, 45, 40))
        .visible(render::get)
        .build()
    );

    private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder()
        .name("line-color")
        .description("Highlight line color.")
        .defaultValue(new SettingColor(251, 192, 45, 200))
        .visible(render::get)
        .build()
    );

    private record Side(BlockPos pos, Direction outward) {}

    private final Long2LongOpenHashMap rendered = new Long2LongOpenHashMap();
    private LongSet anchored = new LongOpenHashSet();
    private int anchorY;
    private int budget;
    private boolean running;

    public Surround() {
        super(Coyduck.CATEGORY, "surround+", "Surrounds your feet with obsidian and keeps it up against any break.");
    }

    @Override
    public void onActivate() {
        Module meteor = Modules.get().get(meteordevelopment.meteorclient.systems.modules.combat.Surround.class);
        if (meteor != null && meteor.isActive()) {
            meteor.toggle();
            info("Disabled %s, it places over the same blocks.", meteor.title);
        }

        rendered.clear();
        budget = blocksPerTick.get();

        if (!canAct()) return;
        anchorY = level();
        anchored = columns(anchorY);
        pass();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        budget = blocksPerTick.get();
        pass();
    }

    @EventHandler
    private void onPacket(PacketApplyEvent.Post event) {
        if (!canAct()) return;

        boolean relevant = switch (event.packet) {
            case BlockUpdateS2CPacket packet -> near(packet.getPos());
            case BlockBreakingProgressS2CPacket packet -> near(packet.getPos());
            case ChunkDeltaUpdateS2CPacket ignored -> true;
            case EntitiesDestroyS2CPacket ignored -> true;
            case ExplosionS2CPacket ignored -> true;
            case PlayerActionResponseS2CPacket ignored -> true;
            default -> false;
        };

        if (relevant) pass();
    }

    @EventHandler
    private void onEntityAdded(EntityAddedEvent event) {
        if (event.entity instanceof EndCrystalEntity && canAct() && near(event.entity.getBlockPos())) pass();
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (!render.get()) return;

        long now = Util.getMeasuringTimeMs();
        rendered.long2LongEntrySet().removeIf(entry -> now - entry.getLongValue() > RENDER_TIME);
        for (long key : rendered.keySet()) {
            event.renderer.box(BlockPos.fromLong(key), sideColor.get(), lineColor.get(), shapeMode.get(), 0);
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
        if (!updateAnchor() || budget <= 0) return;

        InteractionManager interaction = InteractionManager.get();
        List<BlockPos> placeable = new ArrayList<>();

        for (BlockPos pos : targets()) {
            if (!interaction.canPlace(pos)) continue;
            if (airplace.get() && interaction.hit(pos, true) == null) continue;
            if (!clear(pos)) continue;
            placeable.add(pos);
        }

        if (placeable.isEmpty()) return;

        int slot = blockSlot();
        if (slot == -1) return;

        InventoryManager.get().withItem(slot, switchMode.get(), () -> {
            for (BlockPos pos : placeable) {
                if (budget <= 0 || !isBlock(mc.player.getMainHandStack())) break;
                place(pos);
            }
        });
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
        InteractionManager.get().place(pos, hit, swing.get());
        rendered.put(pos.asLong(), Util.getMeasuringTimeMs());
        budget--;
    }

    private boolean clear(BlockPos pos) {
        InteractionManager interaction = InteractionManager.get();
        List<Entity> entities = interaction.obstructions(pos);
        entities.removeIf(entity -> entity instanceof EndCrystalEntity && CrystalTracker.get().isDead(entity));
        if (entities.isEmpty()) return true;
        if (!breakCrystals.get()) return false;

        for (Entity entity : entities) {
            if (!(entity instanceof EndCrystalEntity crystal) || !safe(crystal)) return false;
        }

        for (Entity entity : entities) {
            if (interaction.recentlyAttacked(entity)) continue;
            if (!interaction.attack(entity, switchMode.get(), swing.get())) return false;
        }

        return true;
    }

    private boolean safe(EndCrystalEntity crystal) {
        float damage = ExplosionDamage.crystal(mc.player, crystal.getEntityPos());
        float health = mc.player.getHealth() + mc.player.getAbsorptionAmount();
        if (damage < health && damage <= maxSelfDamage.get()) return true;
        return allowPop.get() && mc.player.getOffHandStack().contains(DataComponentTypes.DEATH_PROTECTION);
    }

    private List<BlockPos> targets() {
        LongSet ring = new LongOpenHashSet();
        List<BlockPos> threatened = new ArrayList<>();
        List<Side> threatenedSides = new ArrayList<>();
        List<BlockPos> floors = new ArrayList<>();
        List<BlockPos> sides = new ArrayList<>();

        for (long key : anchored) {
            BlockPos column = BlockPos.fromLong(key);

            if (floor.get()) {
                BlockPos below = column.down();
                (isThreatened(below) ? threatened : floors).add(below);
            }

            for (Direction direction : Direction.Type.HORIZONTAL) {
                BlockPos side = column.offset(direction);
                if (anchored.contains(side.asLong()) || !ring.add(side.asLong())) continue;

                if (isThreatened(side)) {
                    threatened.add(side);
                    threatenedSides.add(new Side(side, direction));
                } else {
                    sides.add(side);
                }
            }
        }

        List<BlockPos> blockers = new ArrayList<>();
        if (blocker.get()) {
            for (Side side : threatenedSides) {
                addBlocker(blockers, ring, side.pos.offset(side.outward));
                addBlocker(blockers, ring, side.pos.offset(side.outward.rotateYClockwise()));
                addBlocker(blockers, ring, side.pos.offset(side.outward.rotateYCounterclockwise()));
                if (blockerTop.get()) addBlocker(blockers, ring, side.pos.up());
            }
        }

        List<BlockPos> targets = new ArrayList<>(threatened.size() + floors.size() + sides.size() + blockers.size());
        targets.addAll(threatened);
        targets.addAll(floors);
        targets.addAll(sides);
        targets.addAll(blockers);
        return targets;
    }

    private void addBlocker(List<BlockPos> blockers, LongSet ring, BlockPos pos) {
        long key = pos.asLong();
        if (anchored.contains(key) || ring.contains(key) || blockers.contains(pos)) return;
        if (mc.player.getBoundingBox().intersects(new Box(pos))) return;
        blockers.add(pos);
    }

    private boolean isThreatened(BlockPos pos) {
        MiningTracker mining = MiningTracker.get();
        return mining.stage(pos) >= 0 || mining.wasBroken(pos, HOT_TIME);
    }

    private boolean updateAnchor() {
        int level = level();
        if (level < anchorY || (mc.player.isOnGround() && level > anchorY)) anchorY = level;

        LongSet current = columns(anchorY);
        if (disable.get() == Disable.Leave && current.longStream().noneMatch(anchored::contains)) {
            toggle();
            return false;
        }

        anchored = current;
        return true;
    }

    private LongSet columns(int y) {
        Box box = mc.player.getBoundingBox();
        LongSet columns = new LongOpenHashSet();

        int minX = MathHelper.floor(box.minX + EDGE);
        int maxX = MathHelper.floor(box.maxX - EDGE);
        int minZ = MathHelper.floor(box.minZ + EDGE);
        int maxZ = MathHelper.floor(box.maxZ - EDGE);

        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                columns.add(BlockPos.asLong(x, y, z));
            }
        }

        return columns;
    }

    private int level() {
        return MathHelper.floor(mc.player.getY() + 0.4);
    }

    private boolean near(BlockPos pos) {
        return mc.player.getBlockPos().getSquaredDistance(pos) <= NEAR;
    }

    private int blockSlot() {
        for (Block block : blocks.get()) {
            int slot = InventoryManager.get().find(stack -> stack.getItem() instanceof BlockItem item && item.getBlock() == block, 0, InventoryManager.MAIN_END);
            if (slot != -1) return slot;
        }
        return -1;
    }

    private boolean isBlock(ItemStack stack) {
        return !stack.isEmpty() && stack.getItem() instanceof BlockItem item && blocks.get().contains(item.getBlock());
    }

    private boolean canAct() {
        return mc.player != null && mc.world != null && mc.interactionManager != null && mc.getNetworkHandler() != null
            && mc.player.isAlive() && !mc.player.isSpectator();
    }
}
