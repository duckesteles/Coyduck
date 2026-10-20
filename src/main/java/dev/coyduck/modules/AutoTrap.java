package dev.coyduck.modules;

import dev.coyduck.Coyduck;
import dev.coyduck.events.PacketApplyEvent;
import dev.coyduck.managers.CrystalTracker;
import dev.coyduck.managers.InteractionManager;
import dev.coyduck.managers.InventoryManager;
import dev.coyduck.utils.Targets;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongSet;
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
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ChunkDeltaUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.EntitiesDestroyS2CPacket;
import net.minecraft.util.Util;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Box;

import java.util.ArrayList;
import java.util.List;

public class AutoTrap extends Module {
    public enum Face {
        Smart,
        Always,
        Off
    }

    private static final long RENDER_TIME = 500;
    private static final double STILL_SPEED = 0.05;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");

    private final Setting<Double> targetRange = sgGeneral.add(new DoubleSetting.Builder()
        .name("target-range")
        .description("Range to look for targets in.")
        .defaultValue(6)
        .range(1, 12)
        .sliderRange(1, 12)
        .build()
    );

    private final Setting<List<Block>> blocks = sgGeneral.add(new BlockListSetting.Builder()
        .name("blocks")
        .description("Blocks to trap with, in order of preference. Obsidian on top also lets Cev Breaker use it.")
        .defaultValue(Blocks.OBSIDIAN, Blocks.CRYING_OBSIDIAN)
        .filter(block -> block.getBlastResistance() >= 600)
        .build()
    );

    private final Setting<Boolean> top = sgGeneral.add(new BoolSetting.Builder()
        .name("top")
        .description("Covers the block above the target's head so they cannot jump out.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Face> face = sgGeneral.add(new EnumSetting.Builder<Face>()
        .name("face")
        .description("Smart only covers escape routes: nothing for a target in a full hole, the head-level block over a gap a crystal cannot fill, and every open side in the open. Always covers the whole head ring.")
        .defaultValue(Face.Smart)
        .build()
    );

    private final Setting<Boolean> onlyStill = sgGeneral.add(new BoolSetting.Builder()
        .name("only-still")
        .description("Only traps targets that are in a hole or standing still, so no blocks are wasted on someone running past.")
        .defaultValue(true)
        .build()
    );

    private final Setting<InventoryManager.SwitchMode> switchMode = sgGeneral.add(new EnumSetting.Builder<InventoryManager.SwitchMode>()
        .name("switch")
        .description("How blocks are put in your hand.")
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
        .defaultValue(6)
        .range(1, 20)
        .sliderRange(1, 20)
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
    private PlayerEntity target;
    private int budget;
    private boolean running;

    public AutoTrap() {
        super(Coyduck.CATEGORY, "auto-trap+", "Traps the target from above and around the head without closing your own crystal spots.");
    }

    @Override
    public void onActivate() {
        Module meteor = Modules.get().get(meteordevelopment.meteorclient.systems.modules.combat.AutoTrap.class);
        if (meteor != null && meteor.isActive()) {
            meteor.toggle();
            info("Disabled %s, it places over the same blocks.", meteor.title);
        }

        rendered.clear();
        target = null;
        budget = blocksPerTick.get();
    }

    @Override
    public String getInfoString() {
        return target == null ? null : target.getName().getString();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        budget = blocksPerTick.get();
        pass();
    }

    @EventHandler
    private void onPacket(PacketApplyEvent.Post event) {
        if (target == null) return;

        boolean relevant = switch (event.packet) {
            case BlockUpdateS2CPacket packet -> packet.getPos().isWithinDistance(target.getBlockPos(), 3);
            case ChunkDeltaUpdateS2CPacket ignored -> true;
            case EntitiesDestroyS2CPacket ignored -> true;
            default -> false;
        };

        if (relevant) pass();
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
        target = Targets.find(targetRange.get());
        if (target == null || budget <= 0) return;

        Box box = Targets.box(target);
        LongSet columns = Targets.columns(box);
        if (onlyStill.get() && !Targets.isHoled(columns) && !Targets.isStill(target, STILL_SPEED)) return;

        List<BlockPos> targets = positions(box, columns);
        InteractionManager interaction = InteractionManager.get();
        if (targets.stream().noneMatch(pos -> interaction.hit(pos, airplace.get()) != null)) return;

        int slot = blockSlot();
        if (slot == -1) return;

        InventoryManager.get().withItem(slot, switchMode.get(), () -> {
            for (int round = 0; round < 2 && budget > 0; round++) {
                boolean placed = false;
                for (BlockPos pos : targets) {
                    if (budget <= 0 || !isBlock()) break;
                    placed |= place(pos);
                }
                if (!placed || airplace.get()) break;
            }
        });
    }

    private boolean place(BlockPos pos) {
        InteractionManager interaction = InteractionManager.get();
        if (!placeable(pos)) return false;

        BlockHitResult hit = interaction.hit(pos, airplace.get());
        if (hit == null) return false;

        interaction.place(pos, hit, swing.get());
        rendered.put(pos.asLong(), Util.getMeasuringTimeMs());
        budget--;
        return true;
    }

    private List<BlockPos> positions(Box box, LongSet columns) {
        List<BlockPos> positions = new ArrayList<>();
        int height = Math.max(1, MathHelper.ceil(box.getLengthY() - 1.0E-3));

        if (top.get()) {
            int topY = MathHelper.floor(box.maxY - 1.0E-4) + 1;
            for (long key : columns) {
                BlockPos column = BlockPos.fromLong(key);
                add(positions, new BlockPos(column.getX(), topY, column.getZ()));
            }
        }

        if (face.get() == Face.Off || height < 2) return positions;

        List<BlockPos> ring = Targets.ring(columns, 0);
        int open = 0;
        for (BlockPos cell : ring) {
            if (mc.world.getBlockState(cell).isReplaceable()) open++;
        }

        for (BlockPos cell : ring) {
            if (face.get() == Face.Smart && !escape(cell, open)) continue;
            add(positions, cell.up());
        }

        return positions;
    }

    private boolean escape(BlockPos cell, int open) {
        if (!mc.world.getBlockState(cell).isReplaceable()) return false;
        if (open > 1) return true;

        BlockPos base = cell.down();
        if (!Targets.isBase(mc.world.getBlockState(base))) return true;

        AutoCrystal autoCrystal = Modules.get().get(AutoCrystal.class);
        if (autoCrystal == null || !autoCrystal.fillsGaps() || !mc.world.getBlockState(cell).isAir()) return true;

        double range = autoCrystal.placeRange();
        return new Box(base).squaredMagnitude(mc.player.getEyePos()) > range * range;
    }

    private void add(List<BlockPos> positions, BlockPos pos) {
        if (placeable(pos) && !positions.contains(pos)) positions.add(pos);
    }

    private boolean placeable(BlockPos pos) {
        InteractionManager interaction = InteractionManager.get();
        if (!interaction.canPlace(pos)) return false;

        CrystalTracker tracker = CrystalTracker.get();
        if (mc.player.getBoundingBox().intersects(new Box(pos))) return false;

        List<Entity> entities = interaction.obstructions(pos);
        entities.removeIf(entity -> entity instanceof EndCrystalEntity && tracker.isDead(entity));
        return entities.isEmpty();
    }

    private int blockSlot() {
        for (Block block : blocks.get()) {
            int slot = InventoryManager.get().find(stack -> stack.getItem() instanceof BlockItem item && item.getBlock() == block, 0, InventoryManager.MAIN_END);
            if (slot != -1) return slot;
        }
        return -1;
    }

    private boolean isBlock() {
        return mc.player.getMainHandStack().getItem() instanceof BlockItem item && blocks.get().contains(item.getBlock());
    }

    private boolean canAct() {
        return mc.player != null && mc.world != null && mc.interactionManager != null && mc.getNetworkHandler() != null
            && mc.player.isAlive() && !mc.player.isSpectator();
    }
}
