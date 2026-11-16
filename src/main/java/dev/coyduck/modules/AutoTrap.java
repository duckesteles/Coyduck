package dev.coyduck.modules;

import dev.coyduck.events.PacketApplyEvent;
import dev.coyduck.events.RenderWorldEvent;
import dev.coyduck.events.TickEvent;
import dev.coyduck.events.WorldResetEvent;
import dev.coyduck.feature.BlockListSetting;
import dev.coyduck.feature.BoolSetting;
import dev.coyduck.feature.ColorSetting;
import dev.coyduck.feature.DoubleSetting;
import dev.coyduck.feature.EnumSetting;
import dev.coyduck.feature.Feature;
import dev.coyduck.feature.Features;
import dev.coyduck.feature.IntSetting;
import dev.coyduck.feature.Rgba;
import dev.coyduck.feature.Setting;
import dev.coyduck.feature.SettingGroup;
import dev.coyduck.managers.CrystalTracker;
import dev.coyduck.managers.InteractionManager;
import dev.coyduck.managers.InventoryManager;
import dev.coyduck.managers.MiningManager;
import dev.coyduck.render.DuckRender;
import dev.coyduck.render.Shape;
import dev.coyduck.utils.Targets;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongSet;
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
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;

import java.util.ArrayList;
import java.util.List;

public class AutoTrap extends Feature {
    public enum Face {
        Smart,
        Always,
        Off
    }

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
        .description("Blocks to trap with, in order of preference.")
        .defaultValue(Blocks.OBSIDIAN, Blocks.CRYING_OBSIDIAN)
        .filter(block -> block.getBlastResistance() >= 600)
        .build()
    );

    private final Setting<Boolean> top = sgGeneral.add(new BoolSetting.Builder()
        .name("top")
        .description("Covers the block above the target's head.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Face> face = sgGeneral.add(new EnumSetting.Builder<Face>()
        .name("face")
        .description("Smart covers only escape routes, Always the whole ring.")
        .defaultValue(Face.Smart)
        .build()
    );

    private final Setting<Boolean> onlyStill = sgGeneral.add(new BoolSetting.Builder()
        .name("only-still")
        .description("Only traps targets in a hole or standing still.")
        .defaultValue(true)
        .build()
    );

    private final Setting<InventoryManager.SwitchMode> switchMode = sgGeneral.add(new EnumSetting.Builder<InventoryManager.SwitchMode>()
        .name("switch")
        .description("Silent uses the hotbar, InvSwap uses inventory clicks.")
        .defaultValue(InventoryManager.SwitchMode.Silent)
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
        .description("Most blocks placed in one tick.")
        .defaultValue(6)
        .range(1, 20)
        .sliderRange(1, 20)
        .build()
    );

    private final Setting<Boolean> rotate = sgGeneral.add(new BoolSetting.Builder()
        .name("rotate")
        .description("Looks at each block before placing. Slower.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> look = sgGeneral.add(new BoolSetting.Builder()
        .name("look")
        .description("Sends a look at each block right before placing it.")
        .defaultValue(true)
        .visible(() -> !rotate.get())
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

    private final Setting<Shape> shapeMode = sgRender.add(new EnumSetting.Builder<Shape>()
        .name("shape-mode")
        .description("How the highlight is drawn.")
        .defaultValue(Shape.Both)
        .visible(render::get)
        .build()
    );

    private final ColorSetting sideColor = sgRender.add(new ColorSetting.Builder()
        .name("side-color")
        .description("Highlight side color.")
        .defaultValue(new Rgba(110, 45, 200, 40))
        .visible(render::get)
        .build()
    );

    private final ColorSetting lineColor = sgRender.add(new ColorSetting.Builder()
        .name("line-color")
        .description("Highlight line color.")
        .defaultValue(new Rgba(110, 45, 200, 200))
        .visible(render::get)
        .build()
    );

    private final Long2LongOpenHashMap rendered = new Long2LongOpenHashMap();
    private PlayerEntity target;
    private int budget;
    private boolean running;
    private boolean entityBlocked;

    public AutoTrap() {
        super("auto-trap+", "Traps enemies in obsidian.");
    }

    @Override
    public void onActivate() {
        displace("it places over the same blocks.", "auto-trap");

        reset();
        budget = blocksPerTick.get();
    }

    @Override
    public void onDeactivate() {
        reset();
    }

    @EventHandler
    private void onWorldReset(WorldResetEvent event) {
        reset();
    }

    private void reset() {
        rendered.clear();
        target = null;
        entityBlocked = false;
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
            case ChunkDeltaUpdateS2CPacket packet -> Targets.touches(packet, target.getBlockPos(), 3);
            case EntitiesDestroyS2CPacket ignored -> entityBlocked;
            default -> false;
        };

        if (relevant) pass();
    }

    @EventHandler
    private void onRender(RenderWorldEvent event) {
        if (!render.get()) return;

        long now = Util.getMeasuringTimeMs();
        rendered.long2LongEntrySet().removeIf(entry -> now - entry.getLongValue() > DuckRender.LIFETIME);
        for (var entry : rendered.long2LongEntrySet()) {
            DuckRender.box(event.canvas, new Box(BlockPos.fromLong(entry.getLongKey())), sideColor.argb(), lineColor.argb(), shapeMode.get(), DuckRender.alpha(now - entry.getLongValue()));
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
        if (InventoryManager.get().isOffhandEating()) return;
        if (target != null && target.getEntityWorld() != mc.world) target = null;
        AutoCrystal autoCrystal = Features.get(AutoCrystal.class);
        PlayerEntity focus = autoCrystal == null ? null : autoCrystal.target();
        target = Targets.find(targetRange.get(), Targets.valid(focus, targetRange.get()) ? focus : target);
        if (target == null || budget <= 0) return;

        Box box = Targets.box(target);
        LongSet columns = Targets.columns(box);
        if (onlyStill.get() && !holed(columns) && !Targets.isStill(target, STILL_SPEED)) return;

        entityBlocked = false;
        List<BlockPos> cells = positions(box, columns);
        InteractionManager interaction = InteractionManager.get();
        List<BlockPos> ready = new ArrayList<>();
        List<BlockHitResult> hits = new ArrayList<>();
        for (int pass = 0; pass < 2; pass++) {
            for (BlockPos pos : cells) {
                if (interaction.wasRejected(pos) != (pass == 1)) continue;
                BlockHitResult hit = hitFor(pos);
                if (hit == null) continue;
                ready.add(pos);
                hits.add(hit);
            }
        }
        if (ready.isEmpty()) return;

        int slot = blockSlot();
        if (slot == -1) return;
        if (!interaction.ready(ready.getFirst(), airplace.get(), rotate.get(), InteractionManager.TRAP_PRIORITY)) return;

        InventoryManager.get().withItem(slot, switchMode.get(), () -> {
            boolean placed = false;
            for (int i = 0; i < ready.size(); i++) {
                if (budget <= 0 || !isBlock()) break;
                placed |= place(ready.get(i), placed ? null : hits.get(i));
            }
            if (!placed || airplace.get()) return;
            for (BlockPos pos : cells) {
                if (budget <= 0 || !isBlock()) break;
                place(pos, null);
            }
        });
    }

    private boolean place(BlockPos pos, BlockHitResult known) {
        InteractionManager interaction = InteractionManager.get();
        if (!placeable(pos)) return false;

        BlockHitResult hit = known != null ? known : hitFor(pos);
        if (hit == null) return false;
        if (!interaction.seen(hit)) {
            InteractionManager.Helper helper = interaction.helper(pos, cell -> cell.getY() >= pos.getY() && placeable(cell));
            if (helper != null && interaction.place(helper.cell(), helper.hit(), swing.get(), rotate.get(), look.get(), InteractionManager.TRAP_PRIORITY)) {
                rendered.put(helper.cell().asLong(), Util.getMeasuringTimeMs());
                if (--budget <= 0) return true;
                hit = hitFor(pos);
                if (hit == null) return true;
            }
        }

        if (!interaction.place(pos, hit, swing.get(), rotate.get(), look.get(), InteractionManager.TRAP_PRIORITY)) {
            budget = 0;
            return false;
        }
        rendered.put(pos.asLong(), Util.getMeasuringTimeMs());
        budget--;
        return true;
    }

    private BlockHitResult hitFor(BlockPos pos) {
        InteractionManager interaction = InteractionManager.get();
        BlockHitResult hit = interaction.hit(pos, airplace.get());
        if (hit == null || !hit.getBlockPos().equals(pos) || !interaction.wasRejected(pos)) return hit;
        return interaction.hit(pos, false);
    }

    private List<BlockPos> positions(Box box, LongSet columns) {
        List<BlockPos> positions = new ArrayList<>();
        int height = Math.max(1, MathHelper.ceil(box.getLengthY() - 1.0E-3));
        List<BlockPos> ownFace = Targets.ring(Targets.columns(mc.player.getBoundingBox()), 1);

        if (top.get()) {
            int level = BlockPos.fromLong(columns.iterator().nextLong()).getY();
            int topY = target.isOnGround() ? MathHelper.floor(box.maxY - 1.0E-4) + 1 : level + height;
            for (long key : columns) {
                BlockPos column = BlockPos.fromLong(key);
                BlockPos cell = new BlockPos(column.getX(), topY, column.getZ());
                if (!crystalOn(cell)) add(positions, cell, ownFace);
            }
        }

        if (face.get() == Face.Off || height < 2) return positions;

        List<BlockPos> ring = Targets.ring(columns, 0);
        int open = 0;
        for (BlockPos cell : ring) {
            if (walkable(cell)) open++;
        }

        for (BlockPos cell : ring) {
            if (face.get() == Face.Smart && !escape(cell, open)) continue;
            add(positions, cell.up(), ownFace);
        }

        return positions;
    }

    private boolean walkable(BlockPos cell) {
        return mc.world.getBlockState(cell).getCollisionShape(mc.world, cell).isEmpty();
    }

    private boolean holed(LongSet columns) {
        for (BlockPos cell : Targets.ring(columns, 0)) {
            if (walkable(cell)) return false;
        }
        return true;
    }

    private boolean crystalOn(BlockPos cell) {
        return !mc.world.getEntitiesByClass(EndCrystalEntity.class, new Box(cell.up()), CrystalTracker.get()::isAlive).isEmpty();
    }

    private boolean escape(BlockPos cell, int open) {
        if (!walkable(cell)) return false;
        if (MiningManager.get().isMining(cell) || cell.equals(MiningManager.get().background())) return false;
        if (open > 1) return true;

        BlockPos base = cell.down();
        if (!Targets.isBase(mc.world.getBlockState(base))) return true;

        CrystalTracker tracker = CrystalTracker.get();
        if (tracker.isPlacing(base) || !mc.world.getEntitiesByClass(EndCrystalEntity.class, new Box(cell), tracker::isAlive).isEmpty()) return false;

        AutoCrystal autoCrystal = Features.get(AutoCrystal.class);
        return autoCrystal == null || target == null || !autoCrystal.wouldFillGap(cell, target);
    }

    private void add(List<BlockPos> positions, BlockPos pos, List<BlockPos> ownFace) {
        MiningManager mining = MiningManager.get();
        if (ownFace.contains(pos) || mining.isMining(pos) || pos.equals(mining.background())) return;
        if (placeable(pos) && !positions.contains(pos)) positions.add(pos);
    }

    private boolean placeable(BlockPos pos) {
        InteractionManager interaction = InteractionManager.get();
        if (!interaction.canPlace(pos)) return false;

        CrystalTracker tracker = CrystalTracker.get();
        if (mc.player.getBoundingBox().intersects(new Box(pos))) return false;

        List<Entity> entities = interaction.obstructions(pos);
        entities.removeIf(entity -> entity instanceof EndCrystalEntity && tracker.isDead(entity));
        if (entities.isEmpty()) return true;
        entityBlocked = true;
        return false;
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
