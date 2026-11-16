package dev.coyduck.modules;

import dev.coyduck.Coyduck;
import dev.coyduck.events.MineStopEvent;
import dev.coyduck.events.PacketApplyEvent;
import dev.coyduck.events.TickEvent;
import dev.coyduck.events.WorldResetEvent;
import dev.coyduck.managers.CrystalTracker;
import dev.coyduck.managers.HurtTracker;
import dev.coyduck.managers.InteractionManager;
import dev.coyduck.managers.InventoryManager;
import dev.coyduck.managers.LatencyManager;
import dev.coyduck.managers.MiningManager;
import dev.coyduck.managers.MotionTracker;
import dev.coyduck.managers.RotationManager;
import dev.coyduck.utils.BlockOverlay;
import dev.coyduck.utils.DuckRender;
import dev.coyduck.utils.ExplosionDamage;
import dev.coyduck.utils.PacketBatch;
import dev.coyduck.utils.Targets;
import dev.coyduck.utils.Trace;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import meteordevelopment.meteorclient.events.render.Render2DEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.combat.AnchorAura;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.RespawnAnchorBlock;
import net.minecraft.block.ShapeContext;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ChunkDeltaUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ExplosionS2CPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.Util;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.dimension.DimensionTypes;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

public class AutoAnchor extends Module {
    private static final float POWER = 5.0f;
    private static final double RANGE = POWER * 2.0;
    private static final int PRIORITY = 100;
    private static final int SEARCH = 2;
    private static final int SEARCH_UP = 3;
    private static final int MAX_PREDICT_TICKS = 6;
    private static final float LETHAL_BONUS = 100.0f;
    private static final float FOCUS_BONUS = 1.0f;
    private static final float AIM_BONUS = 1.0f;
    private static final float DEFENSE_THREAT = 4.0f;
    private static final float DEFENSE_FREE = 0.5f;
    private static final float LETHAL_DEFENSE_RAW_MARGIN = 2.0f;
    private static final long LETHAL_DEFENSE_TIME_MARGIN = 100;
    private static final double KNOCKBACK_LIMIT = 0.4;
    private static final float HARMLESS = 0.5f;
    private static final float FRIEND_LIMIT = 1.0f;
    private static final double RAY_SLACK = 2.0;
    private static final float FIRE_MARGIN = 1.0f;
    private static final float CHARGED_TIE = 1.0f;
    private static final float SELF_WEIGHT = 0.05f;
    private static final byte UNKNOWN = 0;
    private static final byte NO = 1;
    private static final byte YES = 2;
    private static final List<Box> UNIT = List.of(new Box(0, 0, 0, 1, 1, 1));
    private static final int MAX_TARGETS = 64;
    private static final int MAIN_HAND = -2;
    private static final int NONE = -1;
    private static final int MISSES_TO_PAUSE = 2;
    private static final long REJECT_PAUSE = 750;

    private record Target(PlayerEntity player, Box box, Vec3d feet, BlockPos block, float health, float minDamage) {}

    private record Choice(BlockPos pos, Target target, float damage, float self, float score, boolean charged) {
        float rank() {
            return score + (charged ? CHARGED_TIE : 0) - SELF_WEIGHT * self;
        }

        boolean beats(Choice other) {
            return other == null || rank() > other.rank();
        }
    }

    private record Danger(BlockPos pos, Vec3d center, BlockOverlay view, float raw, float full) {}

    private record Saved(Danger threat, Choice now) {}

    private record Spot(BlockPos pos, boolean charged, float potential, float timedPotential, long reachable, long timedReachable) {}

    private static final class Raws {
        final float[] target;
        float self = Float.NaN;
        byte friend = UNKNOWN;
        byte costly = UNKNOWN;

        Raws(int targets) {
            target = new float[targets];
            Arrays.fill(target, Float.NaN);
        }
    }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgDamage = settings.createGroup("Damage");
    private final SettingGroup sgRotate = settings.createGroup("Rotate");
    private final SettingGroup sgRender = settings.createGroup("Render");

    private final Setting<Double> targetRange = sgGeneral.add(new DoubleSetting.Builder()
        .name("target-range")
        .description("Range to look for targets in.")
        .defaultValue(12)
        .range(1, 20)
        .sliderRange(1, 20)
        .build()
    );

    private final Setting<Double> placeRange = sgGeneral.add(new DoubleSetting.Builder()
        .name("place-range")
        .description("Place range from your eyes, in blocks.")
        .defaultValue(4.5)
        .range(1, 5.5)
        .sliderRange(1, 5.5)
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
        .description("Places anchors without a neighbouring block to click on.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> hurtSync = sgGeneral.add(new BoolSetting.Builder()
        .name("hurt-sync")
        .description("Waits for the target's damage immunity to end.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> predict = sgGeneral.add(new BoolSetting.Builder()
        .name("predict")
        .description("Aims where targets will be when packets arrive.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> defend = sgGeneral.add(new BoolSetting.Builder()
        .name("defend")
        .description("Sets off charged anchors near you while you are immune.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> pauseMining = sgGeneral.add(new BoolSetting.Builder()
        .name("pause-mining")
        .description("Pauses while you mine a block.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> pauseEating = sgGeneral.add(new BoolSetting.Builder()
        .name("pause-eating")
        .description("Pauses while you eat.")
        .defaultValue(false)
        .build()
    );

    private final Setting<InteractionManager.SwingMode> swing = sgGeneral.add(new EnumSetting.Builder<InteractionManager.SwingMode>()
        .name("swing")
        .description("How the hand swing is shown.")
        .defaultValue(InteractionManager.SwingMode.Packet)
        .build()
    );

    private final Setting<Boolean> showStatus = sgGeneral.add(new BoolSetting.Builder()
        .name("show-status")
        .description("Shows why the last anchor was or wasn't used.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> minDamage = sgDamage.add(new DoubleSetting.Builder()
        .name("min-damage")
        .description("Least damage an anchor must deal to the target.")
        .defaultValue(6)
        .range(0, 36)
        .sliderRange(0, 36)
        .build()
    );

    private final Setting<Double> maxSelfDamage = sgDamage.add(new DoubleSetting.Builder()
        .name("max-self-damage")
        .description("Most damage an anchor may deal to you.")
        .defaultValue(8)
        .range(0, 36)
        .sliderRange(0, 36)
        .build()
    );

    private final Setting<Double> minHealth = sgDamage.add(new DoubleSetting.Builder()
        .name("min-health")
        .description("Least health you keep after an anchor's self damage.")
        .defaultValue(12)
        .range(0, 36)
        .sliderRange(0, 20)
        .build()
    );

    private final Setting<Double> facePlaceHealth = sgDamage.add(new DoubleSetting.Builder()
        .name("face-place-health")
        .description("Face places at or below this target health.")
        .defaultValue(10)
        .range(0, 36)
        .sliderRange(0, 36)
        .build()
    );

    private final Setting<Double> facePlaceDamage = sgDamage.add(new DoubleSetting.Builder()
        .name("face-place-damage")
        .description("Minimum damage while face placing.")
        .defaultValue(2)
        .range(0, 36)
        .sliderRange(0, 10)
        .build()
    );

    private final Setting<Boolean> rotate = sgRotate.add(new BoolSetting.Builder()
        .name("rotate")
        .description("Looks at every anchor before using it.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> rotateSpeed = sgRotate.add(new DoubleSetting.Builder()
        .name("speed")
        .description("Most degrees turned per tick.")
        .defaultValue(180)
        .range(1, 180)
        .sliderRange(1, 180)
        .visible(rotate::get)
        .build()
    );

    private final Setting<Boolean> render = sgRender.add(new BoolSetting.Builder()
        .name("render")
        .description("Highlights where anchors are used.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> damageText = sgRender.add(new BoolSetting.Builder()
        .name("damage-text")
        .description("Writes the damage the anchor deals to the target on its box.")
        .defaultValue(true)
        .visible(render::get)
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
        .defaultValue(new SettingColor(230, 140, 40, 40))
        .visible(render::get)
        .build()
    );

    private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder()
        .name("line-color")
        .description("Highlight line color.")
        .defaultValue(new SettingColor(230, 140, 40, 200))
        .visible(render::get)
        .build()
    );

    private final Long2LongOpenHashMap used = new Long2LongOpenHashMap();
    private final Long2LongOpenHashMap awaiting = new Long2LongOpenHashMap();
    private final Long2ObjectOpenHashMap<Raws> raws = new Long2ObjectOpenHashMap<>();
    private final Long2ObjectOpenHashMap<BlockHitResult> faces = new Long2ObjectOpenHashMap<>();
    private final List<Saved> saved = new ArrayList<>();
    private final DuckRender.Slide slide = new DuckRender.Slide();
    private List<BlockPos> targetBlocks = List.of();
    private List<Vec3d> ownCrystals;
    private PlayerEntity focus;
    private BlockPos aimSpot;
    private String status;
    private long releaseAt;
    private long pauseUntil;
    private int misses;
    private float renderDamage;
    private boolean running;
    private boolean spent;
    private boolean pending;
    private boolean rejectedSelf;
    private boolean rejectedFriend;

    public AutoAnchor() {
        super(Coyduck.CATEGORY, "auto-anchor", "Places and blows up respawn anchors.");
    }

    @Override
    public void onActivate() {
        Module meteor = Modules.get().get(AnchorAura.class);
        if (meteor != null && meteor.isActive()) {
            meteor.toggle();
            info("Disabled %s, it uses the same anchors.", meteor.title);
        }
        reset();
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
        used.clear();
        awaiting.clear();
        raws.clear();
        faces.clear();
        slide.reset();
        targetBlocks = List.of();
        ownCrystals = null;
        focus = null;
        aimSpot = null;
        status = null;
        releaseAt = 0;
        pauseUntil = 0;
        misses = 0;
        renderDamage = 0;
        spent = false;
        pending = false;
    }

    @Override
    public String getInfoString() {
        String name = focus == null ? null : focus.getName().getString();
        if (!showStatus.get() || status == null) return name;
        return name == null ? status : name + " " + status;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        spent = false;
        if (mc.player == null || mc.world == null) return;
        long now = Util.getMeasuringTimeMs();
        used.long2LongEntrySet().removeIf(entry -> now >= entry.getLongValue());
        countMisses(now);
        if (focus != null && !Targets.valid(focus, targetRange.get())) focus = null;
        pass();
    }

    @EventHandler
    private void onPacket(PacketApplyEvent.Post event) {
        if (mc.player == null || mc.world == null) return;

        switch (event.packet) {
            case BlockUpdateS2CPacket packet when matters(packet.getPos(), packet.getState()) -> pending = true;
            case ChunkDeltaUpdateS2CPacket packet -> packet.visitUpdates((pos, state) -> {
                if (matters(pos, state)) pending = true;
            });
            case ExplosionS2CPacket packet -> {
                heard(packet.center());
                if (near(packet.center())) pending = true;
            }
            default -> {}
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    private void onMineStop(MineStopEvent event) {
        if (running || spent || !canAct() || anchorsWork() || Util.getMeasuringTimeMs() < pauseUntil) return;
        if (mc.player.isSneaking() || mc.player.getOffHandStack().isOf(Items.GLOWSTONE)) return;
        if ((pauseEating.get() && mc.player.isUsingItem()) || InventoryManager.get().isOffhandEating()) return;

        BlockPos gap = event.pos.toImmutable();
        InteractionManager interaction = InteractionManager.get();
        if (used.containsKey(gap.asLong()) || ownCells().contains(gap.asLong()) || !inRange(gap)) return;
        if (CrystalTracker.get().placingOverlaps(gap.down()) || interaction.isPending(gap)) return;

        int anchorSlot = slot(Items.RESPAWN_ANCHOR);
        int glowSlot = slot(Items.GLOWSTONE);
        if (anchorSlot == -1 || glowSlot == -1 || detonator() == NONE) return;

        running = true;
        try {
            raws.clear();
            faces.clear();
            anchorOnStop(gap, anchorSlot, glowSlot);
        } finally {
            raws.clear();
            faces.clear();
            running = false;
        }
    }

    private void anchorOnStop(BlockPos gap, int anchorSlot, int glowSlot) {
        List<Target> targets = targets();
        if (targets.isEmpty() || blocksTarget(targets, gap)) return;

        float health = AutoTotem.popInFlight() ? 1.0f + FIRE_MARGIN : mc.player.getHealth() + mc.player.getAbsorptionAmount();
        long arrival = Util.getMeasuringTimeMs() + LatencyManager.get().rtt() / 2;
        long timing = hurtSync.get() ? arrival : 0;
        ownCrystals = ownCrystals();

        Spot spot = spot(targets, gap, false, timing);
        long reachable = spot == null ? 0 : timing != 0 ? spot.timedReachable : spot.reachable;
        if (reachable == 0) return;
        Choice choice = evaluate(targets, gap, health, timing, arrival, false, reachable);
        if (choice == null) return;

        InteractionManager interaction = InteractionManager.get();
        BlockHitResult placeHit = interaction.hit(gap, airplace.get());
        BlockHitResult anchorHit = blindFace(gap);
        if (placeHit == null || anchorHit == null || !rotationReady(placeHit)) return;

        boolean[] done = {false};
        PacketBatch.run(() -> {
            if (!withItem(anchorSlot, () -> interaction.interactBlind(placeHit, swing.get()))) return;
            if (!withItem(glowSlot, () -> interaction.interactBlind(anchorHit, swing.get()))) return;
            Runnable boom = () -> interaction.interactBlind(anchorHit, swing.get());
            int slot = detonator();
            if (slot == MAIN_HAND) {
                boom.run();
                done[0] = true;
            } else {
                done[0] = slot != NONE && withItem(slot, boom);
            }
        });
        if (!done[0]) {
            Trace.log("anchor on stop at {} could not switch items", gap);
            return;
        }

        interaction.notePending(gap);
        finish(gap, targets);
        mark(choice);
        Trace.log("anchor on stop {} for {} dmg", gap, choice.damage);
    }

    private BlockHitResult blindFace(BlockPos gap) {
        Vec3d eye = mc.player.getEyePos();
        double reach = mc.player.getBlockInteractionRange();
        InteractionManager interaction = InteractionManager.get();
        for (Direction direction : Direction.values()) {
            Vec3d normal = Vec3d.of(direction.getVector());
            if (eye.subtract(Vec3d.ofCenter(gap).add(normal.multiply(0.5))).dotProduct(normal) <= 0) continue;
            BlockPos behind = gap.offset(direction);
            if (mc.world.getBlockState(behind).isReplaceable() && interaction.obstructions(behind).isEmpty()) continue;
            Vec3d point = interaction.visiblePoint(gap, direction, eye, reach * reach);
            if (point != null) return new BlockHitResult(point, direction, gap, false);
        }
        return null;
    }

    @EventHandler
    private void onRender2D(Render2DEvent event) {
        if (!render.get() || !damageText.get() || renderDamage <= 0) return;
        DuckRender.label(slide.center(), String.format("%.1f", renderDamage), Color.WHITE, slide.alpha());
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (releaseAt != 0 && Util.getMeasuringTimeMs() >= releaseAt) {
            releaseAt = 0;
            pending = true;
        }
        if (pending) {
            pending = false;
            pass();
        }

        if (!render.get()) return;
        slide.render(event.renderer, sideColor.get(), lineColor.get(), shapeMode.get());
    }

    private boolean matters(BlockPos pos, BlockState state) {
        if (state.isOf(Blocks.RESPAWN_ANCHOR)) return near(Vec3d.ofCenter(pos));
        if (!state.isReplaceable()) return false;
        for (BlockPos target : targetBlocks) {
            if (Math.abs(pos.getX() - target.getX()) <= SEARCH + 1 && Math.abs(pos.getZ() - target.getZ()) <= SEARCH + 1
                && pos.getY() - target.getY() >= -2 && pos.getY() - target.getY() <= SEARCH_UP + 1) return true;
        }
        return false;
    }

    private void heard(Vec3d center) {
        BlockPos pos = BlockPos.ofFloored(center);
        if (awaiting.remove(pos.asLong()) != 0) misses = 0;
    }

    private void countMisses(long now) {
        var it = awaiting.long2LongEntrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            if (now < entry.getLongValue()) continue;
            long key = entry.getLongKey();
            it.remove();
            BlockPos pos = BlockPos.fromLong(key);
            used.remove(key);
            miss("no explosion came from " + pos + (charges(mc.world.getBlockState(pos)) > 0 ? ", the anchor is still charged" : ""));
        }
    }

    private void miss(String why) {
        Trace.log("anchor miss: {}", why);
        if (++misses < MISSES_TO_PAUSE) return;
        misses = 0;
        pauseUntil = Util.getMeasuringTimeMs() + REJECT_PAUSE;
        Trace.log("anchor pauses {}ms after misses in a row", REJECT_PAUSE);
    }

    private boolean near(Vec3d pos) {
        double range = placeRange.get() + RANGE;
        return mc.player.getEyePos().squaredDistanceTo(pos) <= range * range;
    }

    private void pass() {
        if (running || !canAct() || spent) return;

        running = true;
        try {
            raws.clear();
            faces.clear();
            run();
        } finally {
            raws.clear();
            faces.clear();
            running = false;
        }
    }

    private void run() {
        if (anchorsWork()) {
            status = "anchors set spawn here";
            return;
        }
        if (Util.getMeasuringTimeMs() < pauseUntil) {
            status = "rejected";
            return;
        }
        if (pauseMining.get() && mc.interactionManager.isBreakingBlock()) {
            status = "mining";
            return;
        }
        if (mc.player.isSneaking()) {
            status = "sneaking";
            return;
        }
        if (mc.player.getOffHandStack().isOf(Items.GLOWSTONE)) {
            status = "offhand glowstone";
            return;
        }
        if ((pauseEating.get() && mc.player.isUsingItem()) || InventoryManager.get().isOffhandEating()) {
            status = "eating";
            return;
        }

        List<Target> targets = targets();
        List<BlockPos> blocks = new ArrayList<>(targets.size());
        for (Target target : targets) blocks.add(target.block);
        targetBlocks = blocks;

        float health = AutoTotem.popInFlight() ? 1.0f + FIRE_MARGIN : mc.player.getHealth() + mc.player.getAbsorptionAmount();
        long now = Util.getMeasuringTimeMs();
        long arrival = now + LatencyManager.get().rtt() / 2;
        long timing = hurtSync.get() ? arrival : 0;
        ownCrystals = ownCrystals();
        rejectedSelf = false;
        rejectedFriend = false;

        List<BlockPos> charged = chargedAnchors();
        saved.clear();
        if (defend.get() && defend(targets, health, charged, arrival, timing)) return;
        rejectedSelf = false;
        rejectedFriend = false;

        if (targets.isEmpty()) {
            status = null;
            return;
        }

        int anchorSlot = slot(Items.RESPAWN_ANCHOR);
        int glowSlot = slot(Items.GLOWSTONE);

        List<Spot> spots = new ArrayList<>();
        for (BlockPos pos : charged) addSpot(spots, spot(targets, pos, true, timing));
        if (glowSlot != -1) {
            for (BlockPos pos : candidates(targets, anchorSlot != -1)) addSpot(spots, spot(targets, pos, false, timing));
        }

        Choice best = pick(spots, targets, health, timing, arrival);
        if (!saved.isEmpty() && (best == null || best.damage < best.target.health)) {
            Choice threat = null;
            for (Saved keep : saved) {
                if (keep.now != null && keep.now.beats(threat)) threat = keep.now;
            }
            if (threat != null) {
                if (Trace.on() && (best == null || !best.pos.equals(threat.pos))) Trace.log("anchor uses the threat at {} for offense instead of {}", threat.pos, best == null ? null : best.pos);
                best = threat;
            }
        }
        Choice held = null;
        if (best == null && timing != 0) held = pick(spots, targets, health, 0, arrival);

        if (best == null) {
            if (held != null) {
                status = "hurt-sync";
                long release = HurtTracker.get().releaseTime(held.target.player);
                if (release > 0 && (releaseAt == 0 || release < releaseAt)) releaseAt = release;
            } else if (charged.isEmpty() && glowSlot == -1) {
                status = anchorSlot == -1 ? "no anchors" : "no glowstone";
            } else if (spots.isEmpty()) {
                status = anchorSlot == -1 ? "no anchors" : "no spot";
            } else if (rejectedSelf) {
                status = "self damage";
            } else {
                status = rejectedFriend ? "friend" : "low damage";
            }
            return;
        }

        if (best.charged) detonate(best, targets);
        else useAnchor(best, targets, anchorSlot, glowSlot);
    }

    private static void addSpot(List<Spot> spots, Spot spot) {
        if (spot != null) spots.add(spot);
    }

    private Spot spot(List<Target> targets, BlockPos pos, boolean charged, long timing) {
        Vec3d center = Vec3d.ofCenter(pos);
        float bonus = pos.equals(aimSpot) ? AIM_BONUS : 0;
        float potential = -1;
        float timedPotential = -1;
        long reachable = 0;
        long timedReachable = 0;
        for (int i = 0; i < targets.size(); i++) {
            Target target = targets.get(i);
            float rawBound = ExplosionDamage.rawBound(target.player, target.feet, center, POWER);
            float bound = ExplosionDamage.mitigate(target.player, rawBound);
            if (bound < target.minDamage && bound < target.health) continue;
            float extra = bonus + (target.player == focus ? FOCUS_BONUS : 0);
            potential = Math.max(potential, bound + extra + (bound >= target.health ? LETHAL_BONUS : 0));
            reachable |= 1L << i;

            float timed = timing != 0 ? HurtTracker.get().effective(target.player, rawBound, timing) : bound;
            if (timed < target.minDamage && timed < target.health) continue;
            timedPotential = Math.max(timedPotential, timed + extra + (timed >= target.health ? LETHAL_BONUS : 0));
            timedReachable |= 1L << i;
        }
        return reachable == 0 ? null : new Spot(pos, charged, potential, timedPotential, reachable, timedReachable);
    }

    private Choice pick(List<Spot> spots, List<Target> targets, float health, long timing, long arrival) {
        boolean timed = timing != 0;
        List<Spot> order = new ArrayList<>(spots.size());
        for (Spot spot : spots) {
            if (!timed || spot.timedReachable != 0) order.add(spot);
        }
        order.sort(Comparator.comparingDouble((Spot spot) -> timed ? spot.timedPotential : spot.potential).reversed());

        InteractionManager interaction = InteractionManager.get();
        Choice best = null;
        for (Spot spot : order) {
            float potential = timed ? spot.timedPotential : spot.potential;
            if (best != null && potential + CHARGED_TIE <= best.rank()) break;
            Choice choice = evaluate(targets, spot.pos, health, timing, arrival, spot.charged, timed ? spot.timedReachable : spot.reachable);
            if (choice == null || !choice.beats(best)) continue;
            if (!spot.charged && !mc.world.getBlockState(spot.pos).isOf(Blocks.RESPAWN_ANCHOR) && interaction.hit(spot.pos, airplace.get()) == null) continue;
            best = choice;
        }
        return best;
    }

    private boolean defend(List<Target> targets, float health, List<BlockPos> charged, long arrival, long timing) {
        List<Danger> threats = new ArrayList<>();
        for (BlockPos pos : charged) {
            Vec3d center = Vec3d.ofCenter(pos);
            BlockOverlay view = overlay(pos);
            float raw = ExplosionDamage.raw(mc.player, mc.player.getEntityPos(), mc.player.getBoundingBox(), center, POWER, view);
            float full = ExplosionDamage.mitigate(mc.player, raw);
            if (full >= DEFENSE_THREAT) threats.add(new Danger(pos, center, view, raw, full));
        }
        threats.sort(Comparator.comparingDouble((Danger threat) -> threat.full).reversed());

        HurtTracker hurt = HurtTracker.get();
        for (Danger threat : threats) {
            if (hurt.effectiveOnSelf(threat.raw, arrival) > DEFENSE_FREE) continue;
            if (threat.full + FIRE_MARGIN >= health && hurt.effectiveOnSelf(threat.raw + LETHAL_DEFENSE_RAW_MARGIN, arrival + LETHAL_DEFENSE_TIME_MARGIN) > DEFENSE_FREE) continue;
            if (hurtsFriend(threat.center, threat.view, arrival) || destroysOwnCrystals(threat.center)) continue;
            if (launches(threat.center, threat.view)) continue;
            Saved keep = savedForOffense(targets, threat, health, timing, arrival);
            if (keep != null) {
                saved.add(keep);
                continue;
            }
            if (detonator() == NONE) {
                status = "no free slot";
                return false;
            }

            aimSpot = threat.pos;
            if (!rotationReady(threat.pos)) {
                status = "rotating";
                return true;
            }
            if (!click(threat.pos)) return false;

            finish(threat.pos, targets);
            status = "defend";
            Trace.log("anchor defend {} full {} raw {}", threat.pos, threat.full, threat.raw);
            return true;
        }
        return false;
    }

    private Saved savedForOffense(List<Target> targets, Danger threat, float health, long timing, long arrival) {
        Spot offense = spot(targets, threat.pos, true, timing);
        if (offense == null) return null;
        if (offense.timedReachable != 0) {
            Choice now = evaluate(targets, threat.pos, health, timing, arrival, true, offense.timedReachable);
            if (now != null) return new Saved(threat, now);
        }
        if (timing == 0) return null;

        Choice later = evaluate(targets, threat.pos, health, 0, arrival, true, offense.reachable);
        if (later == null) return null;
        long fires = Math.max(HurtTracker.get().releaseTime(later.target.player), Util.getMeasuringTimeMs()) + LatencyManager.get().rtt() / 2;
        return HurtTracker.get().effectiveOnSelf(threat.raw, fires) <= DEFENSE_FREE ? new Saved(threat, null) : null;
    }

    private boolean launches(Vec3d center, BlockOverlay view) {
        Box box = mc.player.getBoundingBox();
        double distance = mc.player.getEntityPos().distanceTo(center) / RANGE;
        if (distance >= 1) return false;

        double exposure = ExplosionDamage.exposure(center, box, view, ShapeContext.of(mc.player));
        double resistance = mc.player.getAttributeValue(EntityAttributes.EXPLOSION_KNOCKBACK_RESISTANCE);
        double knockback = (1 - distance) * exposure * (1 - resistance);
        if (knockback <= KNOCKBACK_LIMIT) return false;

        Vec3d push = mc.player.getEyePos().subtract(center).normalize().multiply(knockback);
        return mc.world.isSpaceEmpty(mc.player, box.stretch(push));
    }

    private void detonate(Choice choice, List<Target> targets) {
        if (detonator() == NONE) {
            status = "no free slot";
            return;
        }
        aimSpot = choice.pos;
        if (!rotationReady(choice.pos)) {
            status = "rotating";
            return;
        }
        if (!click(choice.pos)) {
            Trace.log("anchor detonate {} failed, retrying next tick", choice.pos);
            status = "switch";
            return;
        }

        finish(choice.pos, targets);
        mark(choice);
    }

    private void useAnchor(Choice choice, List<Target> targets, int anchorSlot, int glowSlot) {
        BlockPos pos = choice.pos;
        InteractionManager interaction = InteractionManager.get();
        boolean placed = mc.world.getBlockState(pos).isOf(Blocks.RESPAWN_ANCHOR);
        BlockHitResult placeHit = placed ? null : interaction.hit(pos, airplace.get());
        if (!placed && placeHit == null) {
            status = "no spot";
            return;
        }
        if (detonator() == NONE) {
            status = "no free slot";
            return;
        }
        aimSpot = pos;
        if (!(placed ? rotationReady(pos) : rotationReady(placeHit))) {
            status = "rotating";
            return;
        }

        boolean[] done = {false};
        PacketBatch.run(() -> {
            if (!placed && !withItem(anchorSlot, () -> interaction.place(pos, placeHit, swing.get()))) return;
            if (!mc.world.getBlockState(pos).isOf(Blocks.RESPAWN_ANCHOR)) return;

            BlockHitResult anchorHit = face(pos);
            if (!withItem(glowSlot, () -> {
                mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, anchorHit);
                interaction.swing(swing.get());
            })) return;
            if (charges(mc.world.getBlockState(pos)) == 0) return;

            done[0] = click(pos);
        });

        if (!done[0]) {
            BlockState left = mc.world.getBlockState(pos);
            if (left.isOf(Blocks.RESPAWN_ANCHOR) && charges(left) == 0) used.put(pos.asLong(), Util.getMeasuringTimeMs() + LatencyManager.get().timeout());
            Trace.log("anchor at {} not set off, left {} charge {}", pos, left.getBlock(), charges(left));
            status = "switch";
            return;
        }

        finish(pos, targets);
        mark(choice);
    }

    private boolean click(BlockPos pos) {
        BlockHitResult hit = face(pos);
        Runnable action = () -> {
            mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hit);
            InteractionManager.get().swing(swing.get());
        };

        int slot = detonator();
        if (slot == MAIN_HAND) {
            PacketBatch.run(action);
            return true;
        }
        return slot != NONE && withItem(slot, action);
    }

    private int detonator() {
        if (detonates(mc.player.getMainHandStack())) return MAIN_HAND;
        InventoryManager inventory = InventoryManager.get();
        int slot = inventory.find(ItemStack::isEmpty, 0, InventoryManager.HOTBAR_END);
        return slot != NONE ? slot : inventory.find(AutoAnchor::detonates, 0, InventoryManager.MAIN_END);
    }

    private static boolean detonates(ItemStack stack) {
        return !stack.isOf(Items.GLOWSTONE) && !(stack.getItem() instanceof BlockItem);
    }

    private boolean withItem(int slot, Runnable action) {
        InventoryManager inventory = InventoryManager.get();
        return pauseEating.get() ? inventory.withItem(slot, switchMode.get(), action) : inventory.withItemWhileEating(slot, switchMode.get(), action);
    }

    private void finish(BlockPos pos, List<Target> targets) {
        Vec3d center = Vec3d.ofCenter(pos);
        BlockOverlay view = overlay(pos);
        long now = Util.getMeasuringTimeMs();
        long timeout = LatencyManager.get().timeout();
        used.put(pos.asLong(), now + timeout);
        awaiting.put(pos.asLong(), now + timeout);
        CrystalTracker.get().explodeAt(center, RANGE);
        for (Target target : targets) {
            float hit = ExplosionDamage.raw(target.player, target.feet, target.box, center, POWER, view);
            if (hit > 0) HurtTracker.get().expect(target.player, hit);
        }
        spent = true;
        aimSpot = null;
        slide.show(pos);
    }

    private void mark(Choice choice) {
        if (Trace.on()) Trace.log("anchor {} {} at {} for {} dmg, self {}", choice.charged ? "detonate" : "place", choice.target.player.getName().getString(), choice.pos, choice.damage, choice.self);
        focus = choice.target.player;
        status = String.format("%.1f dmg", choice.damage);
        renderDamage = choice.damage;
    }

    private Raws raws(BlockPos pos, int targets) {
        return raws.computeIfAbsent(pos.asLong(), key -> new Raws(targets));
    }

    private Choice evaluate(List<Target> targets, BlockPos pos, float health, long timing, long arrival, boolean charged, long reachable) {
        Vec3d center = Vec3d.ofCenter(pos);
        Raws cached = raws(pos, targets.size());
        BlockOverlay view = null;
        if (cached.costly == UNKNOWN) cached.costly = destroysOwnCrystals(center) ? YES : NO;
        float bonus = pos.equals(aimSpot) ? AIM_BONUS : 0;

        Choice best = null;
        for (int i = 0; i < targets.size(); i++) {
            if ((reachable & 1L << i) == 0) continue;
            Target target = targets.get(i);

            if (Float.isNaN(cached.target[i])) {
                if (view == null) view = overlay(pos);
                cached.target[i] = ExplosionDamage.raw(target.player, target.feet, target.box, center, POWER, view);
            }
            float raw = cached.target[i];
            float damage = timing != 0 ? HurtTracker.get().effective(target.player, raw, timing) : ExplosionDamage.mitigate(target.player, raw);
            if (damage < target.minDamage && damage < target.health) continue;
            if (cached.costly == YES && damage < target.health) continue;

            float score = damage + bonus + (target.player == focus ? FOCUS_BONUS : 0) + (damage >= target.health ? LETHAL_BONUS : 0);
            Choice choice = new Choice(pos, target, damage, 0, score, charged);
            if (choice.beats(best)) best = choice;
        }
        if (best == null) return null;

        if (Float.isNaN(cached.self)) {
            if (view == null) view = overlay(pos);
            cached.self = ExplosionDamage.raw(mc.player, mc.player.getEntityPos(), mc.player.getBoundingBox(), center, POWER, view);
        }
        float full = ExplosionDamage.mitigate(mc.player, cached.self);
        float self = HurtTracker.get().effectiveOnSelf(cached.self, arrival);
        boolean finishing = best.damage >= best.target.health;
        if (full + FIRE_MARGIN >= health || self > maxSelfDamage.get() || self >= HARMLESS && !finishing && health - self - FIRE_MARGIN < minHealth.get()) {
            rejectedSelf = true;
            return null;
        }

        if (cached.friend == UNKNOWN) {
            if (view == null) view = overlay(pos);
            cached.friend = hurtsFriend(center, view, arrival) ? YES : NO;
        }
        if (cached.friend == YES) {
            rejectedFriend = true;
            return null;
        }

        return new Choice(pos, best.target, best.damage, self, best.score, charged);
    }

    private List<Vec3d> ownCrystals() {
        AutoCrystal autoCrystal = Modules.get().get(AutoCrystal.class);
        if (autoCrystal == null || !autoCrystal.isActive()) return null;

        CrystalTracker tracker = CrystalTracker.get();
        long age = 2L * LatencyManager.get().timeout();
        Box area = mc.player.getBoundingBox().expand(placeRange.get() + RANGE + 1);
        List<Vec3d> crystals = new ArrayList<>();
        for (EndCrystalEntity crystal : mc.world.getEntitiesByClass(EndCrystalEntity.class, area, crystal -> tracker.isRecentOwn(crystal, age) && tracker.isAlive(crystal))) {
            crystals.add(crystal.getEntityPos());
        }
        return crystals;
    }

    private boolean destroysOwnCrystals(Vec3d center) {
        if (ownCrystals == null) return false;
        if (CrystalTracker.get().placingNear(center, RANGE)) return true;

        double range = RANGE * RANGE;
        for (Vec3d crystal : ownCrystals) {
            if (crystal.squaredDistanceTo(center) <= range) return true;
        }
        return false;
    }

    private BlockOverlay overlay(BlockPos pos) {
        return new BlockOverlay(mc.world).set(pos, Blocks.AIR.getDefaultState());
    }

    private boolean hurtsFriend(Vec3d center, BlockOverlay view, long arrival) {
        double range = (RANGE + 1) * (RANGE + 1);
        for (PlayerEntity player : mc.world.getPlayers()) {
            if (player == mc.player || !player.isAlive() || !Friends.get().isFriend(player)) continue;

            Vec3d position = MotionTracker.get().position(player);
            if (position.squaredDistanceTo(center) > range) continue;
            float raw = ExplosionDamage.raw(player, position, Targets.box(player), center, POWER, view);
            if (raw <= 0) continue;

            float full = ExplosionDamage.mitigate(player, raw);
            float effective = HurtTracker.get().effective(player, raw, arrival);
            if (full + FIRE_MARGIN >= player.getHealth() + player.getAbsorptionAmount() || effective > FRIEND_LIMIT) return true;
        }
        return false;
    }

    private List<Target> targets() {
        List<Target> targets = new ArrayList<>();
        double range = targetRange.get() * targetRange.get();
        int ticks = predict.get() ? Math.min(MathHelper.ceil(LatencyManager.get().rtt() / 50.0), MAX_PREDICT_TICKS) : 0;

        for (PlayerEntity player : mc.world.getPlayers()) {
            if (player == mc.player || !player.isAlive() || Friends.get().isFriend(player)) continue;
            if (ExplosionDamage.isInvulnerable(player) || player.squaredDistanceTo(mc.player) > range) continue;

            Box box = MotionTracker.get().predict(player, ticks);
            Vec3d feet = new Vec3d((box.minX + box.maxX) / 2, box.minY, (box.minZ + box.maxZ) / 2);
            float health = player.getHealth() + player.getAbsorptionAmount();
            float min = health <= facePlaceHealth.get() ? facePlaceDamage.get().floatValue() : minDamage.get().floatValue();
            targets.add(new Target(player, box, feet, BlockPos.ofFloored(feet), health, min));
        }
        if (targets.size() > MAX_TARGETS) {
            targets.sort(Comparator.comparingDouble(target -> target.player.squaredDistanceTo(mc.player)));
            return new ArrayList<>(targets.subList(0, MAX_TARGETS));
        }
        return targets;
    }

    private List<BlockPos> candidates(List<Target> targets, boolean place) {
        InteractionManager interaction = InteractionManager.get();
        LongSet seen = new LongOpenHashSet();
        LongSet own = ownCells();
        List<BlockPos> candidates = new ArrayList<>();

        BlockPos.Mutable pos = new BlockPos.Mutable();
        for (Target target : targets) {
            BlockPos base = target.block;
            for (int dx = -SEARCH; dx <= SEARCH; dx++) {
                for (int dz = -SEARCH; dz <= SEARCH; dz++) {
                    for (int dy = -1; dy <= SEARCH_UP; dy++) {
                        long key = BlockPos.asLong(base.getX() + dx, base.getY() + dy, base.getZ() + dz);
                        if (!seen.add(key) || own.contains(key) || used.containsKey(key)) continue;

                        pos.set(key);
                        BlockState state = mc.world.getBlockState(pos);
                        if (state.isOf(Blocks.RESPAWN_ANCHOR)) {
                            if (charges(state) == 0 && inRange(pos)) candidates.add(pos.toImmutable());
                            continue;
                        }
                        if (!place || !state.isReplaceable() || !inRange(pos) || !interaction.canPlace(pos) || interaction.wasRejected(pos) || MiningManager.get().isMining(pos)) continue;
                        if (CrystalTracker.get().isPlacing(pos.down()) || blocksTarget(targets, pos) || !interaction.obstructions(pos).isEmpty()) continue;
                        candidates.add(pos.toImmutable());
                    }
                }
            }
        }
        return candidates;
    }

    private boolean blocksTarget(List<Target> targets, BlockPos pos) {
        Box cell = new Box(pos);
        for (Target target : targets) {
            if (target.box.intersects(cell)) return true;
        }
        return mc.player.getBoundingBox().intersects(cell);
    }

    private List<BlockPos> chargedAnchors() {
        List<BlockPos> anchors = new ArrayList<>();
        BlockPos eye = BlockPos.ofFloored(mc.player.getEyePos());
        int radius = MathHelper.ceil(placeRange.get());
        BlockPos.Mutable pos = new BlockPos.Mutable();

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    pos.set(eye.getX() + dx, eye.getY() + dy, eye.getZ() + dz);
                    BlockState state = mc.world.getBlockState(pos);
                    if (!state.isOf(Blocks.RESPAWN_ANCHOR) || charges(state) == 0 || used.containsKey(pos.asLong()) || !inRange(pos)) continue;
                    anchors.add(pos.toImmutable());
                }
            }
        }
        return anchors;
    }

    private LongSet ownCells() {
        LongSet cells = new LongOpenHashSet();
        Box box = mc.player.getBoundingBox();
        int level = MathHelper.floor(mc.player.getY() + 0.4);

        for (int x = MathHelper.floor(box.minX) - 1; x <= MathHelper.floor(box.maxX) + 1; x++) {
            for (int z = MathHelper.floor(box.minZ) - 1; z <= MathHelper.floor(box.maxZ) + 1; z++) {
                for (int y = level - 1; y <= level + 2; y++) cells.add(BlockPos.asLong(x, y, z));
            }
        }
        return cells;
    }

    private BlockHitResult face(BlockPos pos) {
        BlockHitResult cached = faces.get(pos.asLong());
        if (cached != null) return cached;
        BlockHitResult hit = computeFace(pos);
        faces.put(pos.asLong(), hit);
        return hit;
    }

    private BlockHitResult computeFace(BlockPos pos) {
        Vec3d eye = mc.player.getEyePos();
        double reach = mc.player.getBlockInteractionRange();
        InteractionManager interaction = InteractionManager.get();

        RotationManager rotations = RotationManager.get();
        Vec3d end = eye.add(Vec3d.fromPolar(rotations.serverPitch(), rotations.serverYaw()).multiply(reach));
        BlockHitResult looked = Box.raycast(UNIT, eye, end, pos);
        if (looked != null && interaction.visiblePoint(pos, looked.getSide(), eye, reach) != null) return new BlockHitResult(looked.getPos(), looked.getSide(), pos, false);

        BlockHitResult nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        for (Direction direction : Direction.values()) {
            Vec3d seen = interaction.visiblePoint(pos, direction, eye, reach);
            if (seen == null) continue;
            double distance = seen.squaredDistanceTo(eye);
            if (distance < nearestDistance) {
                nearestDistance = distance;
                nearest = new BlockHitResult(seen, direction, pos, false);
            }
        }
        if (nearest != null) return nearest;

        Vec3d center = Vec3d.ofCenter(pos);
        Vec3d offset = eye.subtract(center);
        Direction side = Direction.getFacing(offset.x, offset.y, offset.z);
        return new BlockHitResult(center.add(side.getOffsetX() * 0.5, side.getOffsetY() * 0.5, side.getOffsetZ() * 0.5), side, pos, false);
    }

    private boolean rotationReady(BlockPos pos) {
        return rotationReady(face(pos));
    }

    private boolean rotationReady(BlockHitResult hit) {
        if (!rotate.get()) return true;

        RotationManager rotations = RotationManager.get();
        rotations.request(PRIORITY, hit.getPos(), rotateSpeed.get().floatValue(), true);
        return rotations.isLookingAt(new Box(hit.getBlockPos()), placeRange.get() + RAY_SLACK);
    }

    private boolean inRange(BlockPos pos) {
        return new Box(pos).squaredMagnitude(mc.player.getEyePos()) <= placeRange.get() * placeRange.get() && InteractionManager.centerReach(pos);
    }

    public boolean anchorsWork() {
        if (mc.world.getRegistryKey() == World.NETHER || mc.world.getDimensionEntry().matchesKey(DimensionTypes.THE_NETHER)) return true;
        return Boolean.TRUE.equals(mc.world.getEnvironmentAttributes().getAttributeValue(EnvironmentAttributes.RESPAWN_ANCHOR_WORKS_GAMEPLAY, mc.player.getBlockPos()));
    }

    private static int charges(BlockState state) {
        return state.isOf(Blocks.RESPAWN_ANCHOR) ? state.get(RespawnAnchorBlock.CHARGES) : 0;
    }

    private static int slot(Item item) {
        return InventoryManager.get().find(stack -> stack.isOf(item), 0, InventoryManager.MAIN_END);
    }

    private boolean canAct() {
        return mc.player != null && mc.world != null && mc.interactionManager != null && mc.getNetworkHandler() != null
            && mc.player.isAlive() && !mc.player.isSpectator();
    }
}
