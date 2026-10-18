package dev.coyduck.modules;

import dev.coyduck.Coyduck;
import dev.coyduck.events.PacketApplyEvent;
import dev.coyduck.managers.CrystalTracker;
import dev.coyduck.managers.HurtTracker;
import dev.coyduck.managers.InteractionManager;
import dev.coyduck.managers.InventoryManager;
import dev.coyduck.managers.LatencyManager;
import dev.coyduck.managers.MiningManager;
import dev.coyduck.utils.BlockOverlay;
import dev.coyduck.utils.ExplosionDamage;
import dev.coyduck.utils.Targets;
import it.unimi.dsi.fastutil.longs.LongSet;
import meteordevelopment.meteorclient.events.entity.EntityAddedEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Items;
import net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ChunkDeltaUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.EntitiesDestroyS2CPacket;
import net.minecraft.network.packet.s2c.play.ExplosionS2CPacket;
import net.minecraft.util.Util;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

public class CevBreaker extends Module {
    public static final int PRIORITY = 60;

    private static final double PLACE_RANGE = 5.5;

    public enum Mode {
        Auto,
        Head,
        Side,
        Trap
    }

    private record Plan(BlockPos block, Mode mode, LongSet columns, float damage) {}

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");

    private final Setting<Double> targetRange = sgGeneral.add(new DoubleSetting.Builder()
        .name("target-range")
        .description("Range to look for targets in.")
        .defaultValue(7)
        .range(1, 12)
        .sliderRange(1, 12)
        .build()
    );

    private final Setting<Mode> mode = sgGeneral.add(new EnumSetting.Builder<Mode>()
        .name("mode")
        .description("Head uses the block above the target's head, Side a surround block, Trap a block beside the head. Auto picks whichever hits hardest.")
        .defaultValue(Mode.Auto)
        .build()
    );

    private final Setting<Double> minDamage = sgGeneral.add(new DoubleSetting.Builder()
        .name("min-damage")
        .description("Least damage one cycle must deal to the target.")
        .defaultValue(4)
        .range(0, 36)
        .sliderRange(0, 36)
        .build()
    );

    private final Setting<Double> maxSelfDamage = sgGeneral.add(new DoubleSetting.Builder()
        .name("max-self-damage")
        .description("Most damage a cycle may deal to you. A cycle that would pop or kill you never fires.")
        .defaultValue(8)
        .range(0, 36)
        .sliderRange(0, 36)
        .build()
    );

    private final Setting<Boolean> hurtSync = sgGeneral.add(new BoolSetting.Builder()
        .name("hurt-sync")
        .description("Fires right as the target's damage immunity ends, so every cycle deals its full damage.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> airplace = sgGeneral.add(new BoolSetting.Builder()
        .name("airplace")
        .description("Places the obsidian without a neighbouring block to click on.")
        .defaultValue(true)
        .build()
    );

    private final Setting<InventoryManager.SwitchMode> switchMode = sgGeneral.add(new EnumSetting.Builder<InventoryManager.SwitchMode>()
        .name("switch")
        .description("How items are put in your hand.")
        .defaultValue(InventoryManager.SwitchMode.InvSwap)
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
        .description("Highlights the cev block.")
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
        .defaultValue(new SettingColor(255, 82, 82, 50))
        .visible(render::get)
        .build()
    );

    private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder()
        .name("line-color")
        .description("Highlight line color.")
        .defaultValue(new SettingColor(255, 82, 82, 220))
        .visible(render::get)
        .build()
    );

    private PlayerEntity target;
    private Plan plan;
    private boolean running;

    public CevBreaker() {
        super(Coyduck.CATEGORY, "cev-breaker", "Explodes a crystal on a block next to the target's head the instant that block breaks, then repeats with instant rebreak.");
    }

    @Override
    public void onActivate() {
        target = null;
        plan = null;
    }

    @Override
    public void onDeactivate() {
        clear();
        target = null;
    }

    @Override
    public String getInfoString() {
        return plan == null ? null : plan.mode.name();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        pass();
    }

    @EventHandler
    private void onEntityAdded(EntityAddedEvent event) {
        if (plan != null && event.entity instanceof EndCrystalEntity && event.entity.getBlockPos().down().equals(plan.block)) pass();
    }

    @EventHandler
    private void onPacket(PacketApplyEvent.Post event) {
        if (plan == null) return;

        boolean relevant = switch (event.packet) {
            case BlockUpdateS2CPacket packet -> packet.getPos().isWithinDistance(plan.block, 2);
            case ChunkDeltaUpdateS2CPacket ignored -> true;
            case EntitiesDestroyS2CPacket ignored -> true;
            case ExplosionS2CPacket ignored -> true;
            default -> false;
        };

        if (relevant) pass();
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (!render.get() || plan == null) return;
        event.renderer.box(plan.block, sideColor.get(), lineColor.get(), shapeMode.get(), 0);
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
        if (target == null) {
            clear();
            return;
        }

        Box box = Targets.box(target);
        LongSet columns = Targets.columns(box);

        if (plan != null && !valid(plan, columns)) clear();
        if (plan == null) {
            plan = choose(box, columns);
            if (plan == null) return;
            CrystalTracker.get().reserve(plan.block, this);
        }

        if (!hasMaterials()) {
            clear();
            return;
        }

        MiningManager mining = MiningManager.get();
        if (!mining.mine(this, PRIORITY, plan.block, false, switchMode.get(), swing.get())) return;

        BlockState state = mc.world.getBlockState(plan.block);

        if (state.isReplaceable()) {
            if (placeObsidian()) placeCrystal();
            return;
        }

        if (!Targets.isBase(state)) {
            mining.stop(this);
            return;
        }

        EndCrystalEntity crystal = crystal();
        if (crystal == null) {
            placeCrystal();
            return;
        }

        if (!mining.isReady()) return;

        Vec3d center = crystal.getEntityPos();
        BlockOverlay view = new BlockOverlay(mc.world).set(plan.block, Blocks.AIR.getDefaultState());
        if (!Targets.safe(center, view, maxSelfDamage.get())) return;

        float raw = ExplosionDamage.crystalRaw(target, Targets.feet(box), box, center, view);
        float damage = ExplosionDamage.mitigate(target, raw);
        float health = target.getHealth() + target.getAbsorptionAmount();
        if (damage < minDamage.get() && damage < health) return;

        if (hurtSync.get()) {
            long arrival = Util.getMeasuringTimeMs() + LatencyManager.get().rtt() / 2;
            float effective = HurtTracker.get().effective(target, raw, arrival);
            if (effective < minDamage.get() && effective < health) return;
        }

        fire(crystal, raw);
    }

    private void fire(EndCrystalEntity crystal, float raw) {
        if (!MiningManager.get().stop(this)) return;
        if (!InteractionManager.get().attack(crystal, switchMode.get(), swing.get())) return;

        CrystalTracker.get().kill(crystal, LatencyManager.get().timeout());
        HurtTracker.get().expect(target, raw);

        if (placeObsidian()) placeCrystal();
    }

    private boolean placeObsidian() {
        InteractionManager interaction = InteractionManager.get();
        BlockPos block = plan.block;
        if (!interaction.canPlace(block)) return Targets.isBase(mc.world.getBlockState(block));

        List<Entity> obstructions = interaction.obstructions(block);
        obstructions.removeIf(entity -> entity instanceof EndCrystalEntity && CrystalTracker.get().isDead(entity));
        if (!obstructions.isEmpty()) return false;

        int slot = InventoryManager.get().find(stack -> stack.getItem() instanceof BlockItem item && item.getBlock() == Blocks.OBSIDIAN, 0, InventoryManager.MAIN_END);
        if (slot == -1) return false;

        BlockHitResult hit = interaction.hit(block, airplace.get());
        if (hit == null) return false;

        InventoryManager.get().withItem(slot, switchMode.get(), () -> interaction.place(block, hit, swing.get()));
        return Targets.isBase(mc.world.getBlockState(block));
    }

    private void placeCrystal() {
        BlockPos block = plan.block;
        if (CrystalTracker.get().isPlacing(block) || !Targets.canHoldCrystal(mc.world, block)) return;
        if (InventoryManager.get().find(stack -> stack.isOf(Items.END_CRYSTAL), 0, InventoryManager.MAIN_END) == -1) return;

        InteractionManager.get().placeCrystal(block, switchMode.get(), swing.get(), null);
    }

    private boolean hasMaterials() {
        InventoryManager inventory = InventoryManager.get();
        if (inventory.find(stack -> stack.isOf(Items.END_CRYSTAL), 0, InventoryManager.MAIN_END) == -1) return false;
        if (Targets.isBase(mc.world.getBlockState(plan.block)) && crystal() != null) return true;
        return inventory.find(stack -> stack.isOf(Items.OBSIDIAN), 0, InventoryManager.MAIN_END) != -1;
    }

    private EndCrystalEntity crystal() {
        BlockPos above = plan.block.up();
        Box box = new Box(above);
        CrystalTracker tracker = CrystalTracker.get();

        for (Entity entity : mc.world.getOtherEntities(null, box, entity -> entity instanceof EndCrystalEntity)) {
            if (tracker.isAlive(entity) && entity.getBlockPos().equals(above)) return (EndCrystalEntity) entity;
        }
        return null;
    }

    private Plan choose(Box box, LongSet columns) {
        Vec3d feet = Targets.feet(box);
        Plan best = null;

        for (Candidate candidate : candidates(columns)) {
            BlockPos block = candidate.block;
            if (!usable(block)) continue;

            BlockOverlay view = new BlockOverlay(mc.world).set(block, Blocks.AIR.getDefaultState());
            Vec3d center = Targets.crystalCenter(block);
            if (!Targets.safe(center, view, maxSelfDamage.get())) continue;

            float damage = ExplosionDamage.crystal(target, feet, box, center, view);
            float health = target.getHealth() + target.getAbsorptionAmount();
            if (damage < minDamage.get() && damage < health) continue;

            if (best == null || damage > best.damage + 0.5f) best = new Plan(block, candidate.mode, columns, damage);
        }

        return best;
    }

    private record Candidate(BlockPos block, Mode mode) {}

    private List<Candidate> candidates(LongSet columns) {
        List<Candidate> candidates = new ArrayList<>();
        Mode selected = mode.get();

        if (selected == Mode.Auto || selected == Mode.Head) {
            for (long key : columns) candidates.add(new Candidate(BlockPos.fromLong(key).up(2), Mode.Head));
        }
        if (selected == Mode.Auto || selected == Mode.Side) {
            for (BlockPos pos : Targets.ring(columns, 0)) candidates.add(new Candidate(pos, Mode.Side));
        }
        if (selected == Mode.Auto || selected == Mode.Trap) {
            for (BlockPos pos : Targets.ring(columns, 1)) candidates.add(new Candidate(pos, Mode.Trap));
        }

        return candidates;
    }

    private boolean usable(BlockPos block) {
        if (!MiningManager.get().inRange(block)) return false;
        if (new Box(block).squaredMagnitude(mc.player.getEyePos()) > PLACE_RANGE * PLACE_RANGE) return false;
        if (CrystalTracker.get().isReserved(block) && !CrystalTracker.get().isReservedBy(block, this)) return false;

        BlockState state = mc.world.getBlockState(block);
        if (state.isOf(Blocks.BEDROCK)) return false;

        BlockPos above = block.up();
        if (!mc.world.getBlockState(above).isAir()) return false;
        Box crystalBox = new Box(above.getX(), above.getY(), above.getZ(), above.getX() + 1, above.getY() + 2, above.getZ() + 1);
        if (!mc.world.getOtherEntities(null, crystalBox, entity -> !(entity instanceof EndCrystalEntity)).isEmpty()) return false;

        if (state.isReplaceable()) {
            if (!InteractionManager.get().obstructions(block).isEmpty()) return false;
            return InteractionManager.get().hit(block, airplace.get()) != null;
        }

        return state.getHardness(mc.world, block) >= 0;
    }

    private boolean valid(Plan plan, LongSet columns) {
        if (!plan.columns.equals(columns)) return false;
        if (!MiningManager.get().inRange(plan.block)) return false;
        if (mc.world.getBlockState(plan.block).isOf(Blocks.BEDROCK)) return false;
        return mc.world.getBlockState(plan.block.up()).isAir();
    }

    private void clear() {
        MiningManager.get().release(this);
        CrystalTracker.get().release(this);
        plan = null;
    }

    private boolean canAct() {
        return mc.player != null && mc.world != null && mc.interactionManager != null && mc.getNetworkHandler() != null
            && mc.player.isAlive() && !mc.player.isSpectator();
    }
}
