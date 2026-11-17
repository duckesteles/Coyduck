package dev.coyduck.modules;

import dev.coyduck.core.Clock;
import dev.coyduck.core.Core;
import dev.coyduck.events.BlockUpdateEvent;
import dev.coyduck.events.ChunkDataEvent;
import dev.coyduck.events.EntityAddedEvent;
import dev.coyduck.events.MineStopEvent;
import dev.coyduck.events.PacketApplyEvent;
import dev.coyduck.events.RenderScreenEvent;
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
import dev.coyduck.logic.Blast;
import dev.coyduck.logic.BlockOverlay;
import dev.coyduck.logic.CrystalScore;
import dev.coyduck.logic.CrystalScore.Choice;
import dev.coyduck.logic.CrystalScore.Rules;
import dev.coyduck.logic.CrystalScore.Target;
import dev.coyduck.managers.CrystalTracker;
import dev.coyduck.managers.HurtTracker;
import dev.coyduck.managers.InteractionManager;
import dev.coyduck.managers.InventoryManager;
import dev.coyduck.managers.LatencyManager;
import dev.coyduck.managers.MiningManager;
import dev.coyduck.managers.MiningTracker;
import dev.coyduck.managers.MotionTracker;
import dev.coyduck.managers.RotationManager;
import dev.coyduck.render.DuckRender;
import dev.coyduck.render.Shape;
import dev.coyduck.utils.ExplosionDamage;
import dev.coyduck.utils.Targets;
import dev.coyduck.utils.Trace;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.longs.Long2FloatOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.ExperienceOrbEntity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.decoration.BlockAttachedEntity;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.PersistentProjectileEntity;
import net.minecraft.entity.vehicle.VehicleEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.HandSwingC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractEntityC2SPacket;
import net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ChunkDeltaUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.EntitiesDestroyS2CPacket;
import net.minecraft.network.packet.s2c.play.ExplosionS2CPacket;
import net.minecraft.predicate.entity.EntityPredicates;
import net.minecraft.util.Hand;
import net.minecraft.util.Util;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.BlockView;
import net.minecraft.world.Difficulty;
import net.minecraft.world.RaycastContext;

import java.util.ArrayList;
import java.util.List;

public class AutoCrystal extends Feature {
    private static final long OPEN_TIME = 2_000;
    private static final int PLACE_PRIORITY = 100;
    private static final int HOLD_PRIORITY = 101;
    private static final int GAP_PRIORITY = 105;
    private static final int DEFEND_PRIORITY = 106;
    private static final int BREAK_PRIORITY = 110;
    private static final int PREDICT_DROP = 3;
    private static final float DEFENSE_THREAT = 4.0f;
    private static final float DEFENSE_FREE = 0.5f;
    private static final double RAY_SLACK = 2.0;
    private static final double SERVER_BREAK_RANGE = 6.0;
    private static final double PREDICT_CLEAR_RANGE = 8.0;
    private static final double CLEAR_RANGE = 4.0;

    private BlockPos stoppedAt;
    private BlockPos lastSpot;
    private long lastSpotAt;

    private static final double CITY_CELL_RANGE = 3.0;

    private static final int FOCUS_TICKS = 20;
    private static final int MAX_PREDICT_TICKS = 6;
    private static final long BROKEN_GAP_TIME = 2_000;
    private static final float HIDDEN_PENALTY = 2.0f;
    private static final float TURN_FREE = 20.0f;
    private static final float TURN_COST = 0.02f;
    private static final long KEEP_MS = 100;
    private static final float PREFERRED_AIM_SLACK = 15.0f;
    private static final double AIM_INSET = 0.2;
    private static final double PREFERRED_LOS_LIFT = 0.15;
    private static final int GAP_PLACES = 1;
    private static final float MINE_AHEAD_PROGRESS = 0.6f;
    private static final double MINE_AHEAD_RANGE = 4.0;
    private static final int AIM_PRIORITY = 99;
    private static final float HURT_WORTH = 0.6f;
    private static final float LETHAL_DEFENSE_RAW_MARGIN = 2.0f;
    private static final long LETHAL_DEFENSE_TIME_MARGIN = 100;

    private static final class TargetRaws {
        Box box;
        final Long2FloatOpenHashMap raws = new Long2FloatOpenHashMap();
    }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgDamage = settings.createGroup("Damage");
    private final SettingGroup sgTiming = settings.createGroup("Timing");
    private final SettingGroup sgBase = settings.createGroup("Base");
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

    private final Setting<Double> breakRange = sgGeneral.add(new DoubleSetting.Builder()
        .name("break-range")
        .description("Break range from your eyes, in blocks.")
        .defaultValue(5.5)
        .range(1, 6)
        .sliderRange(1, 6)
        .build()
    );

    private final Setting<InventoryManager.SwitchMode> switchMode = sgGeneral.add(new EnumSetting.Builder<InventoryManager.SwitchMode>()
        .name("switch")
        .description("Silent uses the hotbar, InvSwap uses inventory clicks.")
        .defaultValue(InventoryManager.SwitchMode.Silent)
        .build()
    );

    private final Setting<Integer> placesPerTick = sgGeneral.add(new IntSetting.Builder()
        .name("places-per-tick")
        .description("Most crystals placed in one tick.")
        .defaultValue(2)
        .range(1, 4)
        .sliderRange(1, 4)
        .build()
    );

    private final Setting<Integer> breaksPerTick = sgGeneral.add(new IntSetting.Builder()
        .name("breaks-per-tick")
        .description("Most crystals broken in one tick.")
        .defaultValue(2)
        .range(1, 4)
        .sliderRange(1, 4)
        .build()
    );

    private final Setting<InteractionManager.SwingMode> swing = sgGeneral.add(new EnumSetting.Builder<InteractionManager.SwingMode>()
        .name("swing")
        .description("How the hand swing is shown.")
        .defaultValue(InteractionManager.SwingMode.Packet)
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
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> showStatus = sgGeneral.add(new BoolSetting.Builder()
        .name("show-status")
        .description("Shows why the last crystal was or wasn't placed.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> minDamage = sgDamage.add(new DoubleSetting.Builder()
        .name("min-damage")
        .description("Least damage a crystal must deal to the target.")
        .defaultValue(4)
        .range(0, 36)
        .sliderRange(0, 36)
        .build()
    );

    private final Setting<Double> maxSelfDamage = sgDamage.add(new DoubleSetting.Builder()
        .name("max-self-damage")
        .description("Most damage a crystal may deal to you.")
        .defaultValue(8)
        .range(0, 36)
        .sliderRange(0, 36)
        .build()
    );

    private final Setting<Double> minHealth = sgDamage.add(new DoubleSetting.Builder()
        .name("min-health")
        .description("Least health you keep after your own crystals.")
        .defaultValue(8)
        .range(0, 36)
        .sliderRange(0, 20)
        .build()
    );

    private final Setting<Double> facePlaceHealth = sgDamage.add(new DoubleSetting.Builder()
        .name("face-place-health")
        .description("Face places at or below this target health.")
        .defaultValue(36)
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

    private final Setting<Integer> armorBreak = sgDamage.add(new IntSetting.Builder()
        .name("armor-break")
        .description("Face places at or below this armor durability percent.")
        .defaultValue(15)
        .range(0, 100)
        .sliderRange(0, 100)
        .build()
    );

    private final Setting<Boolean> chip = sgDamage.add(new BoolSetting.Builder()
        .name("chip")
        .description("Chips armor with weak crystals that barely hurt you.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> chipDamage = sgDamage.add(new DoubleSetting.Builder()
        .name("chip-damage")
        .description("Least damage a chip crystal must deal.")
        .defaultValue(2)
        .range(0, 36)
        .sliderRange(0, 10)
        .visible(chip::get)
        .build()
    );

    private final Setting<Double> chipSelf = sgDamage.add(new DoubleSetting.Builder()
        .name("chip-self-damage")
        .description("Most damage a chip crystal may deal to you.")
        .defaultValue(1)
        .range(0, 36)
        .sliderRange(0, 10)
        .visible(chip::get)
        .build()
    );

    private final Setting<Double> balance = sgDamage.add(new DoubleSetting.Builder()
        .name("balance")
        .description("Least target to self damage ratio. 0 turns it off.")
        .defaultValue(1.0)
        .range(0, 5)
        .sliderRange(0, 3)
        .build()
    );

    private final Setting<Double> selfWeight = sgDamage.add(new DoubleSetting.Builder()
        .name("self-weight")
        .description("How much self damage counts when picking a crystal.")
        .defaultValue(0.5)
        .range(0, 2)
        .sliderRange(0, 2)
        .build()
    );

    private final Setting<Boolean> defend = sgDamage.add(new BoolSetting.Builder()
        .name("defend")
        .description("Breaks enemy crystals near you while you are immune.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> hurtSync = sgTiming.add(new BoolSetting.Builder()
        .name("hurt-sync")
        .description("Waits for the target's damage immunity to end.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> predict = sgTiming.add(new BoolSetting.Builder()
        .name("predict")
        .description("Aims where targets will be when packets arrive.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> idPredict = sgTiming.add(new BoolSetting.Builder()
        .name("id-predict")
        .description("Attacks the predicted crystal ID right after placing.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> idPredictCount = sgTiming.add(new IntSetting.Builder()
        .name("id-predict-count")
        .description("How many upcoming entity IDs to attack.")
        .defaultValue(1)
        .range(1, 3)
        .sliderRange(1, 3)
        .visible(idPredict::get)
        .build()
    );

    private final Setting<Boolean> kickGuard = sgTiming.add(new BoolSetting.Builder()
        .name("kick-guard")
        .description("Skips IDs that could be items, arrows or xp orbs.")
        .defaultValue(false)
        .visible(idPredict::get)
        .build()
    );

    private final Setting<Boolean> antiSurround = sgGeneral.add(new BoolSetting.Builder()
        .name("anti-surround")
        .description("Crystals surround gaps and keeps them open.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> throughWalls = sgGeneral.add(new BoolSetting.Builder()
        .name("through-walls")
        .description("Places and breaks crystals through blocks.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> mineAhead = sgGeneral.add(new BoolSetting.Builder()
        .name("mine-ahead")
        .description("Treats the surround block you mine as already gone.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> basePlace = sgBase.add(new BoolSetting.Builder()
        .name("base-place")
        .description("Places a base block when no spot deals enough damage.")
        .defaultValue(true)
        .build()
    );

    private final Setting<List<Block>> baseBlocks = sgBase.add(new BlockListSetting.Builder()
        .name("base-blocks")
        .description("Blocks crystals can be placed on.")
        .defaultValue(Blocks.OBSIDIAN)
        .filter(block -> block == Blocks.OBSIDIAN)
        .visible(basePlace::get)
        .build()
    );

    private final Setting<Boolean> baseAirplace = sgBase.add(new BoolSetting.Builder()
        .name("airplace")
        .description("Places the base block without a neighbour.")
        .defaultValue(true)
        .visible(basePlace::get)
        .build()
    );

    private final Setting<Boolean> rotate = sgRotate.add(new BoolSetting.Builder()
        .name("rotate")
        .description("Looks at the block before placing a crystal.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> instantAim = sgRotate.add(new BoolSetting.Builder()
        .name("instant-aim")
        .description("Sends a look packet right before the crystal.")
        .defaultValue(true)
        .visible(rotate::get)
        .build()
    );

    private final Setting<Boolean> rotateBreak = sgRotate.add(new BoolSetting.Builder()
        .name("rotate-break")
        .description("Also looks at crystals before breaking them.")
        .defaultValue(false)
        .visible(rotate::get)
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

    private final Setting<Boolean> moveFix = sgRotate.add(new BoolSetting.Builder()
        .name("move-fix")
        .description("Moves relative to the server-side rotation.")
        .defaultValue(true)
        .visible(rotate::get)
        .build()
    );

    private final Setting<Boolean> render = sgRender.add(new BoolSetting.Builder()
        .name("render")
        .description("Highlights where crystals are placed.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> damageText = sgRender.add(new BoolSetting.Builder()
        .name("damage-text")
        .description("Shows the target damage on the box.")
        .defaultValue(true)
        .visible(render::get)
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
        .defaultValue(new Rgba(185, 74, 230, 40))
        .visible(render::get)
        .build()
    );

    private final ColorSetting lineColor = sgRender.add(new ColorSetting.Builder()
        .name("line-color")
        .description("Highlight line color.")
        .defaultValue(new Rgba(185, 74, 230, 200))
        .visible(render::get)
        .build()
    );

    private boolean destroyedCrystal;
    private int placeBudget;
    private int gapBudget;
    private int breakBudget;
    private boolean running;

    private PlayerEntity lastTarget;
    private PlayerEntity focus;
    private int challengeTicks;
    private long releaseAt;
    private final Long2FloatOpenHashMap selfCache = new Long2FloatOpenHashMap();
    private final Int2ObjectOpenHashMap<TargetRaws> rawCache = new Int2ObjectOpenHashMap<>();
    private final List<PlayerEntity> friends = new ArrayList<>();
    private List<EndCrystalEntity> crystals = List.of();
    private Box selfBox;
    private Vec3d selfMove;
    private Difficulty difficulty;
    private List<BlockPos> ownCells = List.of();
    private final DuckRender.Slide slide = new DuckRender.Slide();
    private float renderDamage;
    private final IntOpenHashSet predictedIds = new IntOpenHashSet();
    private long predictedBase;
    private long predictedAt;
    private PlayerEntity predictedTarget;
    private String status;
    private final Long2LongOpenHashMap clearing = new Long2LongOpenHashMap();
    private final CrystalScore scorer = new CrystalScore(new ScoreModel());
    private Rules rules;
    private long preparedAt = -1;

    public AutoCrystal() {
        super("auto-crystal", "Places and breaks crystals.");
    }

    @Override
    public void onActivate() {
        displace("it fights over the same crystals.", "crystal-aura");

        placeBudget = placesPerTick.get();
        gapBudget = GAP_PLACES;
        breakBudget = breaksPerTick.get();
        lastTarget = null;
        focus = null;
        challengeTicks = 0;
        releaseAt = 0;
        slide.reset();
    }

    @Override
    public void onDeactivate() {
        InteractionManager.get().trackDrops(false);
        if (predictedAt != 0) endPrediction();
    }

    public PlayerEntity target() {
        return isActive() ? focus : null;
    }

    public double placeRange() {
        return placeRange.get();
    }

    public boolean wouldFillGap(BlockPos gap, PlayerEntity player) {
        if (!isActive() || !antiSurround.get() || !canAct() || !InteractionManager.get().hasCrystals() || !InteractionManager.get().canAttack()) return false;
        if (pauseMining.get() && mc.interactionManager.isBreakingBlock()) return false;
        if (pauseEating.get() && mc.player.isUsingItem() || InventoryManager.get().isOffhandEating()) return false;

        if (preparedAt != Clock.ticks()) prepare();
        Target target = makeTarget(player, predictTicks());
        Surround surround = Features.get(Surround.class);
        ownCells = surround != null && surround.isActive() ? Targets.ownRing() : List.of();
        return canFillGap(gap, target, mc.player.getHealth() + mc.player.getAbsorptionAmount());
    }

    public float cityDamage(BlockPos gap, BlockPos support, PlayerEntity player) {
        if (preparedAt != Clock.ticks()) prepare();
        List<Target> one = List.of(makeTarget(player, predictTicks()));
        Surround surround = Features.get(Surround.class);
        ownCells = surround != null && surround.isActive() ? Targets.ownRing() : List.of();
        BlockOverlay view = new BlockOverlay(mc.world).set(gap, Blocks.AIR.getDefaultState());
        if (support != null) view.set(support, Blocks.OBSIDIAN.getDefaultState());
        float health = mc.player.getHealth() + mc.player.getAbsorptionAmount();

        float best = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                BlockPos base = gap.add(dx, -1, dz);
                if (!isBase(view.getBlockState(base)) || !view.getBlockState(base.up()).isAir()) continue;
                if (!inPlaceRange(base) || !breakable(base) || blocked(base) || overlapsOwnCells(base) || CrystalTracker.get().isBlacklisted(base)) continue;
                if ((dx != 0 || dz != 0) && !canUse(base)) continue;
                Choice choice = scorer.evaluate(one, crystalCenter(base), health, 0, view);
                if (choice != null) best = Math.max(best, choice.damage());
            }
        }
        return best;
    }

    @Override
    public String getInfoString() {
        String name = focus == null ? null : focus.getName().getString();
        if (!showStatus.get() || status == null) return name;
        return name == null ? status : name + " " + status;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        placeBudget = placesPerTick.get();
        gapBudget = GAP_PLACES;
        breakBudget = breaksPerTick.get();
        InteractionManager.get().trackDrops(idPredict.get() && kickGuard.get());
        if (predictedAt != 0 && Util.getMeasuringTimeMs() - predictedAt > LatencyManager.get().timeout()) endPrediction();
        long now = Util.getMeasuringTimeMs();
        if (!clearing.isEmpty()) clearing.values().removeIf(until -> now >= until);
        pass();
        updateFocus();
    }

    @EventHandler
    private void onWorldReset(WorldResetEvent event) {
        preparedAt = -1;
        focus = null;
        lastTarget = null;
        challengeTicks = 0;
        predictedAt = 0;
        predictedTarget = null;
        clearing.clear();
        slide.reset();
        releaseAt = 0;
        clearCache();
    }

    private void endPrediction() {
        if (predictedTarget != null) HurtTracker.get().forgetSpeculative(predictedTarget);
        predictedAt = 0;
    }

    @EventHandler
    private void onTickPost(TickEvent.Post event) {
        if (rotate.get()) pass();
    }

    @EventHandler
    private void onBlockUpdate(BlockUpdateEvent event) {
        if (mc.player == null) return;
        double range = Math.max(Math.max(placeRange.get(), breakRange.get()), targetRange.get() + 2) + ExplosionDamage.CRYSTAL_RANGE;
        if (mc.player.getEyePos().squaredDistanceTo(Vec3d.ofCenter(event.pos)) <= range * range) clearCache();
    }

    @EventHandler
    private void onChunkData(ChunkDataEvent event) {
        clearCache();
    }

    private void updateFocus() {
        if (focus != null && (!focus.isAlive() || focus.isRemoved() || focus.squaredDistanceTo(mc.player) > targetRange.get() * targetRange.get())) focus = null;

        if (lastTarget != null && !Targets.valid(lastTarget, targetRange.get())) lastTarget = null;

        if (focus == null) {
            focus = lastTarget;
            challengeTicks = 0;
        } else if (lastTarget != null && lastTarget != focus) {
            if (++challengeTicks >= FOCUS_TICKS) {
                focus = lastTarget;
                challengeTicks = 0;
            }
        } else {
            challengeTicks = 0;
        }
    }

    @EventHandler
    private void onEntityAdded(EntityAddedEvent event) {
        if (event.entity instanceof ItemEntity || event.entity instanceof ExperienceOrbEntity) {
            if (mc.player == null || !near(event.entity.getEntityPos())) return;
            if (CrystalTracker.get().cancelPlacing(event.entity.getBoundingBox())) pass();
            return;
        }
        if (!(event.entity instanceof EndCrystalEntity crystal)) return;

        if (predictedAt != 0 && Util.getMeasuringTimeMs() - predictedAt > LatencyManager.get().timeout()) endPrediction();
        if (predictedAt != 0 && crystal.getBlockPos().down().asLong() == predictedBase) {
            if (predictedIds.contains(crystal.getId())) {
                CrystalTracker.get().explodeAt(crystal.getEntityPos());
                if (predictedTarget != null) HurtTracker.get().confirmSpeculative(predictedTarget);
                predictedAt = 0;
            } else {
                endPrediction();
            }
        }

        if (near(crystal.getEntityPos())) pass();
    }

    @EventHandler
    private void onPacket(PacketApplyEvent.Post event) {
        if (mc.player == null) return;

        boolean relevant = switch (event.packet) {
            case ExplosionS2CPacket packet -> near(packet.center());
            case EntitiesDestroyS2CPacket ignored -> destroyedCrystal;
            case BlockUpdateS2CPacket packet -> packet.getState().isReplaceable() && near(Vec3d.ofCenter(packet.getPos()));
            case ChunkDeltaUpdateS2CPacket packet -> {
                boolean[] found = {false};
                packet.visitUpdates((pos, state) -> found[0] |= state.isReplaceable() && near(Vec3d.ofCenter(pos)));
                yield found[0];
            }
            default -> false;
        };

        destroyedCrystal = false;
        if (relevant) pass();
    }

    @EventHandler
    private void onPacketPre(PacketApplyEvent.Pre event) {
        if (!(event.packet instanceof EntitiesDestroyS2CPacket packet) || mc.world == null || mc.player == null) return;

        for (int id : packet.getEntityIds()) {
            Entity entity = mc.world.getEntityById(id);
            boolean expected = entity instanceof EndCrystalEntity && CrystalTracker.get().isDead(entity);
            if ((expected || entity instanceof ItemEntity || entity instanceof ExperienceOrbEntity) && near(entity.getEntityPos())) {
                destroyedCrystal = true;
                return;
            }
        }
    }

    private boolean near(Vec3d pos) {
        double range = placeRange.get() + ExplosionDamage.CRYSTAL_RANGE;
        return mc.player.getEyePos().squaredDistanceTo(pos) <= range * range;
    }

    @EventHandler
    private void onMineStop(MineStopEvent event) {
        clearCache();
        placeBudget = Math.max(placeBudget, 1);
        int before = placeBudget;
        stoppedAt = event.pos;
        try {
            pass();
        } finally {
            stoppedAt = null;
        }
        Trace.log("crystal on stop {} {} status {}", event.pos, placeBudget < before ? "placed" : "none", status);
    }

    private void clearCache() {
        selfCache.clear();
        rawCache.clear();
    }

    @EventHandler
    private void onRender2D(RenderScreenEvent event) {
        if (!render.get() || !damageText.get() || renderDamage <= 0) return;
        DuckRender.label(event.overlay, slide.center(), String.format("%.1f", renderDamage), 0xFFFFFFFF, slide.alpha());
    }

    @EventHandler
    private void onRender(RenderWorldEvent event) {
        if (releaseAt != 0 && Util.getMeasuringTimeMs() >= releaseAt) {
            releaseAt = 0;
            pass();
        }

        if (!render.get()) return;
        slide.render(event.canvas, sideColor.argb(), lineColor.argb(), shapeMode.get());
    }

    private void pass() {
        if (running || !canAct()) return;

        running = true;
        try {
            prepare();
            run();
        } finally {
            running = false;
        }
    }

    private Rules rules() {
        return new Rules(maxSelfDamage.get(), minHealth.get(), chip.get(), chipDamage.get().floatValue(), chipSelf.get(), balance.get(),
            selfWeight.get().floatValue(), antiSurround.get(), AutoTotem.canAffordPop());
    }

    private void prepare() {
        preparedAt = Clock.ticks();
        rules = rules();
        scorer.rules(rules);
        Difficulty now = mc.world.getDifficulty();
        if (now != difficulty) {
            difficulty = now;
            clearCache();
        }

        Box box = mc.player.getBoundingBox();
        Vec3d move = selfMove();
        if (!box.equals(selfBox) || !move.equals(selfMove)) {
            selfCache.clear();
            selfBox = box;
            selfMove = move;
        }

        friends.clear();
        double range = Math.max(breakRange.get(), placeRange.get()) + 2 + ExplosionDamage.CRYSTAL_RANGE;
        for (PlayerEntity player : mc.world.getPlayers()) {
            if (player != mc.player && player.squaredDistanceTo(mc.player) <= range * range && Core.host().isFriend(player)) friends.add(player);
        }

        Box area = box.expand(Math.max(breakRange.get(), placeRange.get()) + ExplosionDamage.CRYSTAL_RANGE + 1);
        crystals = mc.world.getEntitiesByClass(EndCrystalEntity.class, area, crystal -> true);
    }

    private Vec3d selfMove() {
        return MotionTracker.get().selfLead();
    }

    private void run() {
        if (pauseMining.get() && mc.interactionManager.isBreakingBlock()) {
            status = "mining";
            return;
        }

        List<Target> targets = targets();
        if (!rawCache.isEmpty()) {
            IntOpenHashSet present = new IntOpenHashSet();
            for (Target target : targets) present.add(target.player().getId());
            rawCache.keySet().removeIf((java.util.function.IntPredicate) id -> !present.contains(id));
        }
        if (targets.isEmpty()) {
            lastTarget = null;
            status = null;
            return;
        }

        Surround surround = Features.get(Surround.class);
        ownCells = surround != null && surround.isActive() ? Targets.ownRing() : List.of();

        float health = mc.player.getHealth() + mc.player.getAbsorptionAmount();
        if (InventoryManager.get().isOffhandEating()) {
            status = "eating";
            if (defend.get() && breakBudget > 0) defend(targets, health, true);
            return;
        }
        boolean holding = breakBudget > 0 && breakCrystals(targets, health);
        if (pauseEating.get() && mc.player.isUsingItem()) {
            status = "eating";
            return;
        }
        if (!InteractionManager.get().hasCrystals()) {
            status = "no crystals";
            return;
        }
        if (!InteractionManager.get().canAttack()) {
            InteractionManager.get().prepareAttack();
            status = "weakness";
            return;
        }

        aimAtMinedGap(targets);
        if (antiSurround.get() && gapBudget > 0) fillGaps(targets, health);
        if (placeBudget <= 0) return;
        if (holding) {
            status = "hurt-sync";
            return;
        }
        if (antiSurround.get() && placeClearing(targets, health)) return;

        List<BlockPos> candidates = candidates();
        scorer.verdicts().clear();
        BlockPos spot = null;
        Choice spotChoice = null;
        BlockPos seen = null;
        Choice seenChoice = null;
        Vec3d eye = mc.player.getEyePos();
        BlockPos mined = minedCell(targets);
        BlockView minedView = mined == null ? null : new BlockOverlay(mc.world).set(mined, mc.world.getFluidState(mined).getBlockState());
        double aheadRange = MINE_AHEAD_RANGE * MINE_AHEAD_RANGE;
        for (BlockPos base : candidates) {
            if (base.equals(stoppedAt) || base.equals(mined) && !overHead(mined, targets) || !canUse(base)) continue;
            Vec3d center = crystalCenter(base);
            boolean ahead = minedView != null && center.squaredDistanceTo(Vec3d.ofCenter(mined)) <= aheadRange;
            float beat = seenChoice == null ? -Float.MAX_VALUE : seenChoice.score() - CrystalScore.TIE;
            Choice choice = scorer.evaluate(targets, center, health, 0, ahead ? minedView : mc.world, beat);
            if (choice == null) continue;
            choice = steadied(choice, base);
            if (choice.beats(spotChoice)) {
                spot = base;
                spotChoice = choice;
            }
            if (choice.beats(seenChoice) && InteractionManager.get().visiblePoint(base, InteractionManager.get().crystalFace(base), eye, Double.MAX_VALUE) != null) {
                seen = base;
                seenChoice = choice;
            }
        }
        if (seen != null && seenChoice.score() >= spotChoice.score() - HIDDEN_PENALTY) {
            spot = seen;
            spotChoice = seenChoice;
        }
        if (mined != null && mined.equals(stoppedAt)) {
            BlockPos base = mined.down();
            if (inPlaceRange(base) && isBase(mc.world.getBlockState(base)) && !blocked(base) && !overlapsOwnCells(base)
                && !CrystalTracker.get().placingOverlaps(base) && !CrystalTracker.get().isBlacklisted(base)) {
                Choice choice = scorer.evaluate(targets, crystalCenter(base), health, 0, minedView);
                if (choice != null && choice.beats(spotChoice)) {
                    spot = base;
                    spotChoice = choice;
                }
            }
        }

        if (spot != null) {
            boolean ahead = minedView != null && crystalCenter(spot).squaredDistanceTo(Vec3d.ofCenter(mined)) <= aheadRange;
            if (stoppedAt == null && !ahead && CrystalTracker.get().hasPlacing()) {
                status = "waiting";
                return;
            }
            lastSpot = spot;
            lastSpotAt = Util.getMeasuringTimeMs();
            placeCrystal(spot, spotChoice);
            if (ahead && status != null && status.endsWith("dmg")) status = "ahead " + status;
            return;
        }

        if (candidates.isEmpty()) status = "no spot";
        else if (scorer.verdicts().self()) status = "self damage";
        else if (scorer.verdicts().friend()) status = "friend";
        else if (scorer.verdicts().balance()) status = "balance";
        else status = "low damage";

        if (basePlace.get() && !CrystalTracker.get().hasPlacing()) placeBase(targets, health);
    }

    private boolean placeClearing(List<Target> targets, float health) {
        if (!clearing.isEmpty()) return false;

        BlockPos gap = null;
        for (Target target : targets) {
            for (long key : target.gaps()) {
                BlockPos cell = BlockPos.fromLong(key);
                BlockPos base = cell.down();
                BlockState state = mc.world.getBlockState(cell);
                boolean web = state.isOf(Blocks.COBWEB);
                if ((!state.isAir() && !web) || !isBase(mc.world.getBlockState(base))) continue;
                if (!inPlaceRange(base) || !breakable(base) || overlapsOwnCells(base) || (!web && !blockedByDrops(base))) continue;
                gap = cell;
                break;
            }
            if (gap != null) break;
        }
        if (gap == null) return false;

        Vec3d gapCenter = Vec3d.ofCenter(gap);
        BlockPos best = null;
        double bestDistance = CLEAR_RANGE * CLEAR_RANGE;

        for (BlockPos base : candidates()) {
            Vec3d center = crystalCenter(base);
            double distance = center.squaredDistanceTo(gapCenter);
            if (distance > bestDistance || !canUse(base)) continue;
            if (!selfSafe(center, health, 0, mc.world) || hurtsFriend(center, mc.world)) continue;
            best = base;
            bestDistance = distance;
        }
        if (best == null) return false;

        if (!rotationReady(new Box(best), InteractionManager.get().crystalAim(best), placeRange.get() + 1, PLACE_PRIORITY)) return false;
        if (!InteractionManager.get().placeCrystal(best, switchMode.get(), swing.get(), null, !pauseEating.get())) return false;

        clearing.put(best.asLong(), Util.getMeasuringTimeMs() + 2L * LatencyManager.get().timeout());
        placeBudget--;
        status = "clearing";
        slide.show(best);
        return true;
    }

    private boolean blockedByDrops(BlockPos base) {
        BlockPos above = base.up();
        Box box = new Box(above.getX(), above.getY(), above.getZ(), above.getX() + 1, above.getY() + 2, above.getZ() + 1);
        CrystalTracker tracker = CrystalTracker.get();
        boolean drops = false;
        for (Entity entity : mc.world.getOtherEntities(null, box, EntityPredicates.EXCEPT_SPECTATOR)) {
            if (!tracker.isAlive(entity)) continue;
            if (!clearable(entity)) return false;
            drops = true;
        }
        return drops;
    }

    private static boolean clearable(Entity entity) {
        return entity instanceof ItemEntity || entity instanceof ExperienceOrbEntity || entity instanceof ArmorStandEntity
            || entity instanceof BlockAttachedEntity || entity instanceof VehicleEntity;
    }

    private void expectHits(List<Target> targets, Vec3d center, Choice choice) {
        for (Target target : targets) {
            float hit = choice != null && target == choice.target() ? choice.raw() : targetRaw(target, center, mc.world);
            if (hit > 0) HurtTracker.get().expect(target.player(), hit);
        }
    }

    private boolean breakReady(EndCrystalEntity crystal, int priority) {
        if (!rotate.get() || !rotateBreak.get()) return true;
        return rotationReady(crystal.getBoundingBox(), breakAim(crystal), breakRange.get(), priority);
    }

    private void breakClearing(List<Target> targets, float health) {
        if (clearing.isEmpty()) return;

        CrystalTracker tracker = CrystalTracker.get();
        long arrival = Util.getMeasuringTimeMs() + LatencyManager.get().rtt() / 2;
        double range = breakRange.get() * breakRange.get();

        for (EndCrystalEntity crystal : crystals) {
            if (breakBudget <= 0) return;
            if (!tracker.isAlive(crystal) || InteractionManager.get().stuck(crystal)) continue;
            long base = crystal.getBlockPos().down().asLong();
            if (!clearing.containsKey(base) || crystal.getBoundingBox().squaredMagnitude(mc.player.getEyePos()) > range || !canHit(crystal)) continue;

            Vec3d center = crystal.getEntityPos();
            if (!selfSafe(center, health, arrival, mc.world) || hurtsFriend(center, mc.world)) continue;
            if (!InteractionManager.get().canAttack()) return;
            if (!breakReady(crystal, BREAK_PRIORITY)) return;
            if (!InteractionManager.get().attack(crystal, switchMode.get(), swing.get(), !pauseEating.get())) return;

            tracker.broke(crystal);
            expectHits(targets, center, null);
            clearing.remove(base);
            breakBudget--;
        }
    }

    private void aimAtMinedGap(List<Target> targets) {
        if (!rotate.get() || !antiSurround.get()) return;

        MiningManager mining = MiningManager.get();
        BlockPos cell = mining.pos();
        if (cell == null || mining.progress() < MINE_AHEAD_PROGRESS) return;

        BlockPos base = cell.down();
        if (!isBase(mc.world.getBlockState(base)) || !inPlaceRange(base)) return;
        long key = cell.asLong();
        for (Target target : targets) {
            if (!target.gaps().contains(key)) continue;
            rotationReady(new Box(base), InteractionManager.get().crystalAim(base), placeRange.get() + 1, AIM_PRIORITY);
            return;
        }
    }

    private BlockPos minedCell(List<Target> targets) {
        if (!mineAhead.get()) return null;

        MiningManager mining = MiningManager.get();
        BlockPos cell = mining.pos();
        if (cell == null || mc.world.getBlockState(cell).isReplaceable()) return null;
        if (mining.remainingTicks() > MathHelper.ceil(LatencyManager.get().rtt() / 50.0) + 1) return null;

        long key = cell.asLong();
        AutoCity city = Features.get(AutoCity.class);
        boolean cityCell = city != null && city.isActive() && mining.owns(city);
        for (Target target : targets) {
            if (target.gaps().contains(key)) return cell;
            if (cityCell && Vec3d.ofCenter(cell).squaredDistanceTo(target.feet()) <= CITY_CELL_RANGE * CITY_CELL_RANGE) return cell;
        }
        return null;
    }

    private static boolean overHead(BlockPos cell, List<Target> targets) {
        long below = cell.down(2).asLong();
        for (Target target : targets) {
            if (Targets.columns(target.box()).contains(below)) return true;
        }
        return false;
    }

    public double minHealth() {
        return minHealth.get();
    }

    private boolean selfSafe(Vec3d crystal, float health, long arrival, BlockView view) {
        if (arrival == 0) {
            float full = selfDamage(crystal, view);
            return full < health && !rules.leavesLow(full, health) && full <= maxSelfDamage.get();
        }
        float effective = HurtTracker.get().effectiveOnSelf(selfRaw(crystal, view), arrival);
        return effective < health && !rules.leavesLow(effective, health) && effective <= maxSelfDamage.get();
    }

    private void fillGaps(List<Target> targets, float health) {
        for (Target target : targets) {
            for (long key : target.gaps()) {
                if (gapBudget <= 0) return;

                BlockPos gap = BlockPos.fromLong(key);
                clearGap(gap, targets, health);
                if (!canFillGap(gap, target, health)) continue;

                BlockPos base = gap.down();
                Vec3d center = crystalCenter(base);
                if (!rotationReady(new Box(base), InteractionManager.get().crystalAim(base), placeRange.get() + 1, GAP_PRIORITY)) return;

                if (!InteractionManager.get().placeCrystal(base, switchMode.get(), swing.get(), null, !pauseEating.get())) return;
                gapBudget--;
                status = "gap";
                lastTarget = target.player();
                slide.show(base);
            }
        }
    }

    private void clearGap(BlockPos gap, List<Target> targets, float health) {
        BlockPos base = gap.down();
        BlockState state = mc.world.getBlockState(gap);
        boolean web = state.isOf(Blocks.COBWEB);
        if (breakBudget <= 0 || (!state.isAir() && !web) || !isBase(mc.world.getBlockState(base))) return;
        if (!inPlaceRange(base) || !breakable(base) || (!web && !blocked(base))) return;

        CrystalTracker tracker = CrystalTracker.get();
        Box cell = new Box(gap.getX(), gap.getY(), gap.getZ(), gap.getX() + 1, gap.getY() + 2, gap.getZ() + 1);
        boolean junk = web;
        for (Entity entity : mc.world.getOtherEntities(null, cell, EntityPredicates.EXCEPT_SPECTATOR)) {
            if (!tracker.isAlive(entity) || entity instanceof EndCrystalEntity) continue;
            if (!clearable(entity)) return;
            junk = true;
        }
        if (!junk) return;

        Vec3d middle = Vec3d.ofCenter(gap);
        double reach = breakRange.get() * breakRange.get();
        long arrival = Util.getMeasuringTimeMs() + LatencyManager.get().rtt() / 2;
        EndCrystalEntity best = null;
        double bestDistance = CLEAR_RANGE * CLEAR_RANGE;
        for (EndCrystalEntity crystal : crystals) {
            if (!tracker.isAlive(crystal) || InteractionManager.get().stuck(crystal)) continue;
            if (crystal.getBoundingBox().squaredMagnitude(mc.player.getEyePos()) > reach || !canHit(crystal)) continue;
            double distance = crystal.getEntityPos().squaredDistanceTo(middle);
            if (distance >= bestDistance || !selfSafe(crystal.getEntityPos(), health, arrival, mc.world) || hurtsFriend(crystal.getEntityPos(), mc.world)) continue;
            best = crystal;
            bestDistance = distance;
        }
        if (best == null || !InteractionManager.get().canAttack()) return;
        if (!breakReady(best, BREAK_PRIORITY)) return;
        if (!InteractionManager.get().attack(best, switchMode.get(), swing.get(), !pauseEating.get())) return;

        tracker.broke(best);
        expectHits(targets, best.getEntityPos(), null);
        breakBudget--;
    }

    private boolean canFillGap(BlockPos gap, Target target, float health) {
        BlockPos base = gap.down();
        if (!mc.world.getBlockState(gap).isAir() || !isBase(mc.world.getBlockState(base))) return false;
        if (CrystalTracker.get().placingOverlaps(base) || CrystalTracker.get().isBlacklisted(base) || blocked(base) || !inPlaceRange(base) || !breakable(base) || overlapsOwnCells(base) || !canUse(base)) return false;

        Vec3d center = crystalCenter(base);
        if (!selfSafe(center, health, 0, mc.world) || hurtsFriend(center, mc.world)) return false;

        float raw = ExplosionDamage.crystalRaw(target.player(), target.feet(), target.box(), center, mc.world);
        float damage = target.defense().reduce(raw);
        return damage >= facePlaceDamage.get() && rules.balanced(damage, selfDamage(center, mc.world), target.health());
    }

    private boolean breakCrystals(List<Target> targets, float health) {
        CrystalTracker tracker = CrystalTracker.get();
        long arrival = Util.getMeasuringTimeMs() + LatencyManager.get().rtt() / 2;
        BlockView stopView = stoppedAt == null ? null : new BlockOverlay(mc.world).set(stoppedAt, mc.world.getFluidState(stoppedAt).getBlockState());
        double aheadRange = MINE_AHEAD_RANGE * MINE_AHEAD_RANGE;
        long release = 0;
        boolean held = false;
        boolean broke = false;

        while (breakBudget > 0) {
            EndCrystalEntity best = null;
            Choice bestChoice = null;

            for (EndCrystalEntity crystal : crystals) {
                if (!tracker.isAlive(crystal) || InteractionManager.get().stuck(crystal)) continue;
                if (crystal.getBoundingBox().squaredMagnitude(mc.player.getEyePos()) > breakRange.get() * breakRange.get() || !canHit(crystal)) continue;

                Vec3d center = crystal.getEntityPos();
                BlockView view = stopView != null && center.squaredDistanceTo(Vec3d.ofCenter(stoppedAt)) <= aheadRange ? stopView : mc.world;
                Choice choice = scorer.evaluate(targets, center, health, hurtSync.get() ? arrival : 0, view);
                if (choice != null && hurtSync.get() && choice.damage() < choice.target().health()
                    && choice.damage() < choice.target().defense().reduce(choice.raw()) * HURT_WORTH) choice = null;
                if (choice == null) {
                    if (hurtSync.get()) {
                        Choice ready = scorer.evaluate(targets, center, health, 0, view);
                        if (ready != null) {
                            breakReady(crystal, HOLD_PRIORITY);
                            held = true;
                            long time = HurtTracker.get().releaseTime(ready.target().player());
                            if (time > 0 && (release == 0 || time < release)) release = time;
                        }
                    }
                    continue;
                }

                if (choice.beats(bestChoice)) {
                    best = crystal;
                    bestChoice = choice;
                }
            }

            if (best == null) break;
            if (!InteractionManager.get().canAttack()) {
                InteractionManager.get().prepareAttack();
                status = "weakness";
                break;
            }
            if (!breakReady(best, BREAK_PRIORITY)) break;
            if (!InteractionManager.get().attack(best, switchMode.get(), swing.get(), !pauseEating.get())) break;

            tracker.broke(best);
            if (stopView != null && Trace.on()) Trace.log("crystal broken with the stop at {} for {}", stoppedAt, bestChoice.damage());
            expectHits(targets, best.getEntityPos(), bestChoice);
            lastTarget = bestChoice.target().player();
            breakBudget--;
            broke = true;
        }

        releaseAt = release;
        breakClearing(targets, health);
        if (defend.get() && breakBudget > 0 && defend(targets, health, false)) broke = true;
        return held && !broke;
    }

    private boolean defend(List<Target> targets, float health, boolean lethalOnly) {
        CrystalTracker tracker = CrystalTracker.get();
        long arrival = Util.getMeasuringTimeMs() + LatencyManager.get().rtt() / 2;
        double range = breakRange.get() * breakRange.get();
        boolean broke = false;

        for (EndCrystalEntity crystal : crystals) {
            if (breakBudget <= 0) return broke;
            if (!tracker.isAlive(crystal) || InteractionManager.get().stuck(crystal)) continue;
            if (crystal.getBoundingBox().squaredMagnitude(mc.player.getEyePos()) > range || !canHit(crystal)) continue;

            Vec3d center = crystal.getEntityPos();
            if (ExplosionDamage.upperBound(mc.player, mc.player.getEntityPos(), center) < DEFENSE_THREAT) continue;
            float raw = ExplosionDamage.crystalRaw(mc.player, mc.player.getEntityPos(), mc.player.getBoundingBox(), center, mc.world);
            float full = ExplosionDamage.mitigate(mc.player, raw);
            if (full < DEFENSE_THREAT || lethalOnly && full < health) continue;

            HurtTracker hurt = HurtTracker.get();
            if (hurt.effectiveOnSelf(raw, arrival) > DEFENSE_FREE) continue;
            if (full >= health && hurt.effectiveOnSelf(raw + LETHAL_DEFENSE_RAW_MARGIN, arrival + LETHAL_DEFENSE_TIME_MARGIN) > DEFENSE_FREE) continue;
            if (hurtsFriend(center, mc.world)) continue;
            if (!lethalOnly && (scorer.evaluate(targets, center, health, 0, mc.world) != null || destroysReady(targets, center, health))) continue;

            if (!InteractionManager.get().canAttack()) return broke;
            if (!breakReady(crystal, DEFEND_PRIORITY)) return broke;
            if (!InteractionManager.get().attack(crystal, switchMode.get(), swing.get(), lethalOnly || !pauseEating.get())) return broke;

            tracker.broke(crystal);
            expectHits(targets, center, null);
            breakBudget--;
            broke = true;
        }

        return broke;
    }

    private boolean destroysReady(List<Target> targets, Vec3d center, float health) {
        CrystalTracker tracker = CrystalTracker.get();
        double range = ExplosionDamage.CRYSTAL_RANGE * ExplosionDamage.CRYSTAL_RANGE;

        for (EndCrystalEntity other : crystals) {
            if (!tracker.isAlive(other) || other.getEntityPos().equals(center)) continue;
            if (other.getEntityPos().squaredDistanceTo(center) > range) continue;
            if (scorer.evaluate(targets, other.getEntityPos(), health, 0, mc.world) != null) return true;
        }
        return tracker.placingNear(center, ExplosionDamage.CRYSTAL_RANGE);
    }

    private void placeCrystal(BlockPos base, Choice choice) {
        if (!mc.player.getOffHandStack().isOf(Items.END_CRYSTAL) && MiningManager.get().wantsQuiet()) {
            status = "mining";
            return;
        }
        Box box = new Box(base);
        Vec3d aim = InteractionManager.get().crystalAim(base);
        if (!rotationReady(box, aim, placeRange.get() + 1, PLACE_PRIORITY)) {
            if (!instantAim.get() || !RotationManager.get().lookNow(aim, BREAK_PRIORITY)) {
                status = "rotating";
                return;
            }
        }

        Runnable after = idPredict.get() ? () -> predictAttack(base, choice) : null;
        if (!InteractionManager.get().placeCrystal(base, switchMode.get(), swing.get(), after, !pauseEating.get())) {
            status = "switch";
            return;
        }

        placeBudget--;
        status = String.format("%.1f dmg", choice.damage());
        lastTarget = choice.target().player();
        slide.show(base);
        renderDamage = (float) choice.damage();
    }

    private void placeBase(List<Target> targets, float health) {
        int blockSlot = baseSlot();
        if (blockSlot == -1) return;

        InteractionManager interaction = InteractionManager.get();
        BlockPos best = null;
        BlockHitResult bestHit = null;
        Choice bestChoice = null;

        LongSet seen = new LongOpenHashSet();
        for (Target target : targets) {
            BlockPos feet = BlockPos.ofFloored(target.feet());
            List<BlockPos> ring = Targets.ring(Targets.columns(target.box()), 0);

            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    for (int dy = -1; dy <= 0; dy++) {
                        BlockPos base = feet.add(dx, dy, dz);
                        if (!interaction.canPlace(base) || !mc.world.isAir(base.up())) continue;
                        if (ring.contains(base) || ownCells.contains(base) || overlapsOwnCells(base)) continue;
                        if (MiningManager.get().isMining(base) || MiningTracker.get().wasBroken(base, BROKEN_GAP_TIME)) continue;
                        if (!inPlaceRange(base) || !breakable(base) || !interaction.obstructions(base).isEmpty() || blocked(base)) continue;
                        if (target.box().intersects(new Box(base)) || (!throughWalls.get() && !clearLine(crystalCenter(base)))) continue;

                        if (!seen.add(base.asLong())) continue;
                        BlockOverlay view = new BlockOverlay(mc.world).set(base, Blocks.OBSIDIAN.getDefaultState());
                        Choice choice = scorer.evaluate(targets, crystalCenter(base), health, 0, view, bestChoice == null ? -Float.MAX_VALUE : bestChoice.score() - CrystalScore.TIE);
                        if (choice == null || !choice.beats(bestChoice)) continue;

                        BlockHitResult hit = interaction.hit(base, baseAirplace.get());
                        if (hit == null) continue;

                        best = base;
                        bestHit = hit;
                        bestChoice = choice;
                    }
                }
            }
        }

        if (best == null || !rotationReady(new Box(bestHit.getBlockPos()), bestHit.getPos(), placeRange.get() + 1, PLACE_PRIORITY)) return;

        BlockPos base = best;
        BlockHitResult hit = bestHit;
        Runnable place = () -> interaction.place(base, hit, swing.get());
        if (pauseEating.get()) InventoryManager.get().withItem(blockSlot, switchMode.get(), place);
        else InventoryManager.get().withItemWhileEating(blockSlot, switchMode.get(), place);

        if (!isBase(mc.world.getBlockState(base))) return;
        placeCrystal(base, bestChoice);
    }

    private void predictAttack(BlockPos base, Choice choice) {
        boolean offhand = mc.player.getOffHandStack().isOf(Items.END_CRYSTAL);
        if (mc.player.hasStatusEffect(StatusEffects.WEAKNESS) && !offhand) return;
        if (!InteractionManager.get().canHitWithHand() || !predictSafe()) return;

        int highest = CrystalTracker.get().highestId();
        if (highest <= 0) return;

        if (hurtSync.get()) {
            long arrival = Util.getMeasuringTimeMs() + LatencyManager.get().rtt() / 2;
            float effective = HurtTracker.get().effective(choice.target().player(), choice.target().defense(), choice.raw(), arrival);
            if (effective < choice.target().minDamage() && effective < choice.target().health()) return;
        }

        if (kickGuard.get() && InteractionManager.get().dropRisk(crystalCenter(base))) return;

        if (predictedAt != 0) return;
        predictedIds.clear();
        for (int i = 1; i <= idPredictCount.get(); i++) {
            int id = highest + i;
            if (mc.world.getEntityById(id) != null) continue;

            EndCrystalEntity fake = new EndCrystalEntity(mc.world, 0, 0, 0);
            fake.setId(id);
            mc.getNetworkHandler().sendPacket(PlayerInteractEntityC2SPacket.attack(fake, mc.player.isSneaking() && !BoatFly.keepSeat()));
            mc.getNetworkHandler().sendPacket(new HandSwingC2SPacket(Hand.MAIN_HAND));
            predictedIds.add(id);
        }

        if (predictedIds.isEmpty()) return;
        InteractionManager.get().noteOwnBreak(crystalCenter(base));
        predictedAt = Util.getMeasuringTimeMs();
        predictedBase = base.asLong();
        predictedTarget = choice.target().player();
        HurtTracker.get().expectSpeculative(choice.target().player(), choice.raw());
    }

    private boolean predictSafe() {
        if (kickGuard.get()) {
            if (InteractionManager.get().recentDropRisk()) return false;

            Box area = mc.player.getBoundingBox().expand(PREDICT_CLEAR_RANGE);
            boolean kickRisk = !mc.world.getOtherEntities(mc.player, area, entity -> entity instanceof ItemEntity
                || entity instanceof ExperienceOrbEntity || entity instanceof PersistentProjectileEntity).isEmpty();
            if (kickRisk) return false;
        }

        double friendRange = ExplosionDamage.CRYSTAL_RANGE * ExplosionDamage.CRYSTAL_RANGE;
        for (PlayerEntity player : mc.world.getPlayers()) {
            if (player != mc.player && Core.host().isFriend(player) && player.squaredDistanceTo(mc.player) <= friendRange) return false;
        }

        float health = mc.player.getHealth() + mc.player.getAbsorptionAmount();
        Vec3d eye = mc.player.getEyePos();
        BlockPos center = BlockPos.ofFloored(eye);
        int radius = MathHelper.ceil(SERVER_BREAK_RANGE) + 1;
        double reach = SERVER_BREAK_RANGE * SERVER_BREAK_RANGE;

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius - PREDICT_DROP; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos base = center.add(dx, dy, dz);
                    if (!isBase(mc.world.getBlockState(base)) || !mc.world.isAir(base.up())) continue;
                    if (crystalBox(base).squaredMagnitude(eye) > reach || standsOn(base)) continue;

                    float self = selfDamage(crystalCenter(base), mc.world);
                    if (self >= health || rules.leavesLow(self, health) || self > maxSelfDamage.get()) return false;
                }
            }
        }

        return true;
    }

    private Choice steadied(Choice choice, BlockPos base) {
        float score = choice.score() - turnPenalty(base);
        if (base.equals(lastSpot) && Util.getMeasuringTimeMs() - lastSpotAt <= LatencyManager.get().rtt() + KEEP_MS) score += CrystalScore.KEEP_BONUS;
        return choice.withScore(score);
    }

    private float turnPenalty(BlockPos base) {
        if (!rotate.get()) return 0;
        RotationManager rotations = RotationManager.get();
        if (rotations.isLookingAt(new Box(base), placeRange.get() + 1)) return 0;
        return Math.max(0, turn(InteractionManager.get().crystalAim(base)) - TURN_FREE) * TURN_COST;
    }

    private float turn(Vec3d aim) {
        RotationManager rotations = RotationManager.get();
        Vec3d eye = mc.player.getEyePos();
        double dx = aim.x - eye.x;
        double dy = aim.y - eye.y;
        double dz = aim.z - eye.z;
        float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90.0f;
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        return Math.abs(MathHelper.wrapDegrees(yaw - rotations.serverYaw())) + Math.abs(pitch - rotations.serverPitch());
    }

    private float selfDamage(Vec3d crystal, BlockView view) {
        return ExplosionDamage.mitigate(mc.player, selfRaw(crystal, view));
    }

    private float selfRaw(Vec3d crystal, BlockView view) {
        boolean cached = view == mc.world && selfMove != null;
        long key = BlockPos.ofFloored(crystal).asLong();
        float raw;
        if (cached && selfCache.containsKey(key)) {
            raw = selfCache.get(key);
        } else {
            Vec3d move = cached ? selfMove : selfMove();
            raw = ExplosionDamage.rawUnshielded(mc.player, mc.player.getEntityPos(), mc.player.getBoundingBox(), crystal, ExplosionDamage.CRYSTAL_POWER, view);
            if (!move.equals(Vec3d.ZERO)) {
                Vec3d half = move.multiply(0.5);
                raw = Math.max(raw, ExplosionDamage.rawUnshielded(mc.player, mc.player.getEntityPos().add(half), mc.player.getBoundingBox().offset(half), crystal, ExplosionDamage.CRYSTAL_POWER, view));
                raw = Math.max(raw, ExplosionDamage.rawUnshielded(mc.player, mc.player.getEntityPos().add(move), mc.player.getBoundingBox().offset(move), crystal, ExplosionDamage.CRYSTAL_POWER, view));
            }
            if (cached) selfCache.put(key, raw);
        }
        return ExplosionDamage.shield(mc.player, mc.player.getEntityPos(), crystal, raw);
    }

    private float targetRaw(Target target, Vec3d crystal, BlockView view) {
        if (view != mc.world) return ExplosionDamage.crystalRaw(target.player(), target.feet(), target.box(), crystal, view);

        TargetRaws cache = rawCache.get(target.player().getId());
        if (cache == null) {
            cache = new TargetRaws();
            rawCache.put(target.player().getId(), cache);
        }
        if (!target.box().equals(cache.box)) {
            cache.box = target.box();
            cache.raws.clear();
        }
        long key = BlockPos.ofFloored(crystal).asLong();
        float raw;
        if (cache.raws.containsKey(key)) {
            raw = cache.raws.get(key);
        } else {
            raw = ExplosionDamage.rawUnshielded(target.player(), target.feet(), target.box(), crystal, ExplosionDamage.CRYSTAL_POWER, view);
            cache.raws.put(key, raw);
        }
        return ExplosionDamage.shield(target.player(), target.feet(), crystal, raw);
    }

    private boolean hurtsFriend(Vec3d crystal, BlockView view) {
        double range = ExplosionDamage.CRYSTAL_RANGE * ExplosionDamage.CRYSTAL_RANGE;
        double limit = maxSelfDamage.get();
        for (PlayerEntity player : friends) {
            if (player.isRemoved() || player.squaredDistanceTo(crystal) > range) continue;

            float health = player.getHealth() + player.getAbsorptionAmount();
            float bound = ExplosionDamage.upperBound(player, player.getEntityPos(), crystal);
            if (bound < health && bound <= limit) continue;
            float damage = ExplosionDamage.crystal(player, player.getEntityPos(), player.getBoundingBox(), crystal, view);
            if (damage >= health || damage > limit) return true;
        }
        return false;
    }

    private boolean open(BlockPos cell) {
        MiningTracker mining = MiningTracker.get();
        return mc.world.getBlockState(cell).isReplaceable() || mining.stage(cell) >= 0 || mining.wasBroken(cell, OPEN_TIME);
    }

    private boolean overlapsOwnCells(BlockPos base) {
        if (ownCells.isEmpty()) return false;

        Box crystal = new Box(base.getX() - 0.5, base.getY() + 1, base.getZ() - 0.5, base.getX() + 1.5, base.getY() + 3, base.getZ() + 1.5);
        for (BlockPos cell : ownCells) {
            if (crystal.intersects(new Box(cell)) && open(cell)) return true;
        }
        return false;
    }

    private List<Target> targets() {
        List<Target> targets = new ArrayList<>();
        double range = targetRange.get() * targetRange.get();
        int ticks = predictTicks();

        for (PlayerEntity player : mc.world.getPlayers()) {
            if (player == mc.player || !player.isAlive() || Core.host().isFriend(player)) continue;
            if (ExplosionDamage.isInvulnerable(player) || player.squaredDistanceTo(mc.player) > range) continue;
            targets.add(makeTarget(player, ticks));
        }

        return targets;
    }

    private int predictTicks() {
        return predict.get() ? Math.min(MathHelper.ceil(LatencyManager.get().rtt() / 50.0), MAX_PREDICT_TICKS) : 0;
    }

    private Target makeTarget(PlayerEntity player, int ticks) {
        Box box = MotionTracker.get().predict(player, ticks);
        Vec3d feet = new Vec3d((box.minX + box.maxX) / 2, box.minY, (box.minZ + box.maxZ) / 2);
        float health = player.getHealth() + player.getAbsorptionAmount();
        LongSet gaps = new LongOpenHashSet();
        for (BlockPos cell : Targets.ring(Targets.columns(Targets.box(player)), 0)) gaps.add(cell.asLong());
        return new Target(player, box, feet, health, minDamage(player, health), gaps, Blast.Defense.of(player), player == focus, Targets.holdsTotem(player));
    }

    private float minDamage(PlayerEntity player, float health) {
        if (health <= facePlaceHealth.get()) return facePlaceDamage.get().floatValue();

        for (EquipmentSlot slot : EquipmentSlot.VALUES) {
            if (slot.getType() != EquipmentSlot.Type.HUMANOID_ARMOR) continue;

            ItemStack stack = player.getEquippedStack(slot);
            if (!stack.isDamageable()) continue;
            if ((stack.getMaxDamage() - stack.getDamage()) * 100 <= armorBreak.get() * stack.getMaxDamage()) return facePlaceDamage.get().floatValue();
        }

        return minDamage.get().floatValue();
    }

    private List<BlockPos> candidates() {
        List<BlockPos> candidates = new ArrayList<>();
        Vec3d eye = mc.player.getEyePos();
        BlockPos center = BlockPos.ofFloored(eye);
        int radius = MathHelper.ceil(placeRange.get()) + 1;
        double reach = placeRange.get() + Math.sqrt(3) / 2;
        BlockPos.Mutable base = new BlockPos.Mutable();

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    base.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
                    double ox = base.getX() + 0.5 - eye.x;
                    double oy = base.getY() + 0.5 - eye.y;
                    double oz = base.getZ() + 0.5 - eye.z;
                    if (ox * ox + oy * oy + oz * oz > reach * reach) continue;
                    if (!isBase(mc.world.getBlockState(base)) || !inPlaceRange(base) || !breakable(base)) continue;
                    BlockPos fixed = base.toImmutable();
                    if (!(mc.world.isAir(fixed.up()) || InteractionManager.extinguishable(fixed.up())) || CrystalTracker.get().placingOverlaps(fixed) || CrystalTracker.get().isBlacklisted(fixed) || blocked(fixed)) continue;
                    if (overlapsOwnCells(fixed)) continue;
                    candidates.add(fixed);
                }
            }
        }

        return candidates;
    }

    private boolean blocked(BlockPos base) {
        BlockPos above = base.up();
        Box box = new Box(above.getX(), above.getY(), above.getZ(), above.getX() + 1, above.getY() + 2, above.getZ() + 1);
        CrystalTracker tracker = CrystalTracker.get();
        if (!mc.world.getOtherEntities(null, box, entity -> !(entity instanceof PlayerEntity) && EntityPredicates.EXCEPT_SPECTATOR.test(entity)
            && (!(entity instanceof EndCrystalEntity || entity instanceof ItemEntity || entity instanceof ExperienceOrbEntity) || tracker.isAlive(entity))).isEmpty()) return true;
        for (PlayerEntity player : mc.world.getPlayers()) {
            if (player.isSpectator()) continue;
            Box body = player == mc.player ? player.getBoundingBox() : Targets.box(player);
            if (body.intersects(box)) return true;
        }
        return false;
    }

    private Vec3d breakAim(EndCrystalEntity crystal) {
        BlockPos base = crystal.getBlockPos().down();
        Box box = crystal.getBoundingBox();
        Vec3d eye = mc.player.getEyePos();
        Vec3d preferred = isBase(mc.world.getBlockState(base)) ? InteractionManager.get().crystalAim(base) : box.getCenter();
        if (!rotate.get()) return preferred;

        Vec3d look = Vec3d.fromPolar(RotationManager.get().serverPitch(), RotationManager.get().serverYaw());
        Vec3d along = eye.add(look.multiply(Math.max(0, box.getCenter().subtract(eye).dotProduct(look))));
        Box inner = box.contract(AIM_INSET);
        Vec3d nearest = new Vec3d(MathHelper.clamp(along.x, inner.minX, inner.maxX), MathHelper.clamp(along.y, inner.minY, inner.maxY), MathHelper.clamp(along.z, inner.minZ, inner.maxZ));
        Vec3d middle = box.getCenter();
        Vec3d[] points = {preferred, nearest, middle, new Vec3d(middle.x, box.maxY - AIM_INSET, middle.z), new Vec3d(middle.x, box.minY + AIM_INSET, middle.z)};

        Vec3d best = null;
        float bestTurn = Float.MAX_VALUE;
        for (int i = 0; i < points.length; i++) {
            Vec3d point = points[i];
            if (i == 0 && box.raycast(eye, eye.add(point.subtract(eye).normalize().multiply(breakRange.get() + 1))).isEmpty()) continue;
            if (!throughWalls.get() && !clearLine(i == 0 ? point.add(0, PREFERRED_LOS_LIFT, 0) : point)) continue;
            float turn = turn(point) - (i == 0 ? PREFERRED_AIM_SLACK : 0);
            if (turn < bestTurn) {
                best = point;
                bestTurn = turn;
            }
        }
        return best == null ? preferred : best;
    }

    private boolean canUse(BlockPos base) {
        if (throughWalls.get()) return true;
        InteractionManager interaction = InteractionManager.get();
        return interaction.visiblePoint(base, interaction.crystalFace(base), mc.player.getEyePos(), Double.MAX_VALUE) != null;
    }

    private boolean canHit(EndCrystalEntity crystal) {
        if (throughWalls.get()) return true;
        Box box = crystal.getBoundingBox();
        Vec3d middle = box.getCenter();
        return clearLine(middle) || clearLine(new Vec3d(middle.x, box.maxY - 0.1, middle.z)) || clearLine(new Vec3d(middle.x, box.minY + 0.1, middle.z));
    }

    private boolean clearLine(Vec3d point) {
        return mc.world.raycast(new RaycastContext(mc.player.getEyePos(), point, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, mc.player)).getType() == HitResult.Type.MISS;
    }

    private boolean rotationReady(Box box, Vec3d aim, double range, int priority) {
        if (!rotate.get()) return true;
        if (!rotateBreak.get() && priority != PLACE_PRIORITY && priority != GAP_PRIORITY && priority != AIM_PRIORITY) return true;

        RotationManager rotations = RotationManager.get();
        if (rotations.isLookingAt(box, range + RAY_SLACK)) {
            rotations.request(priority, rotations.serverYaw(), rotations.serverPitch(), rotateSpeed.get().floatValue(), moveFix.get());
            return true;
        }
        rotations.request(priority, aim, rotateSpeed.get().floatValue(), moveFix.get());
        return rotations.isLookingAt(box, range + RAY_SLACK);
    }

    private boolean standsOn(BlockPos base) {
        BlockPos above = base.up();
        return mc.player.getBoundingBox().intersects(new Box(above.getX(), above.getY(), above.getZ(), above.getX() + 1, above.getY() + 2, above.getZ() + 1));
    }

    private boolean breakable(BlockPos base) {
        return crystalBox(base).squaredMagnitude(mc.player.getEyePos()) <= breakRange.get() * breakRange.get();
    }

    private static Box crystalBox(BlockPos base) {
        return new Box(base.getX() - 0.5, base.getY() + 1, base.getZ() - 0.5, base.getX() + 1.5, base.getY() + 3, base.getZ() + 1.5);
    }

    private boolean inPlaceRange(BlockPos base) {
        return new Box(base).squaredMagnitude(mc.player.getEyePos()) <= placeRange.get() * placeRange.get() && InteractionManager.centerReach(base);
    }

    private int baseSlot() {
        for (Block block : baseBlocks.get()) {
            int slot = InventoryManager.get().find(stack -> stack.getItem() instanceof BlockItem item && item.getBlock() == block, 0, InventoryManager.MAIN_END);
            if (slot != -1) return slot;
        }
        return -1;
    }

    private static boolean isBase(BlockState state) {
        return state.isOf(Blocks.OBSIDIAN) || state.isOf(Blocks.BEDROCK);
    }

    private static Vec3d crystalCenter(BlockPos base) {
        return new Vec3d(base.getX() + 0.5, base.getY() + 1.0, base.getZ() + 0.5);
    }

    private boolean canAct() {
        return mc.player != null && mc.world != null && mc.interactionManager != null && mc.getNetworkHandler() != null
            && mc.player.isAlive() && !mc.player.isSpectator();
    }

    private final class ScoreModel implements CrystalScore.Model {
        @Override
        public float bound(Target target, Vec3d crystal) {
            return target.defense().reduce(ExplosionDamage.rawBound(target.player(), target.feet(), crystal, ExplosionDamage.CRYSTAL_POWER));
        }

        @Override
        public float raw(Target target, Vec3d crystal, BlockView view) {
            return targetRaw(target, crystal, view);
        }

        @Override
        public float damage(Target target, float raw, long arrival) {
            return arrival != 0 ? HurtTracker.get().effective(target.player(), target.defense(), raw, arrival) : target.defense().reduce(raw);
        }

        @Override
        public float self(Vec3d crystal, BlockView view, long arrival) {
            return arrival != 0 ? HurtTracker.get().effectiveOnSelf(selfRaw(crystal, view), arrival) : selfDamage(crystal, view);
        }

        @Override
        public boolean hurtsFriend(Vec3d crystal, BlockView view) {
            return AutoCrystal.this.hurtsFriend(crystal, view);
        }
    }
}
