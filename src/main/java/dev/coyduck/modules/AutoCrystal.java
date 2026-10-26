package dev.coyduck.modules;

import dev.coyduck.Coyduck;
import dev.coyduck.events.MineStopEvent;
import dev.coyduck.events.PacketApplyEvent;
import dev.coyduck.events.WorldResetEvent;
import dev.coyduck.managers.CrystalTracker;
import dev.coyduck.managers.HurtTracker;
import dev.coyduck.managers.InteractionManager;
import dev.coyduck.managers.InventoryManager;
import dev.coyduck.managers.LatencyManager;
import dev.coyduck.managers.MiningManager;
import dev.coyduck.managers.MiningTracker;
import dev.coyduck.managers.MotionTracker;
import dev.coyduck.managers.RotationManager;
import dev.coyduck.utils.BlockOverlay;
import dev.coyduck.utils.ExplosionDamage;
import dev.coyduck.utils.Targets;
import it.unimi.dsi.fastutil.ints.Int2FloatOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.longs.Long2FloatOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import meteordevelopment.meteorclient.events.entity.EntityAddedEvent;
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
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.combat.CrystalAura;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ExperienceOrbEntity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.PersistentProjectileEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PlayerInteractEntityC2SPacket;
import net.minecraft.predicate.entity.EntityPredicates;
import net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ChunkDeltaUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.EntitiesDestroyS2CPacket;
import net.minecraft.network.packet.s2c.play.ExplosionS2CPacket;
import net.minecraft.util.Util;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.BlockView;

import java.util.ArrayList;
import java.util.List;

public class AutoCrystal extends Module {
    private static final long OPEN_TIME = 2_000;
    private static final int PLACE_PRIORITY = 100;
    private static final int GAP_PRIORITY = 105;
    private static final int DEFEND_PRIORITY = 106;
    private static final int BREAK_PRIORITY = 110;
    private static final int PREDICT_DROP = 3;
    private static final float LETHAL_BONUS = 100.0f;
    private static final float DEFENSE_THREAT = 4.0f;
    private static final float DEFENSE_FREE = 0.5f;
    private static final long RENDER_TIME = 500;
    private static final double RAY_SLACK = 2.0;
    private static final double SERVER_BREAK_RANGE = 6.0;
    private static final double PREDICT_CLEAR_RANGE = 8.0;
    private static final double CLEAR_RANGE = 4.0;

    private record Target(PlayerEntity player, Box box, Vec3d feet, float health, float minDamage, LongSet gaps) {}

    private static final int FOCUS_TICKS = 20;
    private static final float FOCUS_BONUS = 1.0f;
    private static final int MAX_PREDICT_TICKS = 6;
    private static final long BROKEN_GAP_TIME = 2_000;
    private static final float GAP_BONUS = 2.0f;
    private static final float HIDDEN_PENALTY = 2.0f;
    private static final float LOW_HEALTH_BONUS = 3.0f;
    private static final int GAP_PLACES = 1;

    private record Choice(Target target, float damage, float raw, float self, float score) {
        boolean beats(Choice other) {
            if (other == null) return true;
            if (Math.abs(score - other.score) > 0.5f) return score > other.score;
            return self < other.self;
        }
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
        .description("Range from your eyes to the block a crystal is placed on. The server allows up to 5.5.")
        .defaultValue(5)
        .range(1, 5.5)
        .sliderRange(1, 5.5)
        .build()
    );

    private final Setting<Double> breakRange = sgGeneral.add(new DoubleSetting.Builder()
        .name("break-range")
        .description("Range from your eyes to a crystal you break. The server allows up to 6.")
        .defaultValue(5.5)
        .range(1, 6)
        .sliderRange(1, 6)
        .build()
    );

    private final Setting<InventoryManager.SwitchMode> switchMode = sgGeneral.add(new EnumSetting.Builder<InventoryManager.SwitchMode>()
        .name("switch")
        .description("Silent selects the item's hotbar slot for a moment and sends no inventory clicks; an item in your inventory is first moved into an empty hotbar slot once. InvSwap swaps the item into your hand and back with inventory clicks, which some servers answer by resending the whole inventory. While you eat, InvSwap is always used. Your offhand totem is never touched.")
        .defaultValue(InventoryManager.SwitchMode.Silent)
        .build()
    );

    private final Setting<Integer> placesPerTick = sgGeneral.add(new IntSetting.Builder()
        .name("places-per-tick")
        .description("Most crystals placed in one tick. Two lets a crystal broken mid tick be replaced at once.")
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
        .description("Pauses while you eat. When off, crystals are swapped into your main hand and back even while you eat, which can cut the eating. Your offhand totem is never touched.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> showStatus = sgGeneral.add(new BoolSetting.Builder()
        .name("show-status")
        .description("Shows in the module list why the last crystal was or was not placed.")
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
        .description("Most damage a crystal may deal to you. A crystal that would pop or kill you is never used.")
        .defaultValue(8)
        .range(0, 36)
        .sliderRange(0, 36)
        .build()
    );

    private final Setting<Double> minHealth = sgDamage.add(new DoubleSetting.Builder()
        .name("min-health")
        .description("Least health plus absorption you keep after a crystal's damage to you. Ones that would leave you lower are not used, so your own crystals never set you up for the next hit. Ones that can't hurt you are always allowed.")
        .defaultValue(12)
        .range(0, 36)
        .sliderRange(0, 20)
        .build()
    );

    private final Setting<Double> facePlaceHealth = sgDamage.add(new DoubleSetting.Builder()
        .name("face-place-health")
        .description("Uses face place damage once the target's health is at or below this.")
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

    private final Setting<Integer> armorBreak = sgDamage.add(new IntSetting.Builder()
        .name("armor-break")
        .description("Face places once any armor piece of the target is at or below this durability percent.")
        .defaultValue(15)
        .range(0, 100)
        .sliderRange(0, 100)
        .build()
    );

    private final Setting<Double> balance = sgDamage.add(new DoubleSetting.Builder()
        .name("balance")
        .description("Least ratio of the damage a crystal deals to the target over the damage it deals to you. A crystal below it is neither placed nor broken, unless it kills the target. 0 turns it off.")
        .defaultValue(1.0)
        .range(0, 5)
        .sliderRange(0, 3)
        .build()
    );

    private final Setting<Double> selfWeight = sgDamage.add(new DoubleSetting.Builder()
        .name("self-weight")
        .description("How much the damage to you counts against a crystal when choosing the best one. 0 only looks at the target's damage.")
        .defaultValue(0.5)
        .range(0, 2)
        .sliderRange(0, 2)
        .build()
    );

    private final Setting<Boolean> defend = sgDamage.add(new BoolSetting.Builder()
        .name("defend")
        .description("Breaks an enemy crystal near you while your own damage immunity makes the blast almost harmless, so it is wasted instead of hitting you later.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> hurtSync = sgTiming.add(new BoolSetting.Builder()
        .name("hurt-sync")
        .description("Holds a ready crystal until the target's damage immunity ends, so no crystal is spent on a hit that deals nothing.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> predict = sgTiming.add(new BoolSetting.Builder()
        .name("predict")
        .description("Aims at where targets will be when your packets reach the server.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> idPredict = sgTiming.add(new BoolSetting.Builder()
        .name("id-predict")
        .description("Attacks the entity ID a new crystal will most likely get, right after placing it, so it breaks in the same server tick at any ping.")
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
        .description("Skips predicting when the ID could belong to a dropped item, arrow or xp orb. Only needed on servers that kick for attacking those.")
        .defaultValue(false)
        .visible(idPredict::get)
        .build()
    );

    private final Setting<Boolean> antiSurround = sgGeneral.add(new BoolSetting.Builder()
        .name("anti-surround")
        .description("Fills a gap in the target's surround with a crystal the moment it opens, and prefers crystals that keep it open, so the gap cannot be closed again.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> basePlace = sgBase.add(new BoolSetting.Builder()
        .name("base-place")
        .description("Places a block to put a crystal on when no existing spot deals enough damage.")
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
        .description("Places the base block without a neighbouring block to click on.")
        .defaultValue(true)
        .visible(basePlace::get)
        .build()
    );

    private final Setting<Boolean> rotate = sgRotate.add(new BoolSetting.Builder()
        .name("rotate")
        .description("Looks at every crystal and block before using it. Needed on servers with Grim.")
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

    private final Setting<Boolean> moveFix = sgRotate.add(new BoolSetting.Builder()
        .name("move-fix")
        .description("Moves you relative to the rotation the server sees, so the anticheat's movement prediction matches.")
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
        .defaultValue(new SettingColor(185, 74, 230, 40))
        .visible(render::get)
        .build()
    );

    private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder()
        .name("line-color")
        .description("Highlight line color.")
        .defaultValue(new SettingColor(185, 74, 230, 200))
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
    private final Long2ObjectOpenHashMap<Int2FloatOpenHashMap> rawCache = new Long2ObjectOpenHashMap<>();
    private List<BlockPos> ownCells = List.of();
    private BlockPos renderPos;
    private long renderTime;
    private long cacheSignature;
    private final IntOpenHashSet predictedIds = new IntOpenHashSet();
    private long predictedBase;
    private long predictedAt;
    private PlayerEntity predictedTarget;
    private String status;
    private boolean rejectedSelf;
    private boolean rejectedFriend;
    private boolean rejectedBalance;
    private final Long2LongOpenHashMap clearing = new Long2LongOpenHashMap();

    public AutoCrystal() {
        super(Coyduck.CATEGORY, "auto-crystal", "Places and breaks crystals at any ping.");
    }

    @Override
    public void onActivate() {
        Module meteor = Modules.get().get(CrystalAura.class);
        if (meteor != null && meteor.isActive()) {
            meteor.toggle();
            info("Disabled %s, it fights over the same crystals.", meteor.title);
        }

        placeBudget = placesPerTick.get();
        gapBudget = GAP_PLACES;
        breakBudget = breaksPerTick.get();
        lastTarget = null;
        focus = null;
        challengeTicks = 0;
        releaseAt = 0;
        renderPos = null;
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
        if (!isActive() || !antiSurround.get() || !canAct() || !InteractionManager.get().hasCrystals()) return false;
        if (pauseMining.get() && mc.interactionManager.isBreakingBlock()) return false;
        if (pauseEating.get() && mc.player.isUsingItem() || InventoryManager.get().isOffhandEating()) return false;

        long signature = signature();
        if (signature != cacheSignature) {
            clearCache();
            cacheSignature = signature;
        }

        Target target = makeTarget(player, predictTicks());
        Surround surround = Modules.get().get(Surround.class);
        ownCells = surround != null && surround.isActive() ? Targets.ownRing() : List.of();
        return canFillGap(gap, target, mc.player.getHealth() + mc.player.getAbsorptionAmount());
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
        clearCache();
        InteractionManager.get().trackDrops(idPredict.get() && kickGuard.get());
        if (predictedAt != 0 && Util.getMeasuringTimeMs() - predictedAt > LatencyManager.get().timeout()) endPrediction();
        long now = Util.getMeasuringTimeMs();
        clearing.values().removeIf(until -> now >= until);
        pass();
        updateFocus();
    }

    @EventHandler
    private void onWorldReset(WorldResetEvent event) {
        focus = null;
        lastTarget = null;
        challengeTicks = 0;
        predictedAt = 0;
        predictedTarget = null;
        clearing.clear();
        renderPos = null;
        releaseAt = 0;
    }

    private void endPrediction() {
        if (predictedTarget != null) HurtTracker.get().forgetSpeculative(predictedTarget);
        predictedAt = 0;
    }

    @EventHandler
    private void onTickPost(TickEvent.Post event) {
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
                CrystalTracker.get().kill(crystal, LatencyManager.get().timeout());
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

        boolean changed = switch (event.packet) {
            case ExplosionS2CPacket packet -> near(packet.center());
            case BlockUpdateS2CPacket packet -> near(Vec3d.ofCenter(packet.getPos()));
            case ChunkDeltaUpdateS2CPacket packet -> {
                boolean[] found = {false};
                packet.visitUpdates((pos, state) -> found[0] |= near(Vec3d.ofCenter(pos)));
                yield found[0];
            }
            default -> false;
        };

        if (changed) clearCache();
        if (relevant) pass();
    }

    @EventHandler
    private void onPacketPre(PacketApplyEvent.Pre event) {
        if (!(event.packet instanceof EntitiesDestroyS2CPacket packet) || mc.world == null || mc.player == null) return;

        for (int id : packet.getEntityIds()) {
            Entity entity = mc.world.getEntityById(id);
            if ((entity instanceof EndCrystalEntity || entity instanceof ItemEntity || entity instanceof ExperienceOrbEntity) && near(entity.getEntityPos())) {
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
        pass();
    }

    private void clearCache() {
        selfCache.clear();
        rawCache.clear();
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (releaseAt != 0 && Util.getMeasuringTimeMs() >= releaseAt) {
            releaseAt = 0;
            pass();
        }

        if (!render.get() || renderPos == null) return;
        if (Util.getMeasuringTimeMs() - renderTime > RENDER_TIME) {
            renderPos = null;
            return;
        }
        event.renderer.box(renderPos, sideColor.get(), lineColor.get(), shapeMode.get(), 0);
    }

    private void pass() {
        if (running || !canAct()) return;

        running = true;
        try {
            long signature = signature();
            if (signature != cacheSignature) {
                clearCache();
                cacheSignature = signature;
            }
            run();
        } finally {
            running = false;
        }
    }

    private void run() {
        if (pauseMining.get() && mc.interactionManager.isBreakingBlock()) {
            status = "mining";
            return;
        }

        List<Target> targets = targets();
        if (targets.isEmpty()) {
            lastTarget = null;
            status = null;
            return;
        }

        Surround surround = Modules.get().get(Surround.class);
        ownCells = surround != null && surround.isActive() ? Targets.ownRing() : List.of();

        float health = mc.player.getHealth() + mc.player.getAbsorptionAmount();
        if (InventoryManager.get().isOffhandEating()) {
            status = "eating";
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

        if (antiSurround.get() && gapBudget > 0) fillGaps(targets, health);
        if (placeBudget <= 0) return;
        if (holding) {
            status = "hurt-sync";
            return;
        }

        List<BlockPos> candidates = candidates();
        rejectedSelf = false;
        rejectedFriend = false;
        rejectedBalance = false;
        BlockPos spot = null;
        Choice spotChoice = null;
        BlockPos seen = null;
        Choice seenChoice = null;
        Vec3d eye = mc.player.getEyePos();
        for (BlockPos base : candidates) {
            Choice choice = evaluate(targets, crystalCenter(base), health, 0, mc.world);
            if (choice == null) continue;
            if (choice.beats(spotChoice)) {
                spot = base;
                spotChoice = choice;
            }
            if (choice.beats(seenChoice) && InteractionManager.get().visiblePoint(base, Direction.UP, eye, Double.MAX_VALUE) != null) {
                seen = base;
                seenChoice = choice;
            }
        }
        if (seen != null && seenChoice.score() >= spotChoice.score() - HIDDEN_PENALTY) {
            spot = seen;
            spotChoice = seenChoice;
        }

        if (spot != null) {
            placeCrystal(spot, spotChoice);
            return;
        }

        if (candidates.isEmpty()) status = "no spot";
        else if (rejectedSelf) status = "self damage";
        else if (rejectedFriend) status = "friend";
        else if (rejectedBalance) status = "balance";
        else status = "low damage";

        if (antiSurround.get() && placeClearing(targets, health)) return;
        if (basePlace.get() && !CrystalTracker.get().hasPlacing()) placeBase(targets, health);
    }

    private boolean placeClearing(List<Target> targets, float health) {
        if (!clearing.isEmpty()) return false;

        BlockPos gap = null;
        for (Target target : targets) {
            for (long key : target.gaps) {
                BlockPos cell = BlockPos.fromLong(key);
                BlockPos base = cell.down();
                if (!mc.world.getBlockState(cell).isAir() || !isBase(mc.world.getBlockState(base))) continue;
                if (!inPlaceRange(base) || !breakable(base) || overlapsOwnCells(base) || !blockedByDrops(base)) continue;
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
            if (distance > bestDistance) continue;
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
        renderPos = best;
        renderTime = Util.getMeasuringTimeMs();
        return true;
    }

    private boolean blockedByDrops(BlockPos base) {
        BlockPos above = base.up();
        Box box = new Box(above.getX(), above.getY(), above.getZ(), above.getX() + 1, above.getY() + 2, above.getZ() + 1);
        CrystalTracker tracker = CrystalTracker.get();
        boolean drops = false;
        for (Entity entity : mc.world.getOtherEntities(null, box, EntityPredicates.EXCEPT_SPECTATOR)) {
            if (!tracker.isAlive(entity)) continue;
            if (!(entity instanceof ItemEntity) && !(entity instanceof ExperienceOrbEntity)) return false;
            drops = true;
        }
        return drops;
    }

    private void breakClearing(float health) {
        if (clearing.isEmpty()) return;

        CrystalTracker tracker = CrystalTracker.get();
        long arrival = Util.getMeasuringTimeMs() + LatencyManager.get().rtt() / 2;
        double range = breakRange.get() * breakRange.get();

        for (Entity entity : mc.world.getEntities()) {
            if (breakBudget <= 0) return;
            if (!(entity instanceof EndCrystalEntity crystal) || !tracker.isAlive(crystal)) continue;
            long base = crystal.getBlockPos().down().asLong();
            if (!clearing.containsKey(base) || crystal.getBoundingBox().squaredMagnitude(mc.player.getEyePos()) > range) continue;

            Vec3d center = crystal.getEntityPos();
            if (!selfSafe(center, health, arrival, mc.world) || hurtsFriend(center, mc.world)) continue;
            if (!InteractionManager.get().canAttack()) return;
            if (!rotationReady(crystal.getBoundingBox(), breakAim(crystal), breakRange.get(), BREAK_PRIORITY)) return;
            if (!InteractionManager.get().attack(crystal, switchMode.get(), swing.get(), !pauseEating.get())) return;

            tracker.explodeAt(center);
            clearing.remove(base);
            breakBudget--;
        }
    }

    private boolean leavesLow(float self, float health) {
        return self > 0 && health - self < minHealth.get();
    }

    private boolean selfSafe(Vec3d crystal, float health, long arrival, BlockView view) {
        if (arrival == 0) {
            float full = selfDamage(crystal, view);
            return full < health && !leavesLow(full, health) && full <= maxSelfDamage.get();
        }
        float effective = HurtTracker.get().effectiveOnSelf(selfRaw(crystal, view), arrival);
        return effective < health && !leavesLow(effective, health) && effective <= maxSelfDamage.get();
    }

    private void fillGaps(List<Target> targets, float health) {
        for (Target target : targets) {
            for (long key : target.gaps) {
                if (gapBudget <= 0) return;

                BlockPos gap = BlockPos.fromLong(key);
                if (!canFillGap(gap, target, health)) continue;

                BlockPos base = gap.down();
                Vec3d center = crystalCenter(base);
                if (!rotationReady(new Box(base), InteractionManager.get().crystalAim(base), placeRange.get() + 1, GAP_PRIORITY)) return;

                if (!InteractionManager.get().placeCrystal(base, switchMode.get(), swing.get(), null, !pauseEating.get())) return;
                gapBudget--;
                status = "gap";
                lastTarget = target.player;
                renderPos = base;
                renderTime = Util.getMeasuringTimeMs();
            }
        }
    }

    private boolean canFillGap(BlockPos gap, Target target, float health) {
        BlockPos base = gap.down();
        if (!mc.world.getBlockState(gap).isAir() || !isBase(mc.world.getBlockState(base))) return false;
        if (CrystalTracker.get().placingOverlaps(base) || blocked(base) || !inPlaceRange(base) || !breakable(base) || overlapsOwnCells(base)) return false;

        Vec3d center = crystalCenter(base);
        if (!selfSafe(center, health, 0, mc.world) || hurtsFriend(center, mc.world)) return false;

        float raw = ExplosionDamage.crystalRaw(target.player, target.feet, target.box, center, mc.world);
        float damage = ExplosionDamage.mitigate(target.player, raw);
        return damage >= facePlaceDamage.get() && balanced(damage, selfDamage(center, mc.world), target.health);
    }

    private boolean balanced(float damage, float self, float health) {
        return balance.get() <= 0 || damage >= health || damage >= self * balance.get();
    }

    private boolean breakCrystals(List<Target> targets, float health) {
        CrystalTracker tracker = CrystalTracker.get();
        long arrival = Util.getMeasuringTimeMs() + LatencyManager.get().rtt() / 2;
        long release = 0;
        boolean held = false;
        boolean broke = false;

        while (breakBudget > 0) {
            EndCrystalEntity best = null;
            Choice bestChoice = null;

            for (Entity entity : mc.world.getEntities()) {
                if (!(entity instanceof EndCrystalEntity crystal) || !tracker.isAlive(crystal)) continue;
                if (crystal.getBoundingBox().squaredMagnitude(mc.player.getEyePos()) > breakRange.get() * breakRange.get()) continue;

                Vec3d center = crystal.getEntityPos();
                Choice choice = evaluate(targets, center, health, hurtSync.get() ? arrival : 0, mc.world);
                if (choice == null) {
                    if (hurtSync.get()) {
                        Choice ready = evaluate(targets, center, health, 0, mc.world);
                        if (ready != null) {
                            if (rotate.get()) rotationReady(crystal.getBoundingBox(), breakAim(crystal), breakRange.get(), BREAK_PRIORITY);
                            held = true;
                            long time = HurtTracker.get().releaseTime(ready.target.player);
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
            if (!rotationReady(best.getBoundingBox(), breakAim(best), breakRange.get(), BREAK_PRIORITY)) break;
            if (!InteractionManager.get().attack(best, switchMode.get(), swing.get(), !pauseEating.get())) break;


            tracker.explodeAt(best.getEntityPos());
            for (Target target : targets) {
                float hit = target == bestChoice.target ? bestChoice.raw : ExplosionDamage.crystalRaw(target.player, target.feet, target.box, best.getEntityPos(), mc.world);
                if (hit > 0) HurtTracker.get().expect(target.player, hit);
            }
            lastTarget = bestChoice.target.player;
            breakBudget--;
            broke = true;
        }

        releaseAt = release;
        breakClearing(health);
        if (defend.get() && breakBudget > 0 && defend(targets, health)) broke = true;
        return held && !broke;
    }

    private boolean defend(List<Target> targets, float health) {
        CrystalTracker tracker = CrystalTracker.get();
        long arrival = Util.getMeasuringTimeMs() + LatencyManager.get().rtt() / 2;
        double range = breakRange.get() * breakRange.get();
        boolean broke = false;

        for (Entity entity : mc.world.getEntities()) {
            if (breakBudget <= 0) return broke;
            if (!(entity instanceof EndCrystalEntity crystal) || !tracker.isAlive(crystal)) continue;
            if (crystal.getBoundingBox().squaredMagnitude(mc.player.getEyePos()) > range) continue;

            Vec3d center = crystal.getEntityPos();
            float raw = ExplosionDamage.crystalRaw(mc.player, mc.player.getEntityPos(), mc.player.getBoundingBox(), center, mc.world);
            float full = ExplosionDamage.mitigate(mc.player, raw);
            if (full < DEFENSE_THREAT || full >= health) continue;

            float cost = HurtTracker.get().effectiveOnSelf(raw, arrival);
            if (cost > DEFENSE_FREE || hurtsFriend(center, mc.world)) continue;
            if (evaluate(targets, center, health, 0, mc.world) != null || destroysReady(targets, center, health)) continue;

            if (!InteractionManager.get().canAttack()) return broke;
            if (!rotationReady(crystal.getBoundingBox(), breakAim(crystal), breakRange.get(), DEFEND_PRIORITY)) return broke;
            if (!InteractionManager.get().attack(crystal, switchMode.get(), swing.get(), !pauseEating.get())) return broke;

            tracker.explodeAt(center);
            for (Target target : targets) {
                float hit = ExplosionDamage.crystalRaw(target.player, target.feet, target.box, center, mc.world);
                if (hit > 0) HurtTracker.get().expect(target.player, hit);
            }
            breakBudget--;
            broke = true;
        }

        return broke;
    }

    private boolean destroysReady(List<Target> targets, Vec3d center, float health) {
        CrystalTracker tracker = CrystalTracker.get();
        double range = ExplosionDamage.CRYSTAL_RANGE * ExplosionDamage.CRYSTAL_RANGE;

        for (Entity entity : mc.world.getEntities()) {
            if (!(entity instanceof EndCrystalEntity other) || !tracker.isAlive(other) || other.getEntityPos().equals(center)) continue;
            if (other.getEntityPos().squaredDistanceTo(center) > range) continue;
            if (evaluate(targets, other.getEntityPos(), health, 0, mc.world) != null) return true;
        }
        return tracker.placingNear(center, ExplosionDamage.CRYSTAL_RANGE);
    }

    private void placeCrystal(BlockPos base, Choice choice) {
        Box box = new Box(base);
        Vec3d aim = InteractionManager.get().crystalAim(base);
        if (!rotationReady(box, aim, placeRange.get() + 1, PLACE_PRIORITY)) {
            status = "rotating";
            return;
        }

        Runnable after = idPredict.get() ? () -> predictAttack(base, choice) : null;
        if (!InteractionManager.get().placeCrystal(base, switchMode.get(), swing.get(), after, !pauseEating.get())) {
            status = "switch";
            return;
        }

        placeBudget--;
        status = String.format("%.1f dmg", choice.damage);
        lastTarget = choice.target.player;
        renderPos = base;
        renderTime = Util.getMeasuringTimeMs();
    }

    private void placeBase(List<Target> targets, float health) {
        int blockSlot = baseSlot();
        if (blockSlot == -1) return;

        InteractionManager interaction = InteractionManager.get();
        BlockPos best = null;
        BlockHitResult bestHit = null;
        Choice bestChoice = null;

        for (Target target : targets) {
            BlockPos feet = BlockPos.ofFloored(target.feet);
            List<BlockPos> ring = Targets.ring(Targets.columns(target.box), 0);

            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    for (int dy = -1; dy <= 0; dy++) {
                        BlockPos base = feet.add(dx, dy, dz);
                        if (!interaction.canPlace(base) || !mc.world.isAir(base.up())) continue;
                        if (ring.contains(base) || ownCells.contains(base) || overlapsOwnCells(base)) continue;
                        if (MiningManager.get().isMining(base) || MiningTracker.get().wasBroken(base, BROKEN_GAP_TIME)) continue;
                        if (!inPlaceRange(base) || !breakable(base) || !interaction.obstructions(base).isEmpty() || blocked(base)) continue;
                        if (target.box.intersects(new Box(base))) continue;

                        BlockOverlay view = new BlockOverlay(mc.world).set(base, Blocks.OBSIDIAN.getDefaultState());
                        Choice choice = evaluate(targets, crystalCenter(base), health, 0, view);
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

        if (best == null || !rotationReady(new Box(best), bestHit.getPos(), placeRange.get() + 1, PLACE_PRIORITY)) return;

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
            float effective = HurtTracker.get().effective(choice.target.player, choice.raw, arrival);
            if (effective < choice.target.minDamage && effective < choice.target.health) return;
        }

        if (kickGuard.get() && InteractionManager.get().dropRisk(crystalCenter(base))) return;

        if (predictedAt != 0) endPrediction();
        predictedIds.clear();
        for (int i = 1; i <= idPredictCount.get(); i++) {
            int id = highest + i;
            if (mc.world.getEntityById(id) != null) continue;

            EndCrystalEntity fake = new EndCrystalEntity(mc.world, 0, 0, 0);
            fake.setId(id);
            mc.getNetworkHandler().sendPacket(PlayerInteractEntityC2SPacket.attack(fake, mc.player.isSneaking()));
            predictedIds.add(id);
        }

        if (predictedIds.isEmpty()) return;
        InteractionManager.get().noteOwnBreak(crystalCenter(base));
        predictedAt = Util.getMeasuringTimeMs();
        predictedBase = base.asLong();
        predictedTarget = choice.target.player;
        HurtTracker.get().expectSpeculative(choice.target.player, choice.raw);
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
            if (player != mc.player && Friends.get().isFriend(player) && player.squaredDistanceTo(mc.player) <= friendRange) return false;
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
                    if (self >= health || leavesLow(self, health) || self > maxSelfDamage.get()) return false;
                }
            }
        }

        return true;
    }

    private Choice evaluate(List<Target> targets, Vec3d crystal, float health, long arrival, BlockView view) {
        boolean cached = view == mc.world;
        long key = BlockPos.ofFloored(crystal).asLong();

        boolean reachable = false;
        for (Target target : targets) {
            float bound = ExplosionDamage.upperBound(target.player, target.feet, crystal);
            if (bound >= target.minDamage || bound >= target.health) {
                reachable = true;
                break;
            }
        }
        if (!reachable) return null;

        float self = arrival != 0 ? HurtTracker.get().effectiveOnSelf(selfRaw(crystal, view), arrival) : selfDamage(crystal, view);
        if (self >= health) {
            rejectedSelf = true;
            return null;
        }
        boolean low = leavesLow(self, health);
        if (self > maxSelfDamage.get()) {
            rejectedSelf = true;
            return null;
        }
        if (hurtsFriend(crystal, view)) {
            rejectedFriend = true;
            return null;
        }

        Choice best = null;
        for (Target target : targets) {
            float bound = ExplosionDamage.upperBound(target.player, target.feet, crystal);
            if (bound < target.minDamage && bound < target.health) continue;
            float bonus = FOCUS_BONUS + (bound >= target.health ? LETHAL_BONUS : 0) + (antiSurround.get() && target.gaps.contains(key) ? GAP_BONUS : 0);
            if (best != null && bound + bonus < best.score) continue;

            float raw;
            Int2FloatOpenHashMap raws = cached ? rawCache.computeIfAbsent(key, k -> new Int2FloatOpenHashMap()) : null;
            if (raws != null && raws.containsKey(target.player.getId())) {
                raw = raws.get(target.player.getId());
            } else {
                raw = ExplosionDamage.crystalRaw(target.player, target.feet, target.box, crystal, view);
                if (raws != null) raws.put(target.player.getId(), raw);
            }

            float damage = arrival != 0 ? HurtTracker.get().effective(target.player, raw, arrival) : ExplosionDamage.mitigate(target.player, raw);
            if (damage < target.minDamage && damage < target.health) continue;
            if (low && !(damage >= target.health && AutoTotem.canAffordPop())) {
                rejectedSelf = true;
                continue;
            }
            if (!balanced(damage, self, target.health)) {
                rejectedBalance = true;
                continue;
            }

            float counted = Math.min(damage, target.health);
            float score = counted - self * selfWeight.get().floatValue() + (target.player == focus ? FOCUS_BONUS : 0)
                + LOW_HEALTH_BONUS * Math.min(damage / Math.max(target.health, 1.0f), 1.0f);
            if (damage >= target.health) score += LETHAL_BONUS;
            if (antiSurround.get() && target.gaps.contains(key)) score += GAP_BONUS;
            Choice choice = new Choice(target, damage, raw, self, score);
            if (choice.beats(best)) best = choice;
        }

        return best;
    }

    private float selfDamage(Vec3d crystal, BlockView view) {
        return ExplosionDamage.mitigate(mc.player, selfRaw(crystal, view));
    }

    private float selfRaw(Vec3d crystal, BlockView view) {
        boolean cached = view == mc.world;
        long key = BlockPos.ofFloored(crystal).asLong();
        if (cached && selfCache.containsKey(key)) return selfCache.get(key);

        float raw = ExplosionDamage.crystalRaw(mc.player, mc.player.getEntityPos(), mc.player.getBoundingBox(), crystal, view);
        if (cached) selfCache.put(key, raw);
        return raw;
    }

    private boolean hurtsFriend(Vec3d crystal, BlockView view) {
        double range = ExplosionDamage.CRYSTAL_RANGE * ExplosionDamage.CRYSTAL_RANGE;
        for (PlayerEntity player : mc.world.getPlayers()) {
            if (player == mc.player || !Friends.get().isFriend(player) || player.squaredDistanceTo(crystal) > range) continue;

            float damage = ExplosionDamage.crystal(player, player.getEntityPos(), player.getBoundingBox(), crystal, view);
            if (damage >= player.getHealth() + player.getAbsorptionAmount() || damage > maxSelfDamage.get()) return true;
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
            if (open(cell) && crystal.intersects(new Box(cell))) return true;
        }
        return false;
    }

    private List<Target> targets() {
        List<Target> targets = new ArrayList<>();
        double range = targetRange.get() * targetRange.get();
        int ticks = predictTicks();

        for (PlayerEntity player : mc.world.getPlayers()) {
            if (player == mc.player || !player.isAlive() || Friends.get().isFriend(player)) continue;
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
        return new Target(player, box, feet, health, minDamage(player, health), gaps);
    }

    private long signature() {
        long hash = position(mc.player.getEntityPos());
        hash = hash * 31 + mc.player.getPose().ordinal();
        hash = hash * 31 + mc.player.getArmor();
        hash = hash * 31 + mc.player.getStatusEffects().size();
        double range = targetRange.get() * targetRange.get();

        for (PlayerEntity player : mc.world.getPlayers()) {
            if (player == mc.player || player.squaredDistanceTo(mc.player) > range) continue;
            hash = hash * 31 + player.getId();
            hash = hash * 31 + position(MotionTracker.get().position(player));
            hash = hash * 31 + player.getPose().ordinal();
            hash = hash * 31 + (player.isBlocking() ? 1 : 0);
        }
        return hash;
    }

    private static long position(Vec3d pos) {
        long hash = Double.doubleToLongBits(pos.x);
        hash = hash * 31 + Double.doubleToLongBits(pos.y);
        return hash * 31 + Double.doubleToLongBits(pos.z);
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
        BlockPos eye = BlockPos.ofFloored(mc.player.getEyePos());
        int radius = MathHelper.ceil(placeRange.get()) + 1;

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos base = eye.add(dx, dy, dz);
                    if (!inPlaceRange(base) || !breakable(base) || !isBase(mc.world.getBlockState(base))) continue;
                    if (!mc.world.isAir(base.up()) || CrystalTracker.get().placingOverlaps(base) || CrystalTracker.get().isBlacklisted(base) || blocked(base)) continue;
                    if (overlapsOwnCells(base)) continue;
                    candidates.add(base);
                }
            }
        }

        return candidates;
    }

    private boolean blocked(BlockPos base) {
        BlockPos above = base.up();
        Box box = new Box(above.getX(), above.getY(), above.getZ(), above.getX() + 1, above.getY() + 2, above.getZ() + 1);
        CrystalTracker tracker = CrystalTracker.get();
        return !mc.world.getOtherEntities(null, box, entity -> EntityPredicates.EXCEPT_SPECTATOR.test(entity)
            && (!(entity instanceof EndCrystalEntity || entity instanceof ItemEntity || entity instanceof ExperienceOrbEntity) || tracker.isAlive(entity))).isEmpty();
    }

    private Vec3d breakAim(EndCrystalEntity crystal) {
        BlockPos base = crystal.getBlockPos().down();
        return isBase(mc.world.getBlockState(base)) ? InteractionManager.get().crystalAim(base) : crystal.getBoundingBox().getCenter();
    }

    private boolean rotationReady(Box box, Vec3d aim, double range, int priority) {
        if (!rotate.get()) return true;

        RotationManager rotations = RotationManager.get();
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
        return new Box(base).squaredMagnitude(mc.player.getEyePos()) <= placeRange.get() * placeRange.get();
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
}
