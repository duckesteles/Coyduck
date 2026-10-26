package dev.coyduck.modules;

import dev.coyduck.Coyduck;
import dev.coyduck.events.PacketApplyEvent;
import dev.coyduck.managers.CrystalTracker;
import dev.coyduck.managers.HurtTracker;
import dev.coyduck.managers.InteractionManager;
import dev.coyduck.managers.InventoryManager;
import dev.coyduck.managers.LatencyManager;
import dev.coyduck.managers.MiningManager;
import dev.coyduck.managers.MiningTracker;
import dev.coyduck.utils.ExplosionDamage;
import dev.coyduck.utils.Targets;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import meteordevelopment.meteorclient.events.entity.EntityAddedEvent;
import meteordevelopment.meteorclient.events.entity.player.PlayerMoveEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.mixininterface.IVec3d;
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
import meteordevelopment.orbit.EventPriority;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.MovementType;
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
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraft.world.dimension.DimensionTypes;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class Surround extends Module {
    public enum Disable {
        Never,
        Leave
    }

    private static final long HOT_TIME = 2_000;
    private static final long RENDER_TIME = 500;
    private static final double EDGE = 1.0E-4;
    private static final double CENTER_SPEED = 0.2;
    private static final double NEAR = 5 * 5;
    private static final double FACE_ENEMY_RANGE = 12;
    private static final float FACE_MIN_DAMAGE = 2;

    private static final float ANCHOR_POWER = 5.0f;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgProtect = settings.createGroup("Protect");
    private final SettingGroup sgRender = settings.createGroup("Render");

    private final Setting<List<Block>> blocks = sgGeneral.add(new BlockListSetting.Builder()
        .name("blocks")
        .description("Blocks to surround with, in order of preference. Crying obsidian first, since crystals cannot be placed on it.")
        .defaultValue(Blocks.CRYING_OBSIDIAN, Blocks.OBSIDIAN, Blocks.ENDER_CHEST)
        .filter(block -> block.getBlastResistance() >= 600)
        .build()
    );

    private final Setting<InventoryManager.SwitchMode> switchMode = sgGeneral.add(new EnumSetting.Builder<InventoryManager.SwitchMode>()
        .name("switch")
        .description("Silent selects the item's hotbar slot for a moment. InvSwap swaps the item into your hand and back. Nothing is switched while you eat, so the eating is never cut off.")
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

    private final Setting<Boolean> extend = sgGeneral.add(new BoolSetting.Builder()
        .name("extend")
        .description("Surrounds every block you stand on. Off pulls you to the middle of your block and surrounds just that one.")
        .defaultValue(true)
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

    private final Setting<Boolean> allowPop = sgProtect.add(new BoolSetting.Builder()
        .name("allow-pop")
        .description("Also breaks a crystal in your surround that pops you, once Auto Totem+ has confirmed your offhand totem and you still have a spare. The enemy would explode it anyway, this way you pick the moment and close the gap.")
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

    private final Setting<Boolean> face = sgProtect.add(new BoolSetting.Builder()
        .name("face")
        .description("Covers the cells at head level around you once a crystal there could pop you or your health is low, so you cannot be face placed.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> faceHealth = sgProtect.add(new DoubleSetting.Builder()
        .name("face-health")
        .description("Health and absorption at or below which every dangerous head level cell is covered.")
        .defaultValue(10)
        .range(0, 36)
        .sliderRange(0, 36)
        .visible(face::get)
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
    private boolean canPlaceNow = true;

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

    @EventHandler(priority = EventPriority.HIGH)
    private void onMove(PlayerMoveEvent event) {
        if (extend.get() || event.type != MovementType.SELF || !canAct() || anchored.size() != 1 || HoleSnap.snapping()) return;
        if (!mc.player.isOnGround() || hasInput() || level() != anchorY) return;

        BlockPos column = BlockPos.fromLong(anchored.iterator().nextLong());
        double dx = column.getX() + 0.5 - mc.player.getX();
        double dz = column.getZ() + 0.5 - mc.player.getZ();
        double fit = 0.5 - mc.player.getWidth() / 2 - EDGE;
        if (Math.abs(dx) <= fit && Math.abs(dz) <= fit) return;

        ((IVec3d) event.movement).meteor$set(MathHelper.clamp(dx, -CENTER_SPEED, CENTER_SPEED), event.movement.y, MathHelper.clamp(dz, -CENTER_SPEED, CENTER_SPEED));
        mc.player.setVelocity(0, mc.player.getVelocity().y, 0);
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
        if (HoleSnap.snapping() || !updateAnchor() || budget <= 0) return;

        int slot = blockSlot();
        if (slot == -1) return;
        canPlaceNow = slot == InventoryManager.get().selectedSlot() || !InventoryManager.get().isEating();

        InteractionManager interaction = InteractionManager.get();
        List<BlockPos> placeable = new ArrayList<>();
        LongSet retried = new LongOpenHashSet();

        MiningManager mining = MiningManager.get();
        int cost = 0;
        for (BlockPos pos : targets()) {
            if (cost >= budget) break;
            boolean retry = interaction.isPending(pos) && liveCrystalIn(pos);
            if ((!retry && !interaction.canPlace(pos)) || mining.isMining(pos)) continue;

            int needed = cost(pos);
            if (needed == 0 || cost + needed > budget) continue;
            if (!clear(pos)) continue;

            if (retry) {
                interaction.forget(pos);
                retried.add(pos.asLong());
            }
            placeable.add(pos);
            cost += needed;
        }

        if (placeable.isEmpty()) return;

        InventoryManager.get().withItemWhileEating(slot, switchMode.get(), () -> {
            for (BlockPos pos : placeable) {
                if (budget <= 0 || !isBlock(mc.player.getMainHandStack())) break;
                place(pos, retried.contains(pos.asLong()));
            }
        });
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
        if (interaction.hit(pos, airplace.get()) != null) return 1;
        if (airplace.get()) return 0;

        BlockPos support = pos.down();
        return interaction.canPlace(support) && interaction.obstructions(support).isEmpty() && interaction.hit(support, false) != null ? 2 : 0;
    }

    private void place(BlockPos pos, boolean force) {
        InteractionManager interaction = InteractionManager.get();
        if (!force && !interaction.canPlace(pos)) return;

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

        EndCrystalEntity safest = null;
        Verdict safestVerdict = Verdict.Unsafe;
        for (Entity entity : entities) {
            if (!(entity instanceof EndCrystalEntity crystal)) return false;
            if (interaction.recentlyAttacked(crystal)) return true;

            Verdict verdict = verdict(crystal);
            if (verdict.ordinal() < safestVerdict.ordinal()) {
                safest = crystal;
                safestVerdict = verdict;
            }
        }

        if (safest == null || !interaction.attack(safest, switchMode.get(), swing.get())) return false;

        CrystalTracker.get().explodeAt(safest.getEntityPos());
        if (safestVerdict == Verdict.Pop) AutoTotem.notePop();
        return true;
    }

    private enum Verdict {
        Safe,
        Pop,
        Unsafe
    }

    private Verdict verdict(EndCrystalEntity crystal) {
        float raw = ExplosionDamage.crystalRaw(mc.player, mc.player.getEntityPos(), mc.player.getBoundingBox(), crystal.getEntityPos(), mc.world);
        long arrival = Util.getMeasuringTimeMs() + LatencyManager.get().rtt() / 2;
        float damage = HurtTracker.get().effectiveOnSelf(raw, arrival);

        float health = AutoTotem.popInFlight() ? 1.0f : mc.player.getHealth() + mc.player.getAbsorptionAmount();
        if (damage < health) return Verdict.Safe;
        return allowPop.get() && canPlaceNow && AutoTotem.canAffordPop() ? Verdict.Pop : Verdict.Unsafe;
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

        List<BlockPos> faces = faces();

        Set<BlockPos> targets = new LinkedHashSet<>();
        targets.addAll(threatened);
        targets.addAll(floors);
        targets.addAll(sides);
        targets.addAll(faces);
        targets.addAll(blockers);
        return new ArrayList<>(targets);
    }

    private List<BlockPos> faces() {
        List<BlockPos> faces = new ArrayList<>();
        if (!face.get() || Targets.find(FACE_ENEMY_RANGE) == null) return faces;

        float health = mc.player.getHealth() + mc.player.getAbsorptionAmount();
        for (long key : anchored) {
            BlockPos column = BlockPos.fromLong(key);
            for (Direction direction : Direction.Type.HORIZONTAL) {
                BlockPos side = column.offset(direction);
                if (anchored.contains(side.asLong())) continue;

                BlockPos cell = side.up();
                if (!faces.contains(cell) && faceThreat(cell, health)) faces.add(cell);
            }
        }
        return faces;
    }

    private boolean faceThreat(BlockPos cell, float health) {
        if (!mc.world.getBlockState(cell).isReplaceable()) return false;

        float damage = 0;
        if (Targets.isBase(mc.world.getBlockState(cell.down()))) {
            Vec3d crystal = new Vec3d(cell.getX() + 0.5, cell.getY(), cell.getZ() + 0.5);
            damage = ExplosionDamage.crystal(mc.player, mc.player.getEntityPos(), mc.player.getBoundingBox(), crystal, mc.world);
        }
        if (mc.world.getRegistryKey() != World.NETHER && !mc.world.getDimensionEntry().matchesKey(DimensionTypes.THE_NETHER)) {
            damage = Math.max(damage, ExplosionDamage.explosion(mc.player, mc.player.getEntityPos(), mc.player.getBoundingBox(), Vec3d.ofCenter(cell), ANCHOR_POWER, mc.world));
        }
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
        return mining.stage(pos) >= 0 || mining.wasBroken(pos, HOT_TIME);
    }

    private boolean updateAnchor() {
        int level = level();
        boolean ground = mc.player.isOnGround();

        if (level < anchorY) {
            if (hasInput() && columns(anchorY).longStream().noneMatch(anchored::contains)) return leave(level);

            anchorY = level;
            anchored = columns(level);
            return true;
        }

        if (ground && level > anchorY) return leave(level);

        LongSet current = columns(anchorY);
        if (current.longStream().noneMatch(anchored::contains)) {
            if (!ground) return true;
            if (hasInput()) return leave(level);

            anchored = current;
            return true;
        }

        if (anchored.containsAll(current)) anchored = current;
        else if (!hasInput()) anchored.addAll(current);
        return true;
    }

    private boolean leave(int level) {
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
