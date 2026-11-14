package dev.coyduck.modules;

import dev.coyduck.Coyduck;
import dev.coyduck.events.PacketApplyEvent;
import dev.coyduck.events.WorldResetEvent;
import dev.coyduck.managers.CrystalTracker;
import dev.coyduck.managers.HurtTracker;
import dev.coyduck.managers.InteractionManager;
import dev.coyduck.managers.InventoryManager;
import dev.coyduck.managers.LatencyManager;
import dev.coyduck.managers.MiningManager;
import dev.coyduck.utils.DuckRender;
import dev.coyduck.utils.ExplosionDamage;
import dev.coyduck.utils.Targets;
import dev.coyduck.utils.Trace;
import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongSet;
import meteordevelopment.meteorclient.events.entity.EntityAddedEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.BlockListSetting;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.AbstractRedstoneGateBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ComparatorBlock;
import net.minecraft.block.PistonBlock;
import net.minecraft.block.RedstoneWireBlock;
import net.minecraft.block.entity.PistonBlockEntity;
import net.minecraft.block.piston.PistonBehavior;
import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.s2c.play.BlockEventS2CPacket;
import net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ChunkDeltaUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.EntitiesDestroyS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerActionResponseS2CPacket;
import net.minecraft.util.Util;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class AntiPiston extends Module {
    private static final float POP_MARGIN = 1.5f;
    private static final float LOW_HEALTH = 12;
    private static final float CHEAP_BREAK = 6;
    private static final int QUICK_MINE_TICKS = 2;
    private static final int MINE_PRIORITY = 60;
    private static final int MINE_HOLD_TICKS = 10;
    private static final int MAX_PUSH = 12;
    private static final int BELOW = 2;
    private static final int ABOVE = 4;
    private static final double SHRINK = 1.0E-3;
    private static final double PUSH_REACH = 1.5;
    private static final double PACKET_REACH = 3;
    private static final float AIR_PENALTY = 5.0f;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");

    private final Setting<Integer> range = sgGeneral.add(new IntSetting.Builder()
        .name("range")
        .description("How many blocks out from your hole pistons are looked for.")
        .defaultValue(3)
        .range(2, 5)
        .sliderRange(2, 5)
        .build()
    );

    private final Setting<Boolean> breakCrystals = sgGeneral.add(new BoolSetting.Builder()
        .name("break-crystals")
        .description("Breaks a crystal a piston is about to push into you, judged where the push will leave it, as long as the break does not pop you. While the piston is not powered yet and can be mined quickly, only breaks it when that costs little, since mining the piston is free.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> popIfPushed = sgGeneral.add(new BoolSetting.Builder()
        .name("pop-if-pushed")
        .description("Also breaks such a crystal when it pops you, but only if the piston is already pushing, the pushed crystal would pop you anyway and Auto Totem+ has confirmed your offhand totem with a spare left.")
        .defaultValue(true)
        .visible(breakCrystals::get)
        .build()
    );

    private final Setting<Boolean> mine = sgGeneral.add(new BoolSetting.Builder()
        .name("mine")
        .description("Mines the piston or the redstone powering it, whichever breaks sooner, once it really threatens you: a crystal in front of it, power on it, a push that can move you, or a web in the way of blocking it. Takes the pick over from lower priority mining such as Auto City+ while it does.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> place = sgGeneral.add(new BoolSetting.Builder()
        .name("place")
        .description("Places a block where the piston would push, and where a crystal it broke sat, so the push cannot happen.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> preBlock = sgGeneral.add(new BoolSetting.Builder()
        .name("pre-block")
        .description("Also blocks pistons that are not doing anything yet but aim at a spot a crystal could be pushed from into you. One block ends the setup before it starts; off saves blocks and only answers once a crystal, power or a push shows up.")
        .defaultValue(true)
        .visible(place::get)
        .build()
    );

    private final Setting<List<Block>> blocks = sgGeneral.add(new BlockListSetting.Builder()
        .name("blocks")
        .description("Blocks to place, in order of preference. Only blocks that pistons cannot move and crystals cannot blow up are allowed.")
        .defaultValue(Blocks.CRYING_OBSIDIAN, Blocks.OBSIDIAN, Blocks.ENDER_CHEST)
        .filter(block -> block.getBlastResistance() >= 600 && immovable(block))
        .visible(place::get)
        .build()
    );

    private final Setting<Integer> blocksPerTick = sgGeneral.add(new IntSetting.Builder()
        .name("blocks-per-tick")
        .description("Most blocks placed in one tick, packet reactions included. With rotate on, one per tick.")
        .defaultValue(4)
        .range(1, 8)
        .sliderRange(1, 8)
        .visible(place::get)
        .build()
    );

    private final Setting<Boolean> airplace = sgGeneral.add(new BoolSetting.Builder()
        .name("airplace")
        .description("Places without a neighbouring block to click on.")
        .defaultValue(true)
        .visible(place::get)
        .build()
    );

    private final Setting<InventoryManager.SwitchMode> switchMode = sgGeneral.add(new EnumSetting.Builder<InventoryManager.SwitchMode>()
        .name("switch")
        .description("Silent selects the item's hotbar slot for a moment and sends no inventory clicks; an item in your inventory is first moved into an empty hotbar slot once. InvSwap swaps the item into your hand and back with inventory clicks, which some servers answer by resending the whole inventory. While you eat, InvSwap is always used. Your offhand totem is never touched.")
        .defaultValue(InventoryManager.SwitchMode.Silent)
        .build()
    );

    private final Setting<Boolean> rotate = sgGeneral.add(new BoolSetting.Builder()
        .name("rotate")
        .description("Turns your head toward each block or crystal first and acts only once the server sees you looking at it. Slower, but for servers whose anticheat checks where you look.")
        .defaultValue(false)
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
        .description("Highlights threatening pistons and the blocks placed against them.")
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
        .defaultValue(new SettingColor(230, 60, 60, 40))
        .visible(render::get)
        .build()
    );

    private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder()
        .name("line-color")
        .description("Highlight line color.")
        .defaultValue(new SettingColor(230, 60, 60, 200))
        .visible(render::get)
        .build()
    );

    private enum Kind {
        Crystal,
        Body,
        Spot
    }

    private record Threat(BlockPos piston, Direction push, BlockPos head, BlockPos zone, boolean extended, boolean powered, double travel,
                          Kind kind, boolean urgent, List<EndCrystalEntity> crystals) {
        boolean imminent() {
            return extended || powered;
        }

        String describe() {
            return kind + (urgent ? " urgent" : "") + (extended ? " extended" : powered ? " powered" : "");
        }
    }

    private enum Verdict {
        Break,
        BreakPop,
        SkipPops,
        SkipMineFirst
    }

    private final Long2LongOpenHashMap rendered = new Long2LongOpenHashMap();
    private final Int2LongOpenHashMap broken = new Int2LongOpenHashMap();
    private final Long2IntOpenHashMap mineTicksCache = new Long2IntOpenHashMap();
    private final Long2ObjectOpenHashMap<String> seenThreats = new Long2ObjectOpenHashMap<>();
    private final Int2ObjectOpenHashMap<Verdict> seenVerdicts = new Int2ObjectOpenHashMap<>();
    private List<Threat> threats = List.of();
    private long detectedCells = Long.MIN_VALUE;
    private int detectedTick = -1;
    private int tick;
    private int lastUrgent;
    private int budget;
    private boolean dirty;
    private boolean mineBlocked;
    private boolean running;

    public AntiPiston() {
        super(Coyduck.CATEGORY, "anti-piston", "Stops piston crystals and pushes.");
        reset();
    }

    @Override
    public void onActivate() {
        reset();
        budget = blocksPerTick.get();
        pass();
    }

    @Override
    public void onDeactivate() {
        MiningManager.get().release(this);
        reset();
    }

    @EventHandler
    private void onWorldReset(WorldResetEvent event) {
        reset();
    }

    private void reset() {
        rendered.clear();
        broken.clear();
        mineTicksCache.clear();
        seenThreats.clear();
        seenVerdicts.clear();
        threats = List.of();
        detectedCells = Long.MIN_VALUE;
        detectedTick = -1;
        lastUrgent = tick - MINE_HOLD_TICKS - 1;
        dirty = true;
        mineBlocked = false;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        tick++;
        budget = blocksPerTick.get();
        mineTicksCache.clear();
        long now = Util.getMeasuringTimeMs();
        broken.int2LongEntrySet().removeIf(entry -> entry.getLongValue() < now);
        pass();
        mine();
    }

    @EventHandler
    private void onTickPost(TickEvent.Post event) {
        pass();
    }

    @EventHandler
    private void onPacket(PacketApplyEvent.Post event) {
        if (!canAct()) return;

        BlockPos center = mc.player.getBlockPos();
        double near = range.get() + PACKET_REACH;
        switch (event.packet) {
            case BlockUpdateS2CPacket packet when packet.getPos().isWithinDistance(center, near) && relevant(packet.getPos(), packet.getState()) -> refresh();
            case ChunkDeltaUpdateS2CPacket packet -> {
                boolean[] found = {false};
                packet.visitUpdates((pos, state) -> found[0] |= pos.isWithinDistance(center, near) && relevant(pos, state));
                if (found[0]) refresh();
            }
            case BlockEventS2CPacket packet when packet.getBlock() instanceof PistonBlock && packet.getPos().isWithinDistance(center, near) -> refresh();
            case EntitiesDestroyS2CPacket ignored when !threats.isEmpty() -> pass();
            case PlayerActionResponseS2CPacket ignored when !threats.isEmpty() -> pass();
            default -> {}
        }
    }

    @EventHandler
    private void onEntityAdded(EntityAddedEvent event) {
        if (!(event.entity instanceof EndCrystalEntity crystal) || !canAct()) return;
        CrystalTracker tracker = CrystalTracker.get();
        if (tracker.isOwn(crystal) || tracker.isPlacing(crystal.getBlockPos().down())) return;
        if (crystal.getBlockPos().isWithinDistance(mc.player.getBlockPos(), range.get() + PACKET_REACH)) refresh();
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

    private boolean relevant(BlockPos pos, BlockState state) {
        Block block = state.getBlock();
        if (block instanceof PistonBlock || state.isOf(Blocks.PISTON_HEAD) || state.isOf(Blocks.MOVING_PISTON)) return true;
        if (state.isReplaceable() || state.getPistonBehavior() == PistonBehavior.DESTROY || Targets.isBase(state)) return true;
        if (state.emitsRedstonePower() || block instanceof RedstoneWireBlock || block instanceof AbstractRedstoneGateBlock) return true;
        for (Threat threat : threats) {
            if (pos.equals(threat.piston) || pos.equals(threat.zone) || pos.equals(threat.head)) return true;
        }
        return false;
    }

    private void refresh() {
        dirty = true;
        pass();
    }

    private void pass() {
        if (running || !canAct()) return;

        running = true;
        try {
            Box box = mc.player.getBoundingBox();
            long cells = cells(box);
            if (dirty || detectedTick != tick || cells != detectedCells) {
                threats = detect(box);
                dirty = false;
                detectedTick = tick;
                detectedCells = cells;
                note();
            }
            if (threats.isEmpty()) return;

            List<BlockPos> cleared = breakCrystals.get() ? breakAll() : List.of();
            if (place.get() && !HoleSnap.snapping()) placeAll(cleared);
        } finally {
            running = false;
        }
    }

    private static long cells(Box box) {
        long hash = MathHelper.floor(box.minX);
        hash = hash * 31 + MathHelper.floor(box.minY + 0.4);
        hash = hash * 31 + MathHelper.floor(box.minZ);
        hash = hash * 31 + MathHelper.floor(box.maxX);
        return hash * 31 + MathHelper.floor(box.maxZ);
    }

    private void note() {
        long now = Util.getMeasuringTimeMs();
        Long2ObjectOpenHashMap<String> current = new Long2ObjectOpenHashMap<>();
        for (Threat threat : threats) {
            long key = threat.piston.asLong();
            rendered.put(key, now);
            if (threat.urgent) lastUrgent = tick;
            String description = threat.describe();
            current.put(key, description);
            if (Trace.on() && !description.equals(seenThreats.get(key))) Trace.log("anti-piston {} at {} pushing {} into {}", description, threat.piston, threat.push, threat.zone);
        }
        if (Trace.on()) {
            for (long key : seenThreats.keySet()) {
                if (!current.containsKey(key)) Trace.log("anti-piston threat at {} is gone", BlockPos.fromLong(key));
            }
        }
        seenThreats.clear();
        seenThreats.putAll(current);

        IntOpenHashSet alive = new IntOpenHashSet();
        for (Threat threat : threats) {
            for (EndCrystalEntity crystal : threat.crystals) alive.add(crystal.getId());
        }
        seenVerdicts.keySet().removeIf((java.util.function.IntPredicate) id -> !alive.contains(id));
    }

    private List<Threat> detect(Box box) {
        LongSet columns = Targets.columns(box);
        if (columns.isEmpty()) return List.of();

        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE, level = 0;
        for (long key : columns) {
            int x = BlockPos.unpackLongX(key);
            int z = BlockPos.unpackLongZ(key);
            level = BlockPos.unpackLongY(key);
            minX = Math.min(minX, x);
            maxX = Math.max(maxX, x);
            minZ = Math.min(minZ, z);
            maxZ = Math.max(maxZ, z);
        }

        int reach = range.get();
        Box area = new Box(minX - reach - 1, level - BELOW - 1, minZ - reach - 1, maxX + reach + 2, level + ABOVE + 2, maxZ + reach + 2);
        List<EndCrystalEntity> crystals = mc.world.getEntitiesByClass(EndCrystalEntity.class, area, crystal -> CrystalTracker.get().isAlive(crystal));

        List<Threat> found = new ArrayList<>();
        BlockPos.Mutable cursor = new BlockPos.Mutable();
        for (int x = minX - reach; x <= maxX + reach; x++) {
            for (int z = minZ - reach; z <= maxZ + reach; z++) {
                for (int y = level - BELOW; y <= level + ABOVE; y++) {
                    cursor.set(x, y, z);
                    BlockState state = mc.world.getBlockState(cursor);
                    if (!(state.getBlock() instanceof PistonBlock)) continue;

                    Threat threat = threat(cursor.toImmutable(), state, crystals, box);
                    if (threat != null) found.add(threat);
                }
            }
        }

        Vec3d eye = mc.player.getEyePos();
        found.sort(Comparator.<Threat>comparingInt(threat -> threat.kind.ordinal())
            .thenComparing(threat -> !threat.urgent)
            .thenComparing(threat -> !threat.imminent())
            .thenComparingDouble(threat -> Vec3d.ofCenter(threat.zone).squaredDistanceTo(eye)));
        return found;
    }

    private Threat threat(BlockPos piston, BlockState state, List<EndCrystalEntity> crystals, Box box) {
        Direction push = state.get(PistonBlock.FACING);
        boolean extended = state.get(PistonBlock.EXTENDED);
        BlockPos head = piston.offset(push);
        BlockPos zone = extended ? head : zone(head, push);
        if (zone == null) return null;

        boolean powered = !extended && powered(piston, push);
        double travel = extended ? remaining(head) : 1;
        List<EndCrystalEntity> pushed = pushed(crystals, head, zone, push, extended, travel, powered, box);
        if (!pushed.isEmpty()) return new Threat(piston, push, head, zone, extended, powered, travel, Kind.Crystal, true, pushed);

        if (!extended && new Box(zone).contract(SHRINK).intersects(box)) {
            boolean moves = !zone.equals(head) || mc.world.isSpaceEmpty(mc.player, box.offset(push.getOffsetX() * 0.5, push.getOffsetY() * 0.5, push.getOffsetZ() * 0.5));
            return new Threat(piston, push, head, zone, false, powered, travel, Kind.Body, moves, List.of());
        }

        if (extended ? aimed(zone, push, box) : spotNear(zone, push, box)) {
            BlockState inZone = mc.world.getBlockState(zone);
            boolean webbed = !extended && !inZone.isReplaceable() && inZone.getPistonBehavior() == PistonBehavior.DESTROY;
            boolean urgent = powered || webbed || extended && travel > 0;
            return new Threat(piston, push, head, zone, extended, powered, travel, Kind.Spot, urgent, List.of());
        }
        return null;
    }

    private BlockPos zone(BlockPos head, Direction push) {
        BlockPos cell = head;
        for (int moved = 0; moved <= MAX_PUSH; moved++) {
            if (mc.world.isOutOfHeightLimit(cell)) return null;
            BlockState state = mc.world.getBlockState(cell);
            if (state.isReplaceable() || state.getPistonBehavior() == PistonBehavior.DESTROY) return cell;
            if (moved == MAX_PUSH || !movable(state, cell)) return null;
            cell = cell.offset(push);
        }
        return null;
    }

    private double remaining(BlockPos head) {
        if (mc.world.getBlockEntity(head) instanceof PistonBlockEntity piston && piston.isExtending()) {
            return MathHelper.clamp(1 - piston.getProgress(1.0f), 0, 1);
        }
        return 0;
    }

    private boolean movable(BlockState state, BlockPos pos) {
        if (state.isAir() || state.getHardness(mc.world, pos) < 0) return false;
        if (state.getBlock() instanceof PistonBlock) return !state.get(PistonBlock.EXTENDED);
        return !immovable(state.getBlock()) && state.getPistonBehavior() != PistonBehavior.BLOCK && !state.hasBlockEntity();
    }

    private static boolean immovable(Block block) {
        BlockState state = block.getDefaultState();
        return block == Blocks.OBSIDIAN || block == Blocks.CRYING_OBSIDIAN || block == Blocks.RESPAWN_ANCHOR || block == Blocks.REINFORCED_DEEPSLATE
            || block.getHardness() < 0 || state.getPistonBehavior() == PistonBehavior.BLOCK || state.hasBlockEntity();
    }

    private boolean powered(BlockPos piston, Direction face) {
        for (Direction direction : Direction.values()) {
            if (direction != face && emits(piston.offset(direction), direction)) return true;
        }
        if (emits(piston, Direction.DOWN)) return true;

        BlockPos above = piston.up();
        for (Direction direction : Direction.values()) {
            if (direction != Direction.DOWN && emits(above.offset(direction), direction)) return true;
        }
        return false;
    }

    private boolean emits(BlockPos pos, Direction direction) {
        if (mc.world.isEmittingRedstonePower(pos, direction)) return true;
        BlockState state = mc.world.getBlockState(pos);
        return state.getBlock() instanceof ComparatorBlock && state.get(AbstractRedstoneGateBlock.POWERED) && state.get(AbstractRedstoneGateBlock.FACING) == direction;
    }

    private List<EndCrystalEntity> pushed(List<EndCrystalEntity> crystals, BlockPos head, BlockPos zone, Direction push, boolean extended, double travel, boolean powered, Box body) {
        Box sweep = new Box(head).union(new Box(zone)).contract(SHRINK);
        List<EndCrystalEntity> pushed = new ArrayList<>();
        for (EndCrystalEntity crystal : crystals) {
            if (!crystal.getBoundingBox().intersects(sweep)) continue;
            if (CrystalTracker.get().isOwn(crystal) && !extended && !powered) continue;
            Vec3d at = crystal.getEntityPos();
            Vec3d after = at.add(push.getOffsetX() * travel, push.getOffsetY() * travel, push.getOffsetZ() * travel);
            double gap = gap(after, body);
            if (gap <= PUSH_REACH && (extended || gap < gap(at, body))) pushed.add(crystal);
        }
        return pushed;
    }

    private boolean spotNear(BlockPos zone, Direction push, Box body) {
        BlockPos.Mutable cell = new BlockPos.Mutable();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dy = -1; dy <= 0; dy++) {
                    cell.set(zone.getX() + dx, zone.getY() + dy, zone.getZ() + dz);
                    Vec3d at = Vec3d.ofBottomCenter(cell);
                    Vec3d after = at.add(push.getOffsetX(), push.getOffsetY(), push.getOffsetZ());
                    double gap = gap(after, body);
                    if (gap > PUSH_REACH || gap >= gap(at, body)) continue;
                    if (Targets.canHoldCrystal(mc.world, cell.down())) return true;
                }
            }
        }
        return false;
    }

    private static boolean aimed(BlockPos zone, Direction push, Box body) {
        Vec3d after = Vec3d.ofBottomCenter(zone).add(push.getOffsetX(), push.getOffsetY(), push.getOffsetZ());
        return gap(after, body) <= PUSH_REACH;
    }

    private static double gap(Vec3d point, Box box) {
        return Math.sqrt(box.squaredMagnitude(point));
    }

    private List<BlockPos> breakAll() {
        InteractionManager interaction = InteractionManager.get();
        List<BlockPos> cleared = new ArrayList<>();
        IntOpenHashSet handled = new IntOpenHashSet();

        for (Threat threat : threats) {
            if (threat.crystals.isEmpty()) continue;
            boolean blockable = canBlock(threat.zone);
            for (EndCrystalEntity crystal : threat.crystals) {
                if (!handled.add(crystal.getId())) continue;
                if (broken.containsKey(crystal.getId())) {
                    cleared.add(crystal.getBlockPos());
                    continue;
                }
                if (crystal.isRemoved() || CrystalTracker.get().isDead(crystal) || interaction.recentlyAttacked(crystal)) continue;

                Verdict verdict = verdict(crystal, threat);
                if (Trace.on() && seenVerdicts.put(crystal.getId(), verdict) != verdict) Trace.log("anti-piston crystal {} in front of {} {}: {}", crystal.getId(), threat.piston, threat.describe(), verdict);
                if (verdict != Verdict.Break && verdict != Verdict.BreakPop) continue;

                Vec3d aim = blockable ? interaction.clearAim(crystal, threat.zone, airplace.get()) : crystal.getBoundingBox().getCenter();
                if (!interaction.attack(crystal, switchMode.get(), swing.get(), true, rotate.get(), aim)) continue;

                broken.put(crystal.getId(), Util.getMeasuringTimeMs() + LatencyManager.get().timeout());
                seenVerdicts.remove(crystal.getId());
                CrystalTracker.get().explodeAt(crystal.getEntityPos());
                if (verdict == Verdict.BreakPop) AutoTotem.notePop();
                cleared.add(crystal.getBlockPos());
            }
        }
        return cleared;
    }

    private Verdict verdict(EndCrystalEntity crystal, Threat threat) {
        Vec3d position = mc.player.getEntityPos();
        Box box = mc.player.getBoundingBox();
        long arrival = Util.getMeasuringTimeMs() + LatencyManager.get().rtt() / 2;
        float health = AutoTotem.popInFlight() ? 1.0f : mc.player.getHealth() + mc.player.getAbsorptionAmount();
        boolean worst = mc.player.isInsideWall() || health <= LOW_HEALTH;

        Vec3d at = crystal.getEntityPos();
        float[] now = damage(at, position, box, arrival, worst);
        if (!threat.imminent()) {
            if (!fits(now, health)) return Verdict.SkipPops;
            return now[0] <= CHEAP_BREAK || !quickMine(threat) ? Verdict.Break : Verdict.SkipMineFirst;
        }

        double travel = threat.extended ? threat.travel : 1;
        float[] landed = travel <= 0 ? now : damage(at.add(threat.push.getOffsetX() * travel, threat.push.getOffsetY() * travel, threat.push.getOffsetZ() * travel), position, box, arrival, worst);
        if (fits(now, health) && fits(landed, health)) return Verdict.Break;
        if (!popIfPushed.get() || !AutoTotem.canAffordPop() || fits(landed, health)) return Verdict.SkipPops;
        return Verdict.BreakPop;
    }

    private float[] damage(Vec3d crystal, Vec3d position, Box box, long arrival, boolean worst) {
        float raw = ExplosionDamage.crystalRaw(mc.player, position, box, crystal, mc.world);
        float effective = HurtTracker.get().effectiveOnSelf(raw, arrival);
        float full = worst ? ExplosionDamage.upperBound(mc.player, position, crystal) : ExplosionDamage.mitigate(mc.player, raw);
        return new float[] {effective, full};
    }

    private static boolean fits(float[] damage, float health) {
        return damage[0] < health - POP_MARGIN && damage[1] < health - POP_MARGIN;
    }

    private boolean quickMine(Threat threat) {
        if (!mine.get() || mineBlocked) return false;
        MiningManager mining = MiningManager.get();
        for (BlockPos option : targets(threat)) {
            if (Targets.isMinable(option) && mining.inRange(option) && mineTicks(option) <= QUICK_MINE_TICKS) return true;
        }
        return false;
    }

    private boolean canBlock(BlockPos pos) {
        if (!InteractionManager.get().canPlace(pos) || MiningManager.get().isMining(pos)) return false;
        return !mc.player.getBoundingBox().intersects(new Box(pos));
    }

    private boolean free(BlockPos pos) {
        InteractionManager interaction = InteractionManager.get();
        for (Entity entity : interaction.obstructions(pos)) {
            if (entity instanceof EndCrystalEntity crystal && (CrystalTracker.get().isDead(crystal) || interaction.recentlyAttacked(crystal))) continue;
            return false;
        }
        return true;
    }

    private void placeAll(List<BlockPos> cleared) {
        if (budget <= 0) return;
        int slot = blockSlot();
        if (slot == -1) return;

        Set<BlockPos> cells = new LinkedHashSet<>();
        for (Threat threat : threats) {
            if (threat.kind != Kind.Spot || threat.urgent || preBlock.get()) cells.add(threat.zone);
        }
        cells.addAll(cleared);

        int limit = rotate.get() ? Math.min(budget, 1) : budget;
        List<BlockPos> placeable = new ArrayList<>();
        int cost = 0;
        for (BlockPos pos : cells) {
            if (cost >= limit) break;
            if (!canBlock(pos) || !free(pos)) continue;

            int needed = cost(pos);
            if (needed == 0 || cost + needed > limit) continue;
            placeable.add(pos);
            cost += needed;
        }

        if (placeable.isEmpty() || !InteractionManager.get().ready(placeable.getFirst(), airplace.get(), rotate.get(), InteractionManager.DEFENSE_PRIORITY)) return;

        InventoryManager.get().withItemWhileEating(slot, switchMode.get(), () -> {
            for (BlockPos pos : placeable) {
                if (budget <= 0 || !isBlock(mc.player.getMainHandStack())) break;
                placeAt(pos);
            }
        });
    }

    private int cost(BlockPos pos) {
        InteractionManager interaction = InteractionManager.get();
        if (interaction.hit(pos, airplace.get()) != null) return 1;
        if (airplace.get() || rotate.get()) return 0;

        BlockPos support = pos.down();
        return interaction.canPlace(support) && interaction.obstructions(support).isEmpty() && interaction.hit(support, false) != null ? 2 : 0;
    }

    private void placeAt(BlockPos pos) {
        InteractionManager interaction = InteractionManager.get();
        if (!interaction.canPlace(pos)) return;

        BlockHitResult hit = interaction.hit(pos, airplace.get());
        if (hit == null && !airplace.get() && !rotate.get()) {
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

    private void mine() {
        MiningManager mining = MiningManager.get();
        if (!mine.get() || !canAct()) {
            mineBlocked = false;
            mining.release(this);
            return;
        }

        boolean tried = false;
        for (Threat threat : threats) {
            if (!threat.urgent) continue;

            List<BlockPos> options = new ArrayList<>();
            BlockPos best = null;
            int bestTicks = Integer.MAX_VALUE;
            for (BlockPos option : targets(threat)) {
                if (!Targets.isMinable(option) || !mining.inRange(option)) continue;
                options.add(option);
                int needed = mineTicks(option);
                if (needed < bestTicks) {
                    bestTicks = needed;
                    best = option;
                }
            }
            if (best == null) continue;

            if (mining.owns(this) && options.contains(mining.pos()) && !mining.wasBroken()) {
                mineBlocked = false;
                return;
            }
            tried = true;
            if (mining.mine(this, MINE_PRIORITY, best, true, switchMode.get(), swing.get())) {
                mineBlocked = false;
                return;
            }
        }

        mineBlocked = tried;
        if (tried) return;
        if (mining.owns(this) && tick - lastUrgent <= MINE_HOLD_TICKS && !mining.wasBroken() && mining.pos() != null && !mc.world.getBlockState(mining.pos()).isAir()) return;
        mining.release(this);
    }

    private Set<BlockPos> targets(Threat threat) {
        Set<BlockPos> targets = new LinkedHashSet<>();
        targets.add(threat.piston);
        for (Direction direction : Direction.values()) {
            if (direction != threat.push) addSource(targets, threat.piston.offset(direction), direction);
        }
        BlockPos above = threat.piston.up();
        for (Direction direction : Direction.values()) {
            if (direction != Direction.DOWN) addSource(targets, above.offset(direction), direction);
        }
        return targets;
    }

    private void addSource(Set<BlockPos> targets, BlockPos pos, Direction direction) {
        BlockState state = mc.world.getBlockState(pos);
        if (state.isAir() || state.getBlock() instanceof PistonBlock || state.getBlock().getBlastResistance() >= 600) return;
        if (emits(pos, direction)) targets.add(pos);
    }

    private int mineTicks(BlockPos pos) {
        long key = pos.asLong();
        if (mineTicksCache.containsKey(key)) return mineTicksCache.get(key);

        BlockState state = mc.world.getBlockState(pos);
        MiningManager mining = MiningManager.get();
        float best = mining.groundDelta(state, pos, mc.player.getMainHandStack(), true);
        for (int i = 0; i < InventoryManager.MAIN_END; i++) {
            best = Math.max(best, mining.groundDelta(state, pos, InventoryManager.get().stack(i), true));
        }
        if (!mc.player.isOnGround() && !AirMine.spoofing()) best /= AIR_PENALTY;
        int needed = best <= 0 ? Integer.MAX_VALUE : best >= 1 ? 0 : MathHelper.ceil(1 / best);
        mineTicksCache.put(key, needed);
        return needed;
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
