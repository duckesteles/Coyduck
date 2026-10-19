package dev.coyduck.modules;

import dev.coyduck.Coyduck;
import dev.coyduck.events.PacketApplyEvent;
import dev.coyduck.managers.CrystalTracker;
import dev.coyduck.managers.InteractionManager;
import dev.coyduck.managers.InventoryManager;
import dev.coyduck.managers.MiningManager;
import dev.coyduck.managers.MiningTracker;
import dev.coyduck.utils.BlockOverlay;
import dev.coyduck.utils.ExplosionDamage;
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
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.BlockView;

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
    private static final double PLACE_RANGE = 5.5;
    private static final float MAX_DAMAGE_LOSS = 1.0f;
    private static final long BROKEN_TIME = 2_000;

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
        .description("Smart covers the blocks beside the target's head, except where a crystal on the surround below would still hurt them.")
        .defaultValue(Face.Smart)
        .build()
    );

    private final Setting<Double> keepDamage = sgGeneral.add(new DoubleSetting.Builder()
        .name("keep-damage")
        .description("Smart leaves a face block open while a crystal under it would deal at least this much.")
        .defaultValue(4)
        .range(0, 36)
        .sliderRange(0, 36)
        .visible(() -> face.get() == Face.Smart)
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
        if (targets.isEmpty()) return;

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

        if (top.get()) {
            for (long key : columns) {
                BlockPos pos = BlockPos.fromLong(key).up(2);
                if (placeable(pos)) positions.add(pos);
            }
        }

        if (face.get() != Face.Off) {
            Vec3d feet = Targets.feet(box);
            float before = Float.NaN;

            for (BlockPos pos : Targets.ring(columns, 1)) {
                if (!placeable(pos)) continue;

                if (face.get() == Face.Smart) {
                    BlockPos below = pos.down();
                    if (MiningManager.get().isMining(below) || MiningTracker.get().wasBroken(below, BROKEN_TIME)) continue;
                    if (keepsCrystal(pos, box, feet)) continue;

                    if (Float.isNaN(before)) before = bestDamage(box, feet, mc.world);
                    BlockOverlay view = new BlockOverlay(mc.world).set(pos, Blocks.OBSIDIAN.getDefaultState());
                    if (before - bestDamage(box, feet, view) > MAX_DAMAGE_LOSS) continue;
                }

                positions.add(pos);
            }
        }

        return positions;
    }

    private boolean placeable(BlockPos pos) {
        InteractionManager interaction = InteractionManager.get();
        if (!interaction.canPlace(pos)) return false;

        CrystalTracker tracker = CrystalTracker.get();
        if (tracker.isReserved(pos) || tracker.isReserved(pos.down())) return false;
        if (mc.player.getBoundingBox().intersects(new Box(pos))) return false;

        List<Entity> entities = interaction.obstructions(pos);
        entities.removeIf(entity -> entity instanceof EndCrystalEntity && tracker.isDead(entity));
        return entities.isEmpty();
    }

    private float bestDamage(Box box, Vec3d feet, BlockView view) {
        BlockPos center = BlockPos.ofFloored(feet);
        float best = 0;

        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int dy = -1; dy <= 0; dy++) {
                    BlockPos base = center.add(dx, dy, dz);
                    if (!Targets.canHoldCrystal(view, base)) continue;
                    if (new Box(base).squaredMagnitude(mc.player.getEyePos()) > PLACE_RANGE * PLACE_RANGE) continue;

                    float damage = ExplosionDamage.crystal(target, feet, box, Targets.crystalCenter(base), view);
                    if (damage > best) best = damage;
                }
            }
        }

        return best;
    }

    private boolean keepsCrystal(BlockPos pos, Box box, Vec3d feet) {
        BlockPos base = pos.down();
        if (!Targets.canHoldCrystal(mc.world, base)) return false;
        return ExplosionDamage.crystal(target, feet, box, Targets.crystalCenter(base), mc.world) >= keepDamage.get();
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
