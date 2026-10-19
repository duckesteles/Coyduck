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
import net.minecraft.entity.ItemEntity;
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

    private static final float CLEAR_PENALTY = 2;

    private static final double PLACE_RANGE = 5.5;

    public enum Mode {
        Auto,
        Head,
        Side,
        Trap
    }

    private record Plan(BlockPos block, BlockPos clear, Mode mode, LongSet columns, float damage) {}

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
        .description("Most damage a cycle may deal to you. The crystal sits two blocks above the target, so a few blocks away it often deals 10 or more. A cycle that would pop or kill you never fires.")
        .defaultValue(12)
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

    private final Setting<Boolean> debug = sgGeneral.add(new BoolSetting.Builder()
        .name("debug")
        .description("Tells you in chat what the module is waiting for.")
        .defaultValue(false)
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

    private static final long STUCK_TIME = 2_000;
    private static final int MAX_REJECTED = 2;

    private PlayerEntity target;
    private Plan plan;
    private long releaseAt;
    private String status;
    private long stuckSince;
    private long crystalSentAt;
    private int rejected;
    private long firedAt;
    private EndCrystalEntity firedCrystal;
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
        if (debug.get()) return status;
        return plan == null ? null : plan.mode.name();
    }

    private void status(String text) {
        if (text.equals(status)) return;

        String kind = text.split(",", 2)[0];
        boolean changed = status == null || !status.split(",", 2)[0].equals(kind);
        status = text;
        if (debug.get() && changed) info(text);
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
        if (releaseAt != 0 && Util.getMeasuringTimeMs() >= releaseAt) {
            releaseAt = 0;
            pass();
        }

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
        target = Targets.find(targetRange.get(), plan != null ? target : null);
        if (target == null) {
            clear();
            status("No target");
            return;
        }

        Box box = Targets.box(target);
        LongSet columns = Targets.columns(box);

        if (plan != null && !valid(plan, columns)) clear();
        if (plan == null) {
            plan = choose(box, columns);
            if (plan == null) {
                status("No safe cev spot");
                return;
            }
            stuckSince = 0;
            CrystalTracker.get().reserve(plan.block, this);
        }

        if (!hasMaterials()) {
            clear();
            status("Out of obsidian or crystals");
            return;
        }

        MiningManager mining = MiningManager.get();
        BlockPos above = plan.block.up();
        if (!mc.world.getBlockState(above).isAir()) {
            mining.mine(this, PRIORITY, above, true, switchMode.get(), swing.get());
            status("Mining the block on the cev spot");
            return;
        }

        if (!mining.mine(this, PRIORITY, plan.block, false, switchMode.get(), swing.get())) {
            status("Another module is mining");
            return;
        }

        BlockState state = mc.world.getBlockState(plan.block);

        if (state.isReplaceable()) {
            int lead = LatencyManager.get().rtt() / 50 + 2;
            int left = mining.remainingTicks(Blocks.OBSIDIAN.getDefaultState());
            if (left > lead) {
                status("Arming, " + left + " ticks");
                return;
            }
            if (!placeObsidian()) {
                status("Cannot place obsidian");
                return;
            }
        }

        state = mc.world.getBlockState(plan.block);
        if (!Targets.isBase(state)) {
            mining.stop(this);
            status("Mining their block, " + mining.remainingTicks() + " ticks");
            return;
        }

        verifyFire();

        EndCrystalEntity crystal = crystal();
        if (crystal == null) {
            placeCrystalTimed(box);
            return;
        }
        crystalSentAt = 0;
        rejected = 0;

        if (!mining.isReady()) {
            status("Mining, " + mining.remainingTicks() + " ticks");
            return;
        }

        Vec3d center = crystal.getEntityPos();
        BlockOverlay view = new BlockOverlay(mc.world).set(plan.block, Blocks.AIR.getDefaultState());
        if (!Targets.safe(center, view, maxSelfDamage.get())) {
            float self = ExplosionDamage.crystal(mc.player, mc.player.getEntityPos(), mc.player.getBoundingBox(), center, view);
            blocked(String.format("Too dangerous, %.1f damage to you", self));
            return;
        }

        float raw = ExplosionDamage.crystalRaw(target, Targets.feet(box), box, center, view);
        float damage = ExplosionDamage.mitigate(target, raw);
        float health = target.getHealth() + target.getAbsorptionAmount();
        if (damage < minDamage.get() && damage < health) {
            blocked(String.format("Too weak, %.1f damage", damage));
            return;
        }

        if (hurtSync.get()) {
            long arrival = Util.getMeasuringTimeMs() + LatencyManager.get().rtt() / 2;
            float effective = HurtTracker.get().effective(target, raw, arrival);
            if (effective < minDamage.get() && effective < health) {
                releaseAt = HurtTracker.get().releaseTime(target);
                status("Waiting for damage immunity to end");
                return;
            }
        }

        releaseAt = 0;
        stuckSince = 0;
        fire(crystal, raw);
    }

    private void blocked(String reason) {
        status(reason);

        long now = Util.getMeasuringTimeMs();
        if (stuckSince == 0) stuckSince = now;
        else if (now - stuckSince >= STUCK_TIME) clear();
    }

    private void placeCrystalTimed(Box box) {
        int lead = LatencyManager.get().rtt() / 50 + 2;
        int left = MiningManager.get().remainingTicks();
        if (left > lead) {
            status("Mining, " + left + " ticks");
            return;
        }

        if (hurtSync.get()) {
            long release = HurtTracker.get().releaseTime(target);
            long spawn = Util.getMeasuringTimeMs() + LatencyManager.get().rtt();
            if (release > spawn) {
                releaseAt = release - LatencyManager.get().rtt();
                status("Timing the crystal to damage immunity");
                return;
            }
        }

        if (!Targets.canHoldCrystal(mc.world, plan.block)) {
            blocked("Crystal spot is blocked by " + blocker());
            return;
        }

        long now = Util.getMeasuringTimeMs();
        if (crystalSentAt != 0 && now - crystalSentAt > LatencyManager.get().timeout()) {
            crystalSentAt = 0;
            if (++rejected >= MAX_REJECTED) {
                status("Server rejected the crystal twice, replanning");
                clear();
                return;
            }
        }

        if (crystalSentAt == 0) {
            placeCrystal();
            crystalSentAt = now;
        }
        status("Waiting for the crystal to spawn");
    }

    private String blocker() {
        BlockPos above = plan.block.up();
        if (!mc.world.getBlockState(above).isAir()) return "a block";

        Box box = new Box(above.getX(), above.getY(), above.getZ(), above.getX() + 1, above.getY() + 2, above.getZ() + 1);
        for (Entity entity : mc.world.getOtherEntities(null, box)) {
            if (entity instanceof EndCrystalEntity && !CrystalTracker.get().isAlive(entity)) continue;
            return entity instanceof ItemEntity ? "a dropped item" : entity.getName().getString();
        }
        return "something";
    }

    private void verifyFire() {
        if (firedAt == 0) return;

        long now = Util.getMeasuringTimeMs();
        if (now - firedAt < LatencyManager.get().timeout()) return;

        if (firedCrystal != null && !firedCrystal.isRemoved()) status("Server did not destroy the crystal");
        firedAt = 0;
        firedCrystal = null;
    }

    private void fire(EndCrystalEntity crystal, float raw) {
        if (!MiningManager.get().stop(this)) {
            status("Break was not sent");
            return;
        }
        if (!InteractionManager.get().attack(crystal, switchMode.get(), swing.get())) {
            status("Cannot hit the crystal");
            return;
        }

        CrystalTracker.get().explodeAt(crystal.getEntityPos());
        HurtTracker.get().expect(target, raw);
        status("Fired");
        firedAt = Util.getMeasuringTimeMs();
        firedCrystal = crystal;
        crystalSentAt = 0;
        rejected = 0;

        placeObsidian();
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

            BlockPos above = block.up();
            BlockPos clear = mc.world.getBlockState(above).isAir() ? null : above;

            BlockOverlay view = new BlockOverlay(mc.world).set(block, Blocks.AIR.getDefaultState());
            if (clear != null) view.set(clear, Blocks.AIR.getDefaultState());
            Vec3d center = Targets.crystalCenter(block);
            if (!Targets.safe(center, view, maxSelfDamage.get())) continue;

            float damage = ExplosionDamage.crystal(target, feet, box, center, view);
            float health = target.getHealth() + target.getAbsorptionAmount();
            if (damage < minDamage.get() && damage < health) continue;

            float score = clear == null ? damage : damage - CLEAR_PENALTY;
            if (best == null || score > best.damage + 0.5f) best = new Plan(block, clear, candidate.mode, columns, score);
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
        if (Targets.ownRing().contains(block)) return false;
        if (new Box(block).squaredMagnitude(mc.player.getEyePos()) > PLACE_RANGE * PLACE_RANGE) return false;
        if (CrystalTracker.get().isReserved(block) && !CrystalTracker.get().isReservedBy(block, this)) return false;

        BlockState state = mc.world.getBlockState(block);
        if (state.isOf(Blocks.BEDROCK)) return false;

        BlockPos above = block.up();
        if (!mc.world.getBlockState(above).isAir() && !clearable(above)) return false;
        if (!mc.world.getBlockState(above.up()).isAir()) return false;
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

        BlockPos above = plan.block.up();
        return mc.world.getBlockState(above).isAir() || clearable(above);
    }

    private boolean clearable(BlockPos pos) {
        BlockState state = mc.world.getBlockState(pos);
        return !state.isOf(Blocks.BEDROCK) && state.getHardness(mc.world, pos) >= 0 && MiningManager.get().inRange(pos);
    }

    private void clear() {
        releaseAt = 0;
        stuckSince = 0;
        crystalSentAt = 0;
        rejected = 0;
        firedAt = 0;
        firedCrystal = null;
        MiningManager.get().release(this);
        CrystalTracker.get().release(this);
        plan = null;
    }

    private boolean canAct() {
        return mc.player != null && mc.world != null && mc.interactionManager != null && mc.getNetworkHandler() != null
            && mc.player.isAlive() && !mc.player.isSpectator();
    }
}
