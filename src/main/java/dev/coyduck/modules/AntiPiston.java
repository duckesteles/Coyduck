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
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.PistonBlock;
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
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class AntiPiston extends Module {
    private static final float POP_MARGIN = 1.5f;
    private static final float LOW_HEALTH = 12;
    private static final int MINE_PRIORITY = 60;
    private static final int ABOVE = 2;
    private static final double SHRINK = 1.0E-3;

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
        .description("Breaks a crystal a piston is about to push into you, as long as the break itself does not pop you.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> popIfPushed = sgGeneral.add(new BoolSetting.Builder()
        .name("pop-if-pushed")
        .description("Also breaks such a crystal when it pops you, but only if it would pop you once pushed anyway and Auto Totem+ has confirmed your offhand totem with a spare left.")
        .defaultValue(true)
        .visible(breakCrystals::get)
        .build()
    );

    private final Setting<Boolean> mine = sgGeneral.add(new BoolSetting.Builder()
        .name("mine")
        .description("Mines the piston or the redstone powering it, whichever breaks sooner. Only while no other module is mining, so it never takes the pick from one.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> place = sgGeneral.add(new BoolSetting.Builder()
        .name("place")
        .description("Places a block where the piston head would extend, and where a broken crystal sat, so the push cannot happen.")
        .defaultValue(true)
        .build()
    );

    private final Setting<List<Block>> blocks = sgGeneral.add(new BlockListSetting.Builder()
        .name("blocks")
        .description("Blocks to place, in order of preference. Pistons cannot move any of these.")
        .defaultValue(Blocks.CRYING_OBSIDIAN, Blocks.OBSIDIAN, Blocks.ENDER_CHEST)
        .filter(block -> block.getBlastResistance() >= 600)
        .visible(place::get)
        .build()
    );

    private final Setting<Integer> blocksPerTick = sgGeneral.add(new IntSetting.Builder()
        .name("blocks-per-tick")
        .description("Most blocks placed in one tick, packet reactions included.")
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

    private record Threat(BlockPos piston, Direction push, BlockPos head, boolean extended, List<EndCrystalEntity> crystals) {}

    private enum Verdict {
        Safe,
        Pop,
        Unsafe
    }

    private final Long2LongOpenHashMap rendered = new Long2LongOpenHashMap();
    private List<Threat> threats = List.of();
    private int budget;
    private boolean running;

    public AntiPiston() {
        super(Coyduck.CATEGORY, "anti-piston", "Stops piston crystals: breaks the crystal before it is pushed, mines the piston or its redstone and blocks the piston head.");
    }

    @Override
    public void onActivate() {
        rendered.clear();
        threats = List.of();
        budget = blocksPerTick.get();
        pass();
    }

    @Override
    public void onDeactivate() {
        MiningManager.get().release(this);
        threats = List.of();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        budget = blocksPerTick.get();
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
        double near = range.get() + 3;
        boolean relevant = switch (event.packet) {
            case BlockUpdateS2CPacket packet -> packet.getPos().isWithinDistance(center, near);
            case ChunkDeltaUpdateS2CPacket packet -> Targets.touches(packet, center, near);
            case EntitiesDestroyS2CPacket ignored -> true;
            case ExplosionS2CPacket ignored -> true;
            case PlayerActionResponseS2CPacket ignored -> true;
            default -> false;
        };

        if (relevant) pass();
    }

    @EventHandler
    private void onEntityAdded(EntityAddedEvent event) {
        if (event.entity instanceof EndCrystalEntity && canAct() && event.entity.getBlockPos().isWithinDistance(mc.player.getBlockPos(), range.get() + 3)) pass();
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
        threats = detect();
        if (threats.isEmpty()) return;

        long now = Util.getMeasuringTimeMs();
        for (Threat threat : threats) rendered.put(threat.piston.asLong(), now);

        List<BlockPos> cleared = breakCrystals.get() ? breakAll() : new ArrayList<>();
        if (place.get() && !HoleSnap.snapping()) placeAll(cleared);
    }

    private List<Threat> detect() {
        Box box = mc.player.getBoundingBox();
        LongSet columns = Targets.columns(box);
        LongSet cells = new LongOpenHashSet();
        LongSet body = new LongOpenHashSet();
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE, level = 0;
        for (long key : columns) {
            BlockPos column = BlockPos.fromLong(key);
            level = column.getY();
            minX = Math.min(minX, column.getX());
            maxX = Math.max(maxX, column.getX());
            minZ = Math.min(minZ, column.getZ());
            maxZ = Math.max(maxZ, column.getZ());
            for (int dy = 0; dy <= ABOVE; dy++) cells.add(column.up(dy).asLong());
            body.add(column.asLong());
            body.add(column.up().asLong());
        }
        if (columns.isEmpty()) return List.of();

        int reach = range.get();
        Box area = new Box(minX - reach - 1, level - 2, minZ - reach - 1, maxX + reach + 2, level + ABOVE + 3, maxZ + reach + 2);
        List<EndCrystalEntity> crystals = mc.world.getEntitiesByClass(EndCrystalEntity.class, area, crystal -> CrystalTracker.get().isAlive(crystal));

        List<Threat> found = new ArrayList<>();
        BlockPos.Mutable cursor = new BlockPos.Mutable();
        for (int x = minX - reach; x <= maxX + reach; x++) {
            for (int z = minZ - reach; z <= maxZ + reach; z++) {
                for (int y = level - 2; y <= level + ABOVE + 2; y++) {
                    cursor.set(x, y, z);
                    BlockState state = mc.world.getBlockState(cursor);
                    if (!(state.getBlock() instanceof PistonBlock)) continue;

                    BlockPos piston = cursor.toImmutable();
                    Direction push = state.get(PistonBlock.FACING);
                    boolean extended = state.get(PistonBlock.EXTENDED);
                    BlockPos head = piston.offset(push);
                    if (!extended && !canExtend(head)) continue;

                    List<EndCrystalEntity> pushed = new ArrayList<>();
                    Box headBox = new Box(head).contract(SHRINK);
                    for (EndCrystalEntity crystal : crystals) {
                        if (!crystal.getBoundingBox().intersects(headBox)) continue;
                        BlockPos at = crystal.getBlockPos();
                        if (cells.contains(at.offset(push).asLong()) || cells.contains(at.asLong())) pushed.add(crystal);
                    }

                    if (!pushed.isEmpty() || body.contains(head.asLong()) || couldPushIn(head, push, cells, box)) {
                        found.add(new Threat(piston, push, head, extended, pushed));
                    }
                }
            }
        }

        Vec3d eye = mc.player.getEyePos();
        found.sort(Comparator.comparingDouble(threat -> Vec3d.ofCenter(threat.head).squaredDistanceTo(eye)));
        return found;
    }

    private boolean canExtend(BlockPos head) {
        if (InteractionManager.get().isPending(head)) return false;
        BlockState state = mc.world.getBlockState(head);
        return state.isReplaceable() || state.isOf(Blocks.PISTON_HEAD) || state.isOf(Blocks.MOVING_PISTON);
    }

    private boolean couldPushIn(BlockPos head, Direction push, LongSet cells, Box body) {
        BlockPos.Mutable cell = new BlockPos.Mutable();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dy = -1; dy <= 0; dy++) {
                    cell.set(head.getX() + dx, head.getY() + dy, head.getZ() + dz);
                    if (!cells.contains(BlockPos.asLong(cell.getX() + push.getOffsetX(), cell.getY() + push.getOffsetY(), cell.getZ() + push.getOffsetZ()))) continue;
                    if (!mc.world.getBlockState(cell).isAir()) continue;
                    if (body.intersects(new Box(cell.getX(), cell.getY(), cell.getZ(), cell.getX() + 1, cell.getY() + 2, cell.getZ() + 1))) continue;
                    if (Targets.isBase(mc.world.getBlockState(cell.down()))) return true;
                }
            }
        }
        return false;
    }

    private List<BlockPos> breakAll() {
        InteractionManager interaction = InteractionManager.get();
        List<BlockPos> cleared = new ArrayList<>();
        Set<EndCrystalEntity> handled = new LinkedHashSet<>();

        for (Threat threat : threats) {
            for (EndCrystalEntity crystal : threat.crystals) {
                if (!handled.add(crystal)) continue;
                if (interaction.recentlyAttacked(crystal)) {
                    cleared.add(crystal.getBlockPos());
                    continue;
                }
                if (CrystalTracker.get().isDead(crystal)) continue;

                Verdict verdict = verdict(crystal, threat);
                if (verdict == Verdict.Unsafe) continue;

                Vec3d aim = canBlock(threat.head) ? interaction.clearAim(crystal, threat.head, airplace.get()) : crystal.getBoundingBox().getCenter();
                if (!interaction.attack(crystal, switchMode.get(), swing.get(), true, rotate.get(), aim)) continue;

                CrystalTracker.get().explodeAt(crystal.getEntityPos());
                if (verdict == Verdict.Pop) AutoTotem.notePop();
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

        float raw = ExplosionDamage.crystalRaw(mc.player, position, box, crystal.getEntityPos(), mc.world);
        float damage = HurtTracker.get().effectiveOnSelf(raw, arrival);
        float full = worst ? ExplosionDamage.upperBound(mc.player, position, crystal.getEntityPos()) : ExplosionDamage.mitigate(mc.player, raw);
        if (damage < health - POP_MARGIN && full < health - POP_MARGIN) return Verdict.Safe;
        if (!popIfPushed.get() || !AutoTotem.canAffordPop()) return Verdict.Unsafe;

        Vec3d pushed = threat.extended ? crystal.getEntityPos() : crystal.getEntityPos().add(Vec3d.of(threat.push.getVector()));
        float pushedRaw = ExplosionDamage.crystalRaw(mc.player, position, box, pushed, mc.world);
        float pushedDamage = HurtTracker.get().effectiveOnSelf(pushedRaw, arrival);
        float pushedFull = worst ? ExplosionDamage.upperBound(mc.player, position, pushed) : ExplosionDamage.mitigate(mc.player, pushedRaw);
        return pushedDamage >= health - POP_MARGIN || pushedFull >= health - POP_MARGIN ? Verdict.Pop : Verdict.Unsafe;
    }

    private boolean canBlock(BlockPos pos) {
        InteractionManager interaction = InteractionManager.get();
        if (!interaction.canPlace(pos) || MiningManager.get().isMining(pos)) return false;
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
        for (Threat threat : threats) cells.add(threat.head);
        cells.addAll(cleared);

        InteractionManager interaction = InteractionManager.get();
        List<BlockPos> placeable = new ArrayList<>();
        int cost = 0;
        for (BlockPos pos : cells) {
            if (cost >= budget) break;
            if (!canBlock(pos) || !free(pos)) continue;

            int needed = cost(pos);
            if (needed == 0 || cost + needed > budget) continue;
            placeable.add(pos);
            cost += needed;
        }

        if (placeable.isEmpty() || !interaction.ready(placeable.getFirst(), airplace.get(), rotate.get(), InteractionManager.DEFENSE_PRIORITY)) return;

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
        if (airplace.get()) return 0;

        BlockPos support = pos.down();
        return interaction.canPlace(support) && interaction.obstructions(support).isEmpty() && interaction.hit(support, false) != null ? 2 : 0;
    }

    private void placeAt(BlockPos pos) {
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

    private void mine() {
        MiningManager mining = MiningManager.get();
        if (!mine.get() || !canAct() || threats.isEmpty()) {
            mining.release(this);
            return;
        }
        if (mining.pos() != null && !mining.owns(this)) return;

        List<BlockPos> targets = new ArrayList<>();
        BlockPos best = null;
        int bestTicks = Integer.MAX_VALUE;
        for (Threat threat : threats) {
            List<BlockPos> options = new ArrayList<>();
            options.add(threat.piston);
            options.addAll(sources(threat));
            for (BlockPos option : options) {
                if (!Targets.isMinable(option) || !mining.inRange(option)) continue;
                targets.add(option);
                int ticks = ticks(option);
                if (ticks < bestTicks) {
                    bestTicks = ticks;
                    best = option;
                }
            }
            if (best != null) break;
        }

        if (best == null) {
            mining.release(this);
            return;
        }
        if (mining.owns(this) && targets.contains(mining.pos()) && !mining.wasBroken()) return;
        mining.mine(this, MINE_PRIORITY, best, true, switchMode.get(), swing.get());
    }

    private List<BlockPos> sources(Threat threat) {
        List<BlockPos> sources = new ArrayList<>();
        addSources(sources, threat.piston, threat.head);
        addSources(sources, threat.piston.up(), threat.piston);
        return sources;
    }

    private void addSources(List<BlockPos> sources, BlockPos around, BlockPos skip) {
        for (Direction direction : Direction.values()) {
            BlockPos pos = around.offset(direction);
            if (pos.equals(skip) || sources.contains(pos)) continue;

            BlockState state = mc.world.getBlockState(pos);
            if (state.isAir() || !state.emitsRedstonePower() || state.getBlock() instanceof PistonBlock) continue;
            sources.add(pos);
        }
    }

    private int ticks(BlockPos pos) {
        BlockState state = mc.world.getBlockState(pos);
        MiningManager mining = MiningManager.get();
        float best = mining.delta(state, pos, mc.player.getMainHandStack());
        for (int i = 0; i < InventoryManager.MAIN_END; i++) {
            best = Math.max(best, mining.delta(state, pos, InventoryManager.get().stack(i)));
        }
        if (best <= 0) return Integer.MAX_VALUE;
        return best >= 1 ? 0 : MathHelper.ceil(1 / best);
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
