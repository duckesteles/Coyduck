package dev.coyduck.modules;

import dev.coyduck.core.Core;
import dev.coyduck.events.EntityAddedEvent;
import dev.coyduck.events.PacketApplyEvent;
import dev.coyduck.events.PlayerMoveEvent;
import dev.coyduck.events.RenderWorldEvent;
import dev.coyduck.events.TickEvent;
import dev.coyduck.events.WorldResetEvent;
import dev.coyduck.feature.BlockListSetting;
import dev.coyduck.feature.BoolSetting;
import dev.coyduck.feature.ColorSetting;
import dev.coyduck.feature.DoubleSetting;
import dev.coyduck.feature.EnumSetting;
import dev.coyduck.feature.Feature;
import dev.coyduck.feature.IntSetting;
import dev.coyduck.feature.Rgba;
import dev.coyduck.feature.Setting;
import dev.coyduck.feature.SettingGroup;
import dev.coyduck.logic.BlockOverlay;
import dev.coyduck.managers.CrystalTracker;
import dev.coyduck.managers.HurtTracker;
import dev.coyduck.managers.InteractionManager;
import dev.coyduck.managers.InventoryManager;
import dev.coyduck.managers.LatencyManager;
import dev.coyduck.managers.MiningManager;
import dev.coyduck.managers.MiningTracker;
import dev.coyduck.managers.RotationManager;
import dev.coyduck.render.Argb;
import dev.coyduck.render.DuckRender;
import dev.coyduck.render.Shape;
import dev.coyduck.utils.ExplosionDamage;
import dev.coyduck.utils.Targets;
import dev.coyduck.utils.Trace;
import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.Entity;
import net.minecraft.entity.FallingBlockEntity;
import net.minecraft.entity.MovementType;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.s2c.play.BlockBreakingProgressS2CPacket;
import net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ChunkDeltaUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.EntitiesDestroyS2CPacket;
import net.minecraft.network.packet.s2c.play.ExplosionS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerActionResponseS2CPacket;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.util.Hand;
import net.minecraft.util.Util;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import net.minecraft.world.dimension.DimensionTypes;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class Surround extends Feature {
    private static final double ANVIL_RANGE = 1.5;
    public enum Disable {
        Never,
        Leave
    }

    private static final double MACE_MIN_HEIGHT = 2;
    private static final double MACE_MAX_HEIGHT = 40;
    private static final double MACE_RANGE = 8;
    private static final long HOT_TIME = 2_000;
    private static final int READY_STAGE = 7;
    private static final long REBREAK_TIME = 1_200;
    private static final double REBREAK_RANGE = 7;
    private static final float STRONG = 600;
    private static final long WEAK_OWN_TIME = 5_000;
    private static final long ANCHOR_MEMORY = 5_000;
    private static final long FEET_RETRY = 250;
    private static final double EDGE = 1.0E-4;
    private static final double CENTER_SPEED = 0.2;
    private static final double NEAR = 5 * 5;
    private static final double EXPLOSION_NEAR = 8 * 8;
    private static final float POP_MARGIN = 1.5f;
    private static final float SHIELD_MARGIN = 0.5f;
    private static final float LOW_HEALTH = 12;
    private static final double FACE_ENEMY_RANGE = 12;
    private static final float FACE_MIN_DAMAGE = 2;

    private static final float ANCHOR_POWER = 5.0f;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgProtect = settings.createGroup("Protect");
    private final SettingGroup sgRender = settings.createGroup("Render");

    private final Setting<List<Block>> blocks = sgGeneral.add(new BlockListSetting.Builder()
        .name("blocks")
        .description("Blocks to use, in order of preference.")
        .defaultValue(Blocks.CRYING_OBSIDIAN, Blocks.OBSIDIAN, Blocks.ENDER_CHEST)
        .filter(block -> block.getBlastResistance() >= 600)
        .build()
    );

    private final Setting<InventoryManager.SwitchMode> switchMode = sgGeneral.add(new EnumSetting.Builder<InventoryManager.SwitchMode>()
        .name("switch")
        .description("Silent uses hotbar slots, InvSwap uses inventory clicks.")
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
        .description("Most blocks placed per tick.")
        .defaultValue(10)
        .range(1, 30)
        .sliderRange(1, 30)
        .build()
    );

    private final Setting<Boolean> extend = sgGeneral.add(new BoolSetting.Builder()
        .name("extend")
        .description("Covers every block you stand on; off centers you.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> floor = sgGeneral.add(new BoolSetting.Builder()
        .name("floor")
        .description("Fills the blocks under your feet.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> clearFeet = sgGeneral.add(new BoolSetting.Builder()
        .name("clear-feet")
        .description("Breaks scaffolding and ladders put on you.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> clearWeak = sgGeneral.add(new BoolSetting.Builder()
        .name("clear-weak")
        .description("Replaces weak blocks enemies put in your surround.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> predict = sgGeneral.add(new BoolSetting.Builder()
        .name("predict")
        .description("Resends blocks while an enemy breaks your surround.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> rotate = sgGeneral.add(new BoolSetting.Builder()
        .name("rotate")
        .description("Looks at each block before placing it.")
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

    private final Setting<Disable> disable = sgGeneral.add(new EnumSetting.Builder<Disable>()
        .name("disable")
        .description("Leave turns the module off once you walk out of the hole.")
        .defaultValue(Disable.Leave)
        .build()
    );

    private final Setting<Boolean> breakCrystals = sgProtect.add(new BoolSetting.Builder()
        .name("break-crystals")
        .description("Breaks crystals where surround blocks belong.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> allowPop = sgProtect.add(new BoolSetting.Builder()
        .name("allow-pop")
        .description("Also breaks popping crystals if you have a spare totem.")
        .defaultValue(false)
        .visible(breakCrystals::get)
        .build()
    );

    private final Setting<Boolean> antiCev = sgProtect.add(new BoolSetting.Builder()
        .name("anti-cev")
        .description("Breaks crystals near your head before their block breaks.")
        .defaultValue(true)
        .visible(breakCrystals::get)
        .build()
    );

    private final Setting<Boolean> blocker = sgProtect.add(new BoolSetting.Builder()
        .name("blocker")
        .description("Covers surround blocks being mined or just broken.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> blockerTop = sgProtect.add(new BoolSetting.Builder()
        .name("blocker-top")
        .description("Also covers the top of that block.")
        .defaultValue(true)
        .visible(blocker::get)
        .build()
    );

    private final Setting<Boolean> face = sgProtect.add(new BoolSetting.Builder()
        .name("face")
        .description("Covers head level cells against face places.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> faceHealth = sgProtect.add(new DoubleSetting.Builder()
        .name("face-health")
        .description("Covers all head level cells at or below this health.")
        .defaultValue(36)
        .range(0, 36)
        .sliderRange(0, 36)
        .visible(face::get)
        .build()
    );

    private final Setting<Boolean> maceRoof = sgProtect.add(new BoolSetting.Builder()
        .name("mace-roof")
        .description("Closes you in against maces, elytras and anvils above.")
        .defaultValue(true)
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
        .defaultValue(new Rgba(251, 192, 45, 40))
        .visible(render::get)
        .build()
    );

    private final ColorSetting lineColor = sgRender.add(new ColorSetting.Builder()
        .name("line-color")
        .description("Highlight line color.")
        .defaultValue(new Rgba(251, 192, 45, 200))
        .visible(render::get)
        .build()
    );

    private final Setting<Boolean> showOpen = sgRender.add(new BoolSetting.Builder()
        .name("show-open")
        .description("Marks surround cells the server doesn't hold yet.")
        .defaultValue(true)
        .build()
    );

    private final ColorSetting openColor = sgRender.add(new ColorSetting.Builder()
        .name("open-color")
        .description("Color of open cells.")
        .defaultValue(new Rgba(230, 60, 60, 220))
        .visible(showOpen::get)
        .build()
    );

    private static final long OPEN_MEMORY = 600;

    private record Side(BlockPos pos, Direction outward) {}

    private final Long2LongOpenHashMap rendered = new Long2LongOpenHashMap();
    private final Long2ObjectOpenHashMap<String> cellStatus = new Long2ObjectOpenHashMap<>();
    private final Int2LongOpenHashMap anchorSeen = new Int2LongOpenHashMap();
    private long anchorPlacedUntil;
    private LongSet anchored = new LongOpenHashSet();
    private int anchorY;
    private int budget;
    private boolean running;

    public Surround() {
        super("surround+", "Surrounds your feet with obsidian.");
    }

    private long feetHit;
    private int ticks;
    private long tickStartedAt;
    private boolean reanchor;
    private RegistryKey<World> anchorWorld;
    private boolean destroyedNear;
    private int maceTick = -1;
    private boolean maceCached;
    private final Long2LongOpenHashMap preSent = new Long2LongOpenHashMap();

    @Override
    public void onDeactivate() {
        MiningManager.get().release(this);
    }

    @EventHandler
    private void onWorldReset(WorldResetEvent event) {
        reanchor = true;
        rendered.clear();
        cellStatus.clear();
        preSent.clear();
        anchorSeen.clear();
        anchorPlacedUntil = 0;
        maceTick = -1;
    }

    @Override
    public void onActivate() {
        displace("it places over the same blocks.", "surround");

        rendered.clear();
        cellStatus.clear();
        preSent.clear();
        reanchor = false;
        budget = blocksPerTick.get();

        if (!canAct()) return;
        anchorY = level();
        anchored = columns(anchorY);
        pass();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        budget = blocksPerTick.get();
        ticks++;
        tickStartedAt = Util.getMeasuringTimeMs();
        preSent.long2LongEntrySet().removeIf(entry -> ticks - entry.getLongValue() > 20);
        clearWeak();
        pass();
        clearFeet();
    }

    private void clearWeak() {
        if (!clearWeak.get() || !canAct() || anchored.isEmpty() || HoleSnap.snapping() || mc.interactionManager.isBreakingBlock() || InventoryManager.get().isOffhandEating() || blockSlot() == -1) return;

        MiningManager mining = MiningManager.get();
        InteractionManager interaction = InteractionManager.get();
        for (BlockPos pos : Targets.ring(anchored, 0)) {
            BlockState state = mc.world.getBlockState(pos);
            if (state.isReplaceable() || blocks.get().contains(state.getBlock()) || state.getBlock().getBlastResistance() >= STRONG || state.isOf(Blocks.COBWEB)) continue;
            if (state.getHardness(mc.world, pos) < 0 || mining.isMining(pos) || interaction.placedByUs(pos, WEAK_OWN_TIME) || !mining.inBreakReach(pos)) continue;

            int tool = mining.bestTool(state);
            if (mining.delta(state, pos, tool == -1 ? mc.player.getMainHandStack() : InventoryManager.get().stack(tool)) < 1) continue;

            BlockPos cell = pos.toImmutable();
            Direction side = MiningManager.facing(cell);
            Runnable hit = () -> {
                mc.interactionManager.attackBlock(cell, side);
                interaction.swing(InteractionManager.SwingMode.Packet);
            };
            if (tool == -1) hit.run();
            else if (!InventoryManager.get().withItemWhileEating(tool, switchMode.get(), hit)) continue;
            Trace.log("surround clears weak {} {}", cell, Registries.BLOCK.getId(state.getBlock()).getPath());
            mining.foreignBreak();
        }
    }

    private void clearFeet() {
        if (!canAct()) return;

        MiningManager mining = MiningManager.get();
        BlockPos climb = clearFeet.get() ? climbable() : null;
        if (climb == null) {
            mining.release(this);
            return;
        }

        long now = Util.getMeasuringTimeMs();
        if (!mining.owns(this) && mining.pos() != null) return;

        if (mc.world.getBlockState(climb).isOf(Blocks.SCAFFOLDING)) {
            if (now - feetHit < FEET_RETRY) return;
            feetHit = now;
            mc.interactionManager.attackBlock(climb, Direction.UP);
            mc.player.swingHand(Hand.MAIN_HAND);
            return;
        }
        mining.mine(this, 1, climb, true, switchMode.get(), InteractionManager.SwingMode.Packet);
    }

    private BlockPos climbable() {
        for (long key : Targets.columns(Targets.box(mc.player))) {
            BlockPos feet = BlockPos.fromLong(key);
            if (isClimb(feet)) return feet;
            if (isClimb(feet.up())) return feet.up();
        }
        return null;
    }

    private boolean isClimb(BlockPos pos) {
        BlockState state = mc.world.getBlockState(pos);
        return state.isOf(Blocks.SCAFFOLDING) || state.isOf(Blocks.LADDER) || state.isOf(Blocks.VINE);
    }

    @EventHandler
    private void onTickPost(TickEvent.Post event) {
        pass();
    }

    @EventHandler
    private void onPacket(PacketApplyEvent.Post event) {
        if (!canAct()) return;

        boolean relevant = switch (event.packet) {
            case BlockUpdateS2CPacket packet -> {
                noteAnchor(packet.getPos(), packet.getState());
                yield near(packet.getPos());
            }
            case BlockBreakingProgressS2CPacket packet -> near(packet.getPos());
            case ChunkDeltaUpdateS2CPacket packet -> {
                boolean[] found = {false};
                packet.visitUpdates((pos, state) -> {
                    noteAnchor(pos, state);
                    found[0] |= near(pos);
                });
                yield found[0];
            }
            case EntitiesDestroyS2CPacket ignored -> destroyedNear;
            case ExplosionS2CPacket packet -> mc.player.squaredDistanceTo(packet.center()) <= EXPLOSION_NEAR;
            case PlayerActionResponseS2CPacket ignored -> ownPending();
            default -> false;
        };

        destroyedNear = false;
        if (relevant) pass();
    }

    @EventHandler
    private void onPacketPre(PacketApplyEvent.Pre event) {
        if (!(event.packet instanceof EntitiesDestroyS2CPacket packet) || mc.world == null || mc.player == null) return;
        for (int id : packet.getEntityIds()) {
            Entity entity = mc.world.getEntityById(id);
            if (entity != null && near(entity.getBlockPos())) {
                destroyedNear = true;
                return;
            }
        }
    }

    private boolean ownPending() {
        InteractionManager interaction = InteractionManager.get();
        for (BlockPos pos : Targets.ring(anchored, 0)) {
            if (interaction.isPending(pos)) return true;
        }
        return false;
    }

    @EventHandler
    private void onEntityAdded(EntityAddedEvent event) {
        if (event.entity instanceof EndCrystalEntity && canAct() && near(event.entity.getBlockPos())) pass();
    }

    @EventHandler(priority = EventPriority.HIGH)
    private void onMove(PlayerMoveEvent event) {
        if (extend.get() || event.type != MovementType.SELF || !canAct() || anchored.size() != 1 || HoleSnap.snapping()) return;
        if (!mc.player.isOnGround() || hasInput() || level() != anchorY) return;

        BlockPos column = BlockPos.fromLong(anchored.iterator().nextLong());
        double dx = column.getX() + 0.5 - mc.player.getX();
        double dz = column.getZ() + 0.5 - mc.player.getZ();
        double fit = 0.5 - mc.player.getWidth() / 2 - EDGE;
        if (Math.abs(dx) <= fit && Math.abs(dz) <= fit) return;

        event.set(MathHelper.clamp(dx, -CENTER_SPEED, CENTER_SPEED), event.y(), MathHelper.clamp(dz, -CENTER_SPEED, CENTER_SPEED));
        mc.player.setVelocity(0, event.y(), 0);
    }

    @EventHandler
    private void onRender(RenderWorldEvent event) {
        if (!render.get()) return;

        long now = Util.getMeasuringTimeMs();
        rendered.long2LongEntrySet().removeIf(entry -> now - entry.getLongValue() > DuckRender.LIFETIME);
        InteractionManager interaction = InteractionManager.get();
        for (var entry : rendered.long2LongEntrySet()) {
            if (interaction.rejectedAt(BlockPos.fromLong(entry.getLongKey())) >= entry.getLongValue()) continue;
            DuckRender.box(event.canvas, new Box(BlockPos.fromLong(entry.getLongKey())), sideColor.argb(), lineColor.argb(), shapeMode.get(), DuckRender.alpha(now - entry.getLongValue()));
        }
        if (showOpen.get() && mc.world != null && !anchored.isEmpty()) {
            int line = openColor.argb();
            int openSide = Argb.alpha(line, Math.min(Argb.a(line), 45));
            for (BlockPos pos : Targets.ring(anchored, 0)) {
                if (open(pos, now)) DuckRender.box(event.canvas, new Box(pos), openSide, line, Shape.Both, 1);
            }
        }
    }

    private boolean open(BlockPos pos, long now) {
        InteractionManager interaction = InteractionManager.get();
        BlockState state = mc.world.getBlockState(pos);
        if (state.isReplaceable() || interaction.isPending(pos)) return true;
        long rejected = interaction.rejectedAt(pos);
        if (rejected != 0 && now - rejected < OPEN_MEMORY) return true;
        return !blocks.get().contains(state.getBlock()) && state.getBlock().getBlastResistance() < STRONG;
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
        if (HoleSnap.snapping() || !updateAnchor()) return;
        antiCev();
        if (budget <= 0) return;

        int slot = blockSlot();
        if (slot == -1) return;

        InteractionManager interaction = InteractionManager.get();
        List<BlockPos> placeable = new ArrayList<>();
        LongSet retried = new LongOpenHashSet();

        MiningManager mining = MiningManager.get();
        int cost = 0;
        boolean tracing = Trace.on();
        for (BlockPos pos : targets()) {
            if (cost >= budget) {
                if (!tracing) break;
                note(pos, "budget");
                continue;
            }
            boolean retry = interaction.isPending(pos) && liveCrystalIn(pos);
            if (!retry && !interaction.canPlace(pos)) {
                if (tracing) note(pos, interaction.isPending(pos) ? "pending" : mc.world.getBlockState(pos).isReplaceable() ? "unplaceable" : "filled " + Registries.BLOCK.getId(mc.world.getBlockState(pos).getBlock()).getPath());
                continue;
            }
            if (mining.isMining(pos)) {
                if (tracing) note(pos, "we mine it");
                continue;
            }
            if (!retry && tickStartedAt != 0 && interaction.rejectedAt(pos) >= tickStartedAt) {
                if (tracing) note(pos, "rejected, next tick");
                continue;
            }

            int needed = cost(pos);
            if (needed == 0 || cost + needed > budget) {
                if (tracing) note(pos, needed == 0 ? "no face to place on" : "budget");
                continue;
            }
            if (!clear(pos)) {
                if (tracing) note(pos, "blocked by " + obstruction(pos));
                continue;
            }
            if (tracing) note(pos, retry ? "placing again over crystal" : "placing");

            if (retry) {
                interaction.forget(pos);
                retried.add(pos.asLong());
            }
            placeable.add(pos);
            cost += needed;
        }

        sweep(placeable, interaction);
        if (!placeable.isEmpty() && interaction.ready(placeable.get(0), airplace.get(), rotate.get(), InteractionManager.SURROUND_PRIORITY)) {
            InventoryManager.get().withItemWhileEating(slot, switchMode.get(), () -> {
                for (BlockPos pos : placeable) {
                    if (budget <= 0 || !isBlock(mc.player.getMainHandStack())) break;
                    place(pos, retried.contains(pos.asLong()));
                }
            });
        }

        prePlace(slot);
    }

    private void sweep(List<BlockPos> cells, InteractionManager interaction) {
        if (cells.size() < 2) return;
        Vec3d eye = mc.player.getEyePos();
        float yaw = RotationManager.get().serverYaw();
        List<BlockPos> left = new ArrayList<>(cells);
        cells.clear();
        while (!left.isEmpty()) {
            BlockPos best = null;
            float bestTurn = Float.MAX_VALUE;
            boolean bestFresh = false;
            for (BlockPos cell : left) {
                boolean fresh = !interaction.wasRejected(cell);
                float turn = Math.abs(MathHelper.wrapDegrees(yawTo(eye, cell) - yaw));
                if (best == null || fresh && !bestFresh || fresh == bestFresh && turn < bestTurn) {
                    best = cell;
                    bestTurn = turn;
                    bestFresh = fresh;
                }
            }
            left.remove(best);
            cells.add(best);
            yaw = yawTo(eye, best);
        }
    }

    private static float yawTo(Vec3d eye, BlockPos cell) {
        return (float) Math.toDegrees(Math.atan2(cell.getZ() + 0.5 - eye.z, cell.getX() + 0.5 - eye.x)) - 90.0f;
    }

    private void note(BlockPos pos, String status) {
        String before = cellStatus.put(pos.asLong(), status);
        if (!status.equals(before)) Trace.log("surround {} {}{}", pos, status, before == null ? "" : " (was " + before + ")");
    }

    private String obstruction(BlockPos pos) {
        List<Entity> entities = InteractionManager.get().obstructions(pos);
        if (entities.isEmpty()) return "nothing";
        Entity first = entities.getFirst();
        return first instanceof EndCrystalEntity ? "crystal" + (InteractionManager.get().recentlyAttacked(first) ? " (hit, waiting)" : "") : first instanceof PlayerEntity player ? player.getName().getString() : Registries.ENTITY_TYPE.getId(first.getType()).getPath();
    }

    private void prePlace(int slot) {
        if (!predict.get() || rotate.get() || budget <= 0 || eating()) return;

        InteractionManager interaction = InteractionManager.get();
        List<BlockPos> ready = new ArrayList<>();
        for (BlockPos pos : Targets.ring(anchored, 0)) {
            if (ready.size() >= budget || preSent.containsKey(pos.asLong()) && ticks - preSent.get(pos.asLong()) < preInterval() || !aboutToBreak(pos)) continue;
            if (!interaction.obstructions(pos).isEmpty()) continue;
            if (interaction.hit(pos, airplace.get(), true) != null) ready.add(pos);
        }
        if (ready.isEmpty()) return;

        InventoryManager.get().withItemWhileEating(slot, switchMode.get(), () -> {
            for (BlockPos pos : ready) {
                if (budget <= 0 || !isBlock(mc.player.getMainHandStack())) break;
                BlockHitResult hit = interaction.hit(pos, airplace.get(), true);
                if (hit == null) continue;

                mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hit);
                interaction.swing(swing.get());
                Trace.log("surround pre-place {} about to break", pos);
                preSent.put(pos.asLong(), ticks);
                budget--;
            }
        });
    }

    private boolean eating() {
        return mc.player.isUsingItem() && mc.player.getActiveItem().contains(DataComponentTypes.CONSUMABLE);
    }

    private static int preInterval() {
        return Math.clamp(Math.round(LatencyManager.get().rtt() / 100f), 2, 6);
    }

    private boolean aboutToBreak(BlockPos pos) {
        BlockState state = mc.world.getBlockState(pos);
        if (state.isReplaceable()) return false;
        if (!blocks.get().contains(state.getBlock())) {
            return state.getBlock().getBlastResistance() < STRONG && MiningTracker.get().wasBroken(pos, HOT_TIME) && Targets.find(REBREAK_RANGE) != null;
        }

        MiningTracker.Mining mining = MiningTracker.get().mining(pos);
        if (mining != null && mining.breaker() != mc.player.getId() && mining.stage() >= READY_STAGE) return true;
        return MiningTracker.get().wasBroken(pos, REBREAK_TIME) && Targets.find(REBREAK_RANGE) != null;
    }

    private boolean liveCrystalIn(BlockPos pos) {
        for (Entity entity : InteractionManager.get().obstructions(pos)) {
            if (entity instanceof EndCrystalEntity && entity.getBlockPos().equals(pos) && !CrystalTracker.get().isDead(entity)
                && !InteractionManager.get().recentlyAttacked(entity)) return true;
        }
        return false;
    }

    private int cost(BlockPos pos) {
        InteractionManager interaction = InteractionManager.get();
        if (interaction.hit(pos, airplace.get(), true) != null) return 1;
        if (airplace.get()) return 0;

        BlockPos support = pos.down();
        return interaction.canPlace(support) && interaction.obstructions(support).isEmpty() && interaction.hit(support, false) != null ? 2 : 0;
    }

    private void place(BlockPos pos, boolean force) {
        InteractionManager interaction = InteractionManager.get();
        if (!force && !interaction.canPlace(pos)) return;

        BlockHitResult hit = interaction.hit(pos, airplace.get(), true);
        if (hit == null && !airplace.get()) {
            BlockPos support = pos.down();
            BlockHitResult supportHit = interaction.canPlace(support) && interaction.obstructions(support).isEmpty() ? interaction.hit(support, false) : null;
            if (supportHit == null) return;

            commit(support, supportHit);
            if (budget <= 0 || !isBlock(mc.player.getMainHandStack())) return;
            hit = interaction.hit(pos, false);
        }
        if (hit != null && !interaction.seen(hit)) {
            InteractionManager.Helper helper = interaction.helper(pos, cell -> true);
            if (helper != null) {
                commit(helper.cell(), helper.hit());
                if (budget <= 0 || !isBlock(mc.player.getMainHandStack())) return;
                hit = interaction.hit(pos, airplace.get(), true);
            }
        }

        if (hit != null) commit(pos, hit);
    }

    private void commit(BlockPos pos, BlockHitResult hit) {
        if (!InteractionManager.get().place(pos, hit, swing.get(), rotate.get(), look.get(), InteractionManager.SURROUND_PRIORITY)) {
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

        EndCrystalEntity safest = null;
        Verdict safestVerdict = Verdict.Unsafe;
        for (Entity entity : entities) {
            if (!(entity instanceof EndCrystalEntity)) return false;
        }
        for (Entity entity : entities) {
            EndCrystalEntity crystal = (EndCrystalEntity) entity;
            if (interaction.recentlyAttacked(crystal)) return true;

            Verdict verdict = verdict(crystal, true);
            if (verdict.ordinal() < safestVerdict.ordinal()) {
                safest = crystal;
                safestVerdict = verdict;
            }
        }

        if (safest == null || !interaction.attack(safest, switchMode.get(), swing.get(), true, rotate.get(), interaction.clearAim(safest, pos, airplace.get()))) return false;

        CrystalTracker.get().explodeAt(safest.getEntityPos());
        if (safestVerdict == Verdict.Pop) AutoTotem.notePop();
        return true;
    }

    private enum Verdict {
        Safe,
        Pop,
        Unsafe
    }

    private void antiCev() {
        if (!antiCev.get() || !breakCrystals.get()) return;
        BlockPos feet = mc.player.getBlockPos();
        List<BlockPos> cells = new ArrayList<>();
        cells.add(feet.up(2));
        for (Direction direction : Direction.Type.HORIZONTAL) cells.add(feet.up().offset(direction));

        InteractionManager interaction = InteractionManager.get();
        for (BlockPos cell : cells) {
            MiningTracker.Mining mining = MiningTracker.get().mining(cell);
            if (mining == null || mining.breaker() == mc.player.getId() || mc.world.getBlockState(cell).isReplaceable()) continue;
            for (Entity entity : mc.world.getOtherEntities(null, new Box(cell.up()))) {
                if (!(entity instanceof EndCrystalEntity crystal) || !crystal.getBlockPos().down().equals(cell) || CrystalTracker.get().isDead(crystal) || interaction.recentlyAttacked(crystal)) continue;
                Verdict verdict = verdict(crystal, false);
                if (verdict == Verdict.Unsafe) continue;
                if (!interaction.attack(crystal, switchMode.get(), swing.get(), true, rotate.get(), crystal.getBoundingBox().getCenter())) return;
                CrystalTracker.get().explodeAt(crystal.getEntityPos());
                if (verdict == Verdict.Pop) AutoTotem.notePop();
                Trace.log("surround anti cev broke the crystal on {}", cell);
                return;
            }
        }
    }

    private Verdict verdict(EndCrystalEntity crystal, boolean gap) {
        float raw = ExplosionDamage.crystalRaw(mc.player, mc.player.getEntityPos(), mc.player.getBoundingBox(), crystal.getEntityPos(), mc.world);
        long arrival = Util.getMeasuringTimeMs() + LatencyManager.get().rtt() / 2;
        float damage = HurtTracker.get().effectiveOnSelf(raw, arrival);

        float health = AutoTotem.popInFlight() ? 1.0f : mc.player.getHealth() + mc.player.getAbsorptionAmount();
        boolean worst = mc.player.isInsideWall() || health <= LOW_HEALTH;
        float full = worst ? ExplosionDamage.upperBound(mc.player, mc.player.getEntityPos(), crystal.getEntityPos()) : ExplosionDamage.mitigate(mc.player, raw);
        if (damage < health - POP_MARGIN && full < health - POP_MARGIN) return Verdict.Safe;
        if (gap && shielded(crystal, raw, worst, arrival, health)) return Verdict.Safe;
        return allowPop.get() && AutoTotem.canAffordPop() ? Verdict.Pop : Verdict.Unsafe;
    }

    private boolean shielded(EndCrystalEntity crystal, float raw, boolean worst, long arrival, float health) {
        float bound = worst ? Math.max(raw, ExplosionDamage.rawBound(mc.player, mc.player.getEntityPos(), crystal.getEntityPos(), ExplosionDamage.CRYSTAL_POWER)) : raw;
        float damage = HurtTracker.get().effectiveOnSelf(bound, arrival);
        boolean covered = damage + SHIELD_MARGIN < ExplosionDamage.mitigate(mc.player, bound) && damage < health - POP_MARGIN;
        if (covered) Trace.log("surround breaks the gap crystal inside its hurt window, {} instead of {}", damage, ExplosionDamage.mitigate(mc.player, bound));
        return covered;
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
        if (blocker.get() && !eating()) {
            for (Side side : threatenedSides) {
                addBlocker(blockers, ring, side.pos.offset(side.outward));
                addBlocker(blockers, ring, side.pos.offset(side.outward.rotateYClockwise()));
                addBlocker(blockers, ring, side.pos.offset(side.outward.rotateYCounterclockwise()));
                if (blockerTop.get()) addBlocker(blockers, ring, side.pos.up());
            }
        }

        List<BlockPos> faces = faces();

        Set<BlockPos> targets = new LinkedHashSet<>();
        targets.addAll(threatened);
        targets.addAll(roof());
        targets.addAll(floors);
        targets.addAll(sides);
        targets.addAll(faces);
        targets.addAll(blockers);
        return new ArrayList<>(targets);
    }

    private List<BlockPos> faces() {
        List<BlockPos> faces = new ArrayList<>();
        if (!face.get() || Targets.find(FACE_ENEMY_RANGE) == null) return faces;
        boolean anchorArmed = anchorArmed();

        float health = mc.player.getHealth() + mc.player.getAbsorptionAmount();
        for (long key : anchored) {
            BlockPos column = BlockPos.fromLong(key);
            for (Direction direction : Direction.Type.HORIZONTAL) {
                BlockPos side = column.offset(direction);
                if (anchored.contains(side.asLong())) continue;

                BlockPos cell = side.up();
                if (faces.contains(cell)) continue;
                if (faceThreat(cell, health) || mc.world.getBlockState(cell).isReplaceable() && crystalThreat(cell.offset(direction), health, mc.world)) faces.add(cell);
            }

            BlockPos head = column.up(2);
            if (anchorArmed && !anchored.contains(head.asLong()) && !faces.contains(head) && faceThreat(head, health)) faces.add(head);

            BlockPos top = column.up(3);
            if (!mc.world.getBlockState(column.up(2)).isReplaceable() && mc.world.getBlockState(top).isReplaceable()
                && crystalThreat(top, health, new BlockOverlay(mc.world).set(column.up(2), Blocks.AIR.getDefaultState()))) faces.add(top);
        }
        return faces;
    }

    private void noteAnchor(BlockPos pos, BlockState state) {
        if (!state.isOf(Blocks.RESPAWN_ANCHOR) || mc.player.squaredDistanceTo(Vec3d.ofCenter(pos)) > FACE_ENEMY_RANGE * FACE_ENEMY_RANGE) return;
        if (InteractionManager.get().placedByUs(pos, ANCHOR_MEMORY)) return;
        anchorPlacedUntil = Util.getMeasuringTimeMs() + ANCHOR_MEMORY;
    }

    private boolean anchorArmed() {
        if (mc.world.getRegistryKey() == World.NETHER || mc.world.getDimensionEntry().matchesKey(DimensionTypes.THE_NETHER)) return false;
        long now = Util.getMeasuringTimeMs();
        if (now < anchorPlacedUntil) return true;
        for (PlayerEntity player : mc.world.getPlayers()) {
            if (player == mc.player || !player.isAlive() || Core.host().isFriend(player) || player.squaredDistanceTo(mc.player) > FACE_ENEMY_RANGE * FACE_ENEMY_RANGE) continue;
            if (holdsAnchorGear(player.getMainHandStack()) || holdsAnchorGear(player.getOffHandStack())) anchorSeen.put(player.getId(), now + ANCHOR_MEMORY);
        }
        if (!anchorSeen.isEmpty()) anchorSeen.int2LongEntrySet().removeIf(entry -> entry.getLongValue() <= now);
        return !anchorSeen.isEmpty();
    }

    private static boolean holdsAnchorGear(ItemStack stack) {
        return stack.isOf(Items.RESPAWN_ANCHOR) || stack.isOf(Items.GLOWSTONE);
    }

    private List<BlockPos> roof() {
        List<BlockPos> roof = new ArrayList<>();
        if (!maceRoof.get() || !maceAbove()) return roof;

        for (long key : anchored) {
            BlockPos column = BlockPos.fromLong(key);
            BlockPos top = column.up(2);
            if (!anchored.contains(top.asLong()) && !roof.contains(top)) roof.add(top);
            for (Direction direction : Direction.Type.HORIZONTAL) {
                BlockPos side = column.offset(direction);
                if (anchored.contains(side.asLong())) continue;
                BlockPos cell = side.up();
                if (!roof.contains(cell)) roof.add(cell);
            }
        }
        return roof;
    }

    private boolean maceAbove() {
        if (maceTick != ticks) {
            maceTick = ticks;
            maceCached = scanMaceAbove();
        }
        return maceCached;
    }

    private boolean scanMaceAbove() {
        for (PlayerEntity player : mc.world.getPlayers()) {
            if (player == mc.player || !player.isAlive() || player.isSpectator() || Core.host().isFriend(player)) continue;
            double dy = player.getY() - mc.player.getY();
            if (dy < MACE_MIN_HEIGHT || dy > MACE_MAX_HEIGHT) continue;
            double dx = player.getX() - mc.player.getX();
            double dz = player.getZ() - mc.player.getZ();
            if (dx * dx + dz * dz > MACE_RANGE * MACE_RANGE) continue;
            if (player.getMainHandStack().isOf(Items.MACE) || player.isGliding()) return true;
        }
        Box above = new Box(mc.player.getX() - ANVIL_RANGE, mc.player.getY() + 1, mc.player.getZ() - ANVIL_RANGE, mc.player.getX() + ANVIL_RANGE, mc.player.getY() + MACE_MAX_HEIGHT, mc.player.getZ() + ANVIL_RANGE);
        for (FallingBlockEntity falling : mc.world.getEntitiesByClass(FallingBlockEntity.class, above, entity -> entity.getBlockState().isIn(BlockTags.ANVIL))) {
            double dy = falling.getY() - mc.player.getY();
            double dx = falling.getX() - mc.player.getX();
            double dz = falling.getZ() - mc.player.getZ();
            if (dy > 1 && dy < MACE_MAX_HEIGHT && dx * dx + dz * dz <= ANVIL_RANGE * ANVIL_RANGE) return true;
        }
        return false;
    }

    private boolean crystalThreat(BlockPos cell, float health, BlockView view) {
        if (!Targets.canHoldCrystal(mc.world, cell.down())) return false;
        Vec3d crystal = new Vec3d(cell.getX() + 0.5, cell.getY(), cell.getZ() + 0.5);
        return threatens(ExplosionDamage.crystal(mc.player, mc.player.getEntityPos(), mc.player.getBoundingBox(), crystal, view), health);
    }

    private boolean faceThreat(BlockPos cell, float health) {
        if (!mc.world.getBlockState(cell).isReplaceable()) return false;

        float damage = 0;
        if (Targets.isBase(mc.world.getBlockState(cell.down()))) {
            Vec3d crystal = new Vec3d(cell.getX() + 0.5, cell.getY(), cell.getZ() + 0.5);
            damage = ExplosionDamage.crystal(mc.player, mc.player.getEntityPos(), mc.player.getBoundingBox(), crystal, mc.world);
            if (threatens(damage, health)) return true;
        }
        if (mc.world.getRegistryKey() != World.NETHER && !mc.world.getDimensionEntry().matchesKey(DimensionTypes.THE_NETHER)) {
            damage = Math.max(damage, ExplosionDamage.explosion(mc.player, mc.player.getEntityPos(), mc.player.getBoundingBox(), Vec3d.ofCenter(cell), ANCHOR_POWER, mc.world));
        }
        return threatens(damage, health);
    }

    private boolean threatens(float damage, float health) {
        return damage >= health || (health <= faceHealth.get() && damage >= FACE_MIN_DAMAGE);
    }

    private void addBlocker(List<BlockPos> blockers, LongSet ring, BlockPos pos) {
        long key = pos.asLong();
        if (anchored.contains(key) || ring.contains(key) || blockers.contains(pos)) return;
        if (mc.player.getBoundingBox().intersects(new Box(pos))) return;
        blockers.add(pos);
    }

    private boolean isThreatened(BlockPos pos) {
        MiningTracker mining = MiningTracker.get();
        if (mining.stage(pos) >= 0 || mining.wasBroken(pos, HOT_TIME)) return true;
        return !mc.world.getOtherEntities(mc.player, new Box(pos), entity -> entity instanceof PlayerEntity player && !Core.host().isFriend(player)).isEmpty();
    }

    private boolean updateAnchor() {
        int level = level();
        if (reanchor) {
            reanchor = false;
            boolean sameHole = mc.world.getRegistryKey() == anchorWorld && level == anchorY && columns(level).longStream().anyMatch(anchored::contains);
            if (!sameHole) return leave(level);
        }
        anchorWorld = mc.world.getRegistryKey();
        boolean ground = mc.player.isOnGround();
        if (mc.player.isGliding() || (!ground && level > anchorY + 1)) return false;

        if (level < anchorY) {
            if (hasInput() && columns(anchorY).longStream().noneMatch(anchored::contains)) return leave(level);

            anchorY = level;
            anchored = columns(level);
            return true;
        }

        if (ground && level > anchorY) {
            if (hasInput()) return leave(level);
            anchorY = level;
            anchored = columns(level);
            return true;
        }

        LongSet current = columns(anchorY);
        if (current.longStream().noneMatch(anchored::contains)) {
            if (!ground) return false;
            if (hasInput()) return leave(level);

            anchored = current;
            return true;
        }

        if (anchored.containsAll(current)) anchored = current;
        else if (!hasInput()) anchored.addAll(current);
        return true;
    }

    private boolean leave(int level) {
        Trace.log("surround leaves the hole, level {} -> {}", anchorY, level);
        cellStatus.clear();
        if (disable.get() == Disable.Leave) {
            toggle();
            return false;
        }

        anchorY = level;
        anchored = columns(level);
        return true;
    }

    private boolean hasInput() {
        return mc.player.input.getMovementInput().lengthSquared() > 1.0E-4f || mc.player.input.playerInput.jump();
    }

    private LongSet columns(int y) {
        Box box = mc.player.getBoundingBox();
        LongSet columns = new LongOpenHashSet();

        if (!extend.get()) {
            columns.add(BlockPos.asLong(MathHelper.floor(mc.player.getX()), y, MathHelper.floor(mc.player.getZ())));
            return columns;
        }

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
