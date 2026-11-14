package dev.coyduck.modules;

import dev.coyduck.Coyduck;
import dev.coyduck.events.MineStopEvent;
import dev.coyduck.events.PacketApplyEvent;
import dev.coyduck.events.WorldResetEvent;
import dev.coyduck.managers.InteractionManager;
import dev.coyduck.managers.CrystalTracker;
import dev.coyduck.managers.HurtTracker;
import dev.coyduck.managers.InventoryManager;
import dev.coyduck.managers.LatencyManager;
import dev.coyduck.managers.MiningManager;
import dev.coyduck.managers.RotationManager;
import dev.coyduck.utils.DuckRender;
import dev.coyduck.utils.BlockOverlay;
import dev.coyduck.utils.ExplosionDamage;
import dev.coyduck.utils.Targets;
import dev.coyduck.utils.Trace;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongSet;
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
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.block.AbstractFireBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.StainedGlassBlock;
import net.minecraft.item.BlockItem;
import net.minecraft.entity.ExperienceOrbEntity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.util.Util;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Items;
import net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ChunkDeltaUpdateS2CPacket;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class AutoCity extends Module implements MiningManager.StopGate, MiningManager.HidesMining {
    public static final int PRIORITY = 50;
    private static final long FILL_DELAY = 150;
    private static final long FILL_GAP = 400;
    private static final int FILL_MAX_TICKS = 12;

    private static final float BURROW_SCORE = 1000;
    private static final float SWITCH_GAIN = 4;
    private static final float SWITCH_PROGRESS = 0.3f;
    private static final long OPEN_LIMIT = 1500;
    private static final long SWITCH_COOLDOWN = 1000;
    private static final float FACE_PENALTY = 1.5f;
    private static final float SPEED_BONUS = 3;
    private static final float SUPPORT_PENALTY = 1;
    private static final long LOST_GRACE = 750;
    private static final long REFILL_BAN = 5000;
    private static final long WORTHLESS_LIMIT = 500;
    private static final long HOLD_STALE = 1000;
    private static final float BURROW_PREEMPT = 0.7f;
    private static final float HOLD_TOLERANCE = 1;
    private static final int TOUCHED_UPDATES = 2;
    private static final int OTHER_TARGETS = 3;
    private static final int OTHER_TARGET_TICKS = 4;
    private static final int VALID = 0;
    private static final int LEFT_HOLE = 1;
    private static final int BROKEN_OFF = 2;

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

    private final Setting<Boolean> cev = sgGeneral.add(new BoolSetting.Builder()
        .name("cev")
        .description("Also mines the block over the head of a target with a roof. AutoCrystal puts a crystal on that block just before it breaks and blows it up as it breaks, right on their head.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> burrow = sgGeneral.add(new BoolSetting.Builder()
        .name("burrow")
        .description("Mines the block a target is burrowed in before anything else.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> rebreak = sgGeneral.add(new BoolSetting.Builder()
        .name("rebreak")
        .description("Breaks the block again the moment it is replaced, without mining it again.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> hurtSync = sgGeneral.add(new BoolSetting.Builder()
        .name("hurt-sync")
        .description("Holds the break of a gap, and every rebreak, until the target's hurt time is over, so the crystal auto-crystal puts in the gap on the same tick deals full damage.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> minDamage = sgGeneral.add(new DoubleSetting.Builder()
        .name("min-damage")
        .description("Least crystal damage the opened gap must allow on the target.")
        .defaultValue(4)
        .range(0, 36)
        .sliderRange(0, 36)
        .build()
    );

    private final Setting<Double> maxSelfDamage = sgGeneral.add(new DoubleSetting.Builder()
        .name("max-self-damage")
        .description("Most damage a crystal in the opened gap may deal to you.")
        .defaultValue(8)
        .range(0, 36)
        .sliderRange(0, 36)
        .build()
    );

    private final Setting<Boolean> doubleMine = sgGeneral.add(new BoolSetting.Builder()
        .name("double-mine")
        .description("Mines the second best block in the background while mining the best one, so two gaps open in about the time of one. Briefly holds the pickaxe for the background break. Grim's fast-break check cancels the background finish, so it does not work on 6b6t.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> hideMining = sgGeneral.add(new BoolSetting.Builder()
        .name("hide-mining")
        .description("Cancels the mining right after starting it, so other players never see the block break or how far along it is. The server still remembers when it started, so the final break lands at the same time.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> placeSupport = sgGeneral.add(new BoolSetting.Builder()
        .name("support")
        .description("When the block under a gap is not obsidian, mines it and places obsidian there first, so a crystal fits in the gap.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> antiReplace = sgGeneral.add(new BoolSetting.Builder()
        .name("anti-replace")
        .description("When a surround gap breaks and no crystal goes into it, fills it at once with packed ice or glass from your inventory, so their surround can't put obsidian back. Re-mining obsidian takes the whole mine time again; ice breaks in one tick with a good pickaxe and is broken again when the crystal is ready.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> needCrystals = sgGeneral.add(new BoolSetting.Builder()
        .name("need-crystals")
        .description("Only mines while you have crystals to use the gap with, or anchors and glowstone while auto-anchor is on outside the nether.")
        .defaultValue(true)
        .build()
    );

    private final Setting<InventoryManager.SwitchMode> switchMode = sgGeneral.add(new EnumSetting.Builder<InventoryManager.SwitchMode>()
        .name("switch")
        .description("How the pickaxe is put in your hand for the final break.")
        .defaultValue(InventoryManager.SwitchMode.Silent)
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
        .description("Highlights the block being mined and fills it as it breaks.")
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

    private final Setting<Boolean> progressColor = sgRender.add(new BoolSetting.Builder()
        .name("progress-color")
        .description("Colors the mining box from red to green as the block nears breaking, instead of the fixed colors.")
        .defaultValue(true)
        .visible(render::get)
        .build()
    );

    private final Setting<SettingColor> sideColor = sgRender.add(new ColorSetting.Builder()
        .name("side-color")
        .description("Highlight side color.")
        .defaultValue(new SettingColor(110, 45, 200, 50))
        .visible(render::get)
        .build()
    );

    private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder()
        .name("line-color")
        .description("Highlight line color.")
        .defaultValue(new SettingColor(110, 45, 200, 220))
        .visible(render::get)
        .build()
    );

    private static final Color PROGRESS_START = new Color(230, 60, 60);
    private static final Color PROGRESS_END = new Color(80, 220, 90);

    private final DuckRender.Smooth smoothProgress = new DuckRender.Smooth();
    private final Color sideShade = new Color();
    private final Color lineShade = new Color();

    private final Long2LongOpenHashMap banned = new Long2LongOpenHashMap();

    private PlayerEntity target;
    private BlockPos current;
    private BlockPos currentSupport;
    private float currentScore;
    private long openSince;
    private long switchedAt;
    private long stopHeldSince;
    private long holdUntil;
    private long lostSince;
    private long worthlessSince;
    private int ticks;
    private int updatedTick = -1;
    private int touchedUpdates;
    private int otherTick = -1;
    private boolean pending;
    private boolean supportWallBroken;
    private long filledAt;
    private long crystalSeenAt;

    public AutoCity() {
        super(Coyduck.CATEGORY, "auto-city+", "Mines the target's surround or burrow and breaks it again whenever it is replaced.");
    }

    @Override
    public void onActivate() {
        Module meteor = Modules.get().get(meteordevelopment.meteorclient.systems.modules.combat.AutoCity.class);
        if (meteor != null && meteor.isActive()) {
            meteor.toggle();
            info("Disabled %s, it mines the same blocks.", meteor.title);
        }

        reset();
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
        target = null;
        drop();
        currentScore = 0;
        switchedAt = 0;
        lostSince = 0;
        updatedTick = -1;
        pending = false;
        banned.clear();
    }

    private void drop() {
        current = null;
        currentSupport = null;
        openSince = 0;
        stopHeldSince = 0;
        holdUntil = 0;
        worthlessSince = 0;
        lostSince = 0;
        supportWallBroken = false;
    }

    @Override
    public String getInfoString() {
        return target == null ? null : target.getName().getString();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        ticks++;
        touchedUpdates = 0;
        if (!banned.isEmpty()) {
            long now = Util.getMeasuringTimeMs();
            banned.long2LongEntrySet().removeIf(entry -> entry.getLongValue() <= now);
        }
        update();
    }

    @EventHandler
    private void onPacket(PacketApplyEvent.Post event) {
        if (target == null) return;

        BlockPos center = target.getBlockPos();
        boolean[] touched = {false};
        boolean relevant = switch (event.packet) {
            case BlockUpdateS2CPacket packet -> {
                touched[0] = ours(packet.getPos());
                yield packet.getPos().isWithinDistance(center, 3);
            }
            case ChunkDeltaUpdateS2CPacket packet -> {
                packet.visitUpdates((pos, state) -> touched[0] |= ours(pos));
                yield Targets.touches(packet, center, 3);
            }
            default -> false;
        };
        if (!relevant && !touched[0]) return;

        if (touched[0] && touchedUpdates < TOUCHED_UPDATES) {
            touchedUpdates++;
            update();
        } else if (updatedTick != ticks) {
            update();
        } else {
            pending = true;
        }
    }

    private boolean ours(BlockPos pos) {
        return pos.equals(current) || pos.equals(currentSupport);
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (pending) {
            pending = false;
            update();
        }
        if (!render.get() || current == null || !MiningManager.get().owns(this)) return;

        Box box = new Box(current);
        double progress = smoothProgress.toward(MiningManager.get().progress(), 12);
        Color side = sideColor.get();
        Color line = lineColor.get();
        if (progressColor.get()) {
            side = DuckRender.lerp(sideShade, PROGRESS_START.a(side.a), PROGRESS_END.a(side.a), progress);
            line = DuckRender.lerp(lineShade, PROGRESS_START.a(line.a), PROGRESS_END.a(line.a), progress);
        }

        if (shapeMode.get() != ShapeMode.Lines) drawBox(event, box.contract((1 - progress) * 0.5), side, line, ShapeMode.Sides);
        if (shapeMode.get() != ShapeMode.Sides) drawBox(event, box, side, line, ShapeMode.Lines);
    }

    private void drawBox(Render3DEvent event, Box box, Color side, Color line, ShapeMode mode) {
        if (progressColor.get()) DuckRender.exact(event.renderer, box, side, line, mode, 1);
        else DuckRender.box(event.renderer, box, side, line, mode, 1);
    }

    private void update() {
        updatedTick = ticks;
        pending = false;
        if (mc.player == null || mc.world == null || !mc.player.isAlive() || InventoryManager.get().isOffhandEating()) return;

        MiningManager mining = MiningManager.get();
        long time = Util.getMeasuringTimeMs();
        target = Targets.find(targetRange.get(), current != null ? target : null);

        boolean noAmmo = needCrystals.get() && !hasAmmo();
        if (target == null || target.isGliding() || noAmmo) {
            if (!noAmmo && graceful(time)) return;
            if (current != null) Trace.log("city idle {}", target == null ? "no target" : noAmmo ? "no ammo" : "target is gliding");
            mining.release(this);
            drop();
            return;
        }

        Box box = Targets.box(target);
        LongSet columns = Targets.columns(box);

        int validity = current == null ? VALID : validity(current, box, columns);
        if (validity == VALID) {
            lostSince = 0;
        } else if (validity == BROKEN_OFF || !graceful(time)) {
            Trace.log("city drop {} no longer valid", current);
            drop();
        }
        boolean open = current != null && (mc.world.getBlockState(current).isReplaceable() || webbed(current));
        if (!open) openSince = 0;
        else if (openSince == 0) openSince = time;

        List<Choice> choices = choose(box, columns);
        if (choices.isEmpty() && current == null && ticks % OTHER_TARGET_TICKS == 0 && otherTick != ticks) {
            otherTick = ticks;
            choices = otherTarget();
        }
        Choice best = choices.isEmpty() ? null : choices.getFirst();
        Choice now = find(choices, current);
        boolean solid = current != null && mining.owns(this) && mining.isMining(current) && !mining.wasBroken() && Targets.isMinable(current);
        if (now == null && solid && lostSince == 0) {
            float kept = openValue(current, box);
            if (kept >= minDamage.get()) now = new Choice(current, kept, currentSupport);
        }
        if (now != null) currentScore = now.score;

        boolean worthless = solid && now == null && lostSince == 0;
        if (!worthless) worthlessSince = 0;
        else if (worthlessSince == 0) worthlessSince = time;
        boolean expired = worthless && time - worthlessSince >= WORTHLESS_LIMIT;
        if (expired && best == null) {
            Trace.log("city drop {} no longer worth it, nothing better", current);
            mining.release(this);
            drop();
            return;
        }

        String reason = best == null ? null : current == null ? "new" : expired && !best.pos.equals(current) ? "current no longer worth it" : shouldSwitch(best, now);
        if (reason != null) {
            Trace.log("city target {} -> {} score {} -> {} {} target {}", current, best.pos, now == null ? null : now.score, best.score, reason, target.getName().getString());
            boolean had = current != null;
            drop();
            if (had) switchedAt = time;
            current = best.pos;
            currentScore = best.score;
            currentSupport = best.support;
        }

        if (current == null) {
            mining.release(this);
            return;
        }

        if (!rebreak.get() && mining.owns(this) && mining.isMining(current) && mining.wasBroken()) {
            mining.release(this);
            drop();
            return;
        }

        if (currentSupport != null && !prepareSupport(currentSupport)) return;

        if (doubleMine.get() && currentSupport == null && mining.background() == null && !mining.isMining(current)) {
            for (Choice choice : choices) {
                if (choice.pos.equals(current) || choice.support != null) continue;
                mining.mineTwo(this, PRIORITY, current, choice.pos, true, switchMode.get(), swing.get());
                return;
            }
        }

        mining.mine(this, PRIORITY, current, true, switchMode.get(), swing.get());
        fillOpenGap(time);
    }

    public static boolean isFiller(BlockState state) {
        return state.isOf(Blocks.PACKED_ICE) || state.isOf(Blocks.BLUE_ICE) || state.isOf(Blocks.GLASS) || state.isOf(Blocks.TINTED_GLASS) || state.getBlock() instanceof StainedGlassBlock;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    private void onMineStop(MineStopEvent event) {
        if (!antiReplace.get() || current == null || target == null || !event.pos.equals(current) || mc.player == null || mc.world == null) return;
        if (Util.getMeasuringTimeMs() - filledAt < FILL_GAP || !fillable(current)) return;
        BlockPos base = current.down();
        if (!InteractionManager.centerReach(base)) return;
        int slot = fillerSlot(current);
        if (slot == -1) return;
        BlockPos gap = current;
        BlockHitResult hit = new BlockHitResult(new Vec3d(base.getX() + 0.5, base.getY() + 1.0, base.getZ() + 0.5), Direction.UP, base, false);
        if (!RotationManager.get().lookNow(hit.getPos(), InteractionManager.DEFENSE_PRIORITY)) return;
        boolean placed = InventoryManager.get().withItem(slot, switchMode.get(), () -> InteractionManager.get().place(gap, hit, swing.get(), false, 0));
        if (!placed) return;
        filledAt = Util.getMeasuringTimeMs();
        Trace.log("city fills {} with {} on stop", gap, InventoryManager.get().stack(slot).getItem());
    }

    private void fillOpenGap(long time) {
        if (!antiReplace.get() || current == null || target == null) return;
        MiningManager mining = MiningManager.get();
        if (!mining.owns(this) || !mining.isMining(current) || !mining.wasBroken() || !mc.world.getBlockState(current).isAir()) return;
        if (openSince == 0 || time - Math.max(openSince, crystalSeenAt) < FILL_DELAY || time - filledAt < FILL_GAP) return;
        if (!fillable(current)) return;
        InteractionManager interaction = InteractionManager.get();
        if (!interaction.canPlace(current) || !interaction.obstructions(current).isEmpty()) return;
        BlockHitResult hit = interaction.hit(current, false);
        int slot = fillerSlot(current);
        if (hit == null || slot == -1) return;
        if (!interaction.aimed(hit.getPos(), new Box(hit.getBlockPos()), InteractionManager.TRAP_PRIORITY)) return;
        BlockPos gap = current;
        boolean placed = InventoryManager.get().withItem(slot, switchMode.get(), () -> interaction.place(gap, hit, swing.get(), false, 0));
        if (!placed) return;
        filledAt = time;
        Trace.log("city fills open {} with {}", gap, InventoryManager.get().stack(slot).getItem());
    }

    private boolean fillable(BlockPos gap) {
        if (currentScore >= BURROW_SCORE || gap.getY() != target.getBlockY()) return false;
        if (Targets.columns(Targets.box(target)).contains(gap.asLong())) return false;
        BlockState base = mc.world.getBlockState(gap.down());
        if (!base.isOf(Blocks.OBSIDIAN) && !base.isOf(Blocks.BEDROCK)) return false;
        boolean crystal = CrystalTracker.get().placingOverlaps(gap.down()) || !mc.world.getOtherEntities(null, new Box(gap), entity -> entity instanceof EndCrystalEntity && CrystalTracker.get().isAlive(entity)).isEmpty();
        if (crystal) {
            crystalSeenAt = Util.getMeasuringTimeMs();
            return false;
        }
        return true;
    }

    private int fillerSlot(BlockPos gap) {
        InventoryManager inventory = InventoryManager.get();
        MiningManager mining = MiningManager.get();
        int best = -1;
        float fastest = 1.0f / FILL_MAX_TICKS;
        for (int i = 0; i < InventoryManager.MAIN_END; i++) {
            if (!(inventory.stack(i).getItem() instanceof BlockItem item)) continue;
            BlockState state = item.getBlock().getDefaultState();
            if (!isFiller(state)) continue;
            int tool = mining.bestTool(state);
            float delta = mining.delta(state, gap, tool == -1 ? inventory.stack(inventory.serverSlot()) : inventory.stack(tool));
            if (delta > fastest) {
                fastest = delta;
                best = i;
            }
        }
        return best;
    }

    private boolean graceful(long now) {
        MiningManager mining = MiningManager.get();
        if (current == null || !mining.owns(this) || !mining.isMining(current) || mining.wasBroken()) return false;
        if (lostSince == 0) lostSince = now;
        return now - lostSince < LOST_GRACE;
    }

    private List<Choice> otherTarget() {
        PlayerEntity first = target;
        List<PlayerEntity> others = new ArrayList<>();
        for (PlayerEntity player : mc.world.getPlayers()) {
            if (player != first && Targets.valid(player, targetRange.get()) && !player.isGliding()) others.add(player);
        }
        others.sort(Comparator.comparingDouble(player -> player.squaredDistanceTo(mc.player)));

        for (int i = 0; i < others.size() && i < OTHER_TARGETS; i++) {
            target = others.get(i);
            Box box = Targets.box(target);
            List<Choice> choices = choose(box, Targets.columns(box));
            if (!choices.isEmpty()) {
                Trace.log("city skips {}, nothing to open, takes {}", first.getName().getString(), target.getName().getString());
                return choices;
            }
        }
        target = first;
        return List.of();
    }

    @Override
    public boolean hideMining() {
        return hideMining.get();
    }

    @Override
    public boolean allowStop(BlockPos pos) {
        boolean allow = stopAllowed(pos);
        long now = Util.getMeasuringTimeMs();
        if (!allow && stopHeldSince == 0) {
            stopHeldSince = now;
            Trace.log("city hold stop {} target hurt for {}ms", pos, target == null ? 0 : Math.max(0, HurtTracker.get().releaseTime(target) - now));
        } else if (allow && stopHeldSince != 0) {
            Trace.log("city release stop {} after {}ms", pos, now - stopHeldSince);
            stopHeldSince = 0;
        }
        if (allow) holdUntil = 0;
        return allow;
    }

    private boolean stopAllowed(BlockPos pos) {
        if (!pos.equals(current) || currentScore >= BURROW_SCORE) return true;
        if (target == null) return false;
        AutoCrystal autoCrystal = Modules.get().get(AutoCrystal.class);
        if (autoCrystal == null || !autoCrystal.isActive() || !InteractionManager.get().hasCrystals()) return true;
        if (hurtSync.get() && hurtHolds(pos)) return false;
        return worthOpening(pos);
    }

    private boolean hurtHolds(BlockPos pos) {
        long now = Util.getMeasuringTimeMs();
        if (holdUntil != 0 && now >= holdUntil + HOLD_STALE) holdUntil = 0;
        if (holdUntil != 0) return now < holdUntil;

        long release = HurtTracker.get().releaseTime(target);
        if (release <= now || cheapWindow(pos, now)) return false;
        holdUntil = release;
        return true;
    }

    private boolean cheapWindow(BlockPos pos, long now) {
        Box box = Targets.box(target);
        BlockPos base = roof(pos, box) ? pos : pos.down();
        float raw = ExplosionDamage.rawBound(target, Targets.feet(box), Targets.crystalCenter(base), ExplosionDamage.CRYSTAL_POWER);
        if (raw <= 0) return true;
        long arrival = now + LatencyManager.get().rtt() / 2;
        return HurtTracker.get().effective(target, raw, arrival) >= ExplosionDamage.mitigate(target, raw) - HOLD_TOLERANCE;
    }

    private boolean worthOpening(BlockPos pos) {
        if (!mc.world.getBlockState(pos).isReplaceable() && MiningManager.get().wasBroken()) return true;
        Box box = Targets.box(target);
        boolean roof = roof(pos, box);
        if (roof && !roofCrystal(pos)) {
            if (Trace.on() && stopHeldSince == 0) Trace.log("city waits on {}, no crystal on the roof yet", pos);
            return false;
        }
        float damage = roof ? cevDamage(pos, box, Targets.feet(box)) : gapValue(pos, box);
        if (damage >= minDamage.get() || damage >= target.getHealth() + target.getAbsorptionAmount()) return true;
        if (Trace.on() && stopHeldSince == 0) Trace.log("city waits on {}, the gap would only allow {} damage now", pos, damage);
        return false;
    }

    private boolean roof(BlockPos pos, Box box) {
        return cev.get() && Targets.columns(box).contains(pos.down(2).asLong());
    }

    private float openValue(BlockPos pos, Box box) {
        return roof(pos, box) ? cevDamage(pos, box, Targets.feet(box)) : gapValue(pos, box);
    }

    private float gapValue(BlockPos pos, Box box) {
        BlockPos support = currentSupport != null && !Targets.isBase(mc.world.getBlockState(currentSupport)) ? currentSupport : null;
        return gapDamage(pos, box, Targets.feet(box), support);
    }

    private String shouldSwitch(Choice best, Choice now) {
        if (best.pos.equals(current)) return null;

        MiningManager mining = MiningManager.get();
        if (!mining.owns(this)) return "not mining";
        boolean onCurrent = mining.isMining(current);
        if (!onCurrent && (currentSupport == null || !mining.isMining(currentSupport))) return "mining elsewhere";
        if (onCurrent && mining.wasBroken()) {
            if (currentScore >= BURROW_SCORE) return best.score > SWITCH_GAIN ? "burrow open" : null;
            boolean unused = stuckOpen();
            if (best.score >= BURROW_SCORE) return unused || openValue(current, Targets.box(target)) < minDamage.get() ? "burrow" : null;
            return unused && best.score > SWITCH_GAIN ? "open gap unused" : null;
        }

        if (lostSince != 0) return null;
        long time = Util.getMeasuringTimeMs();
        if (best.score >= BURROW_SCORE && currentScore < BURROW_SCORE && (now == null || mining.progress() < BURROW_PREEMPT)) return "burrow";
        if (time - switchedAt < SWITCH_COOLDOWN) return null;
        float score = now == null ? 0 : now.score;
        return mining.progress() < SWITCH_PROGRESS && best.score > score + SWITCH_GAIN ? "better" : null;
    }

    private static Choice find(List<Choice> choices, BlockPos pos) {
        if (pos == null) return null;
        for (Choice choice : choices) {
            if (choice.pos.equals(pos)) return choice;
        }
        return null;
    }

    private boolean stuckOpen() {
        if (openSince == 0) return false;
        BlockState state = mc.world.getBlockState(current);
        if (state.isReplaceable() && !state.isAir() && !(state.getBlock() instanceof AbstractFireBlock)) return true;
        if (Util.getMeasuringTimeMs() - openSince < OPEN_LIMIT) return false;
        Box cell = new Box(current.getX(), current.getY(), current.getZ(), current.getX() + 1, current.getY() + 2, current.getZ() + 1);
        return mc.world.getOtherEntities(null, cell, entity -> entity instanceof EndCrystalEntity && CrystalTracker.get().isAlive(entity)).isEmpty();
    }

    private int validity(BlockPos pos, Box box, LongSet columns) {
        MiningManager mining = MiningManager.get();
        if (!mining.keepRange(pos) || Targets.ownRing().contains(pos) || ownFace().contains(pos) || mine(pos)) return BROKEN_OFF;

        boolean owned = mining.owns(this) && mining.isMining(pos);
        if (webbed(pos)) return rebreak.get() && owned && mining.wasBroken() ? VALID : BROKEN_OFF;
        if (!Targets.isMinable(pos) && !(rebreak.get() && owned)) return BROKEN_OFF;

        boolean partOfHole = columns.contains(pos.asLong()) || Targets.ring(columns, 0).contains(pos) || Targets.ring(columns, 1).contains(pos)
            || cev.get() && columns.contains(pos.down(2).asLong());
        if (partOfHole) return VALID;
        return owned && !mining.wasBroken() && Targets.isMinable(pos) && gapDamage(pos, box, Targets.feet(box), null) >= minDamage.get() ? VALID : LEFT_HOLE;
    }

    private boolean hasAmmo() {
        if (InteractionManager.get().hasCrystals()) return true;

        AutoAnchor anchor = Modules.get().get(AutoAnchor.class);
        if (anchor == null || !anchor.isActive() || anchor.anchorsWork()) return false;
        InventoryManager inventory = InventoryManager.get();
        return inventory.find(stack -> stack.isOf(Items.RESPAWN_ANCHOR), 0, InventoryManager.MAIN_END) != -1
            && inventory.find(stack -> stack.isOf(Items.GLOWSTONE), 0, InventoryManager.MAIN_END) != -1;
    }

    private boolean mine(BlockPos pos) {
        return mc.player.getBoundingBox().intersects(new Box(pos));
    }

    private boolean webbed(BlockPos pos) {
        return mc.world.getBlockState(pos).isOf(Blocks.COBWEB);
    }

    private List<BlockPos> ownFace() {
        return Targets.ring(Targets.columns(mc.player.getBoundingBox()), 1);
    }

    private boolean candidate(BlockPos pos, List<BlockPos> own, List<BlockPos> face, BlockPos background, MiningManager mining) {
        return !pos.equals(background) && !banned.containsKey(pos.asLong()) && Targets.isMinable(pos) && !webbed(pos)
            && !own.contains(pos) && !face.contains(pos) && !mine(pos) && mining.inRange(pos);
    }

    private float bestDelta(BlockState state, BlockPos pos) {
        MiningManager mining = MiningManager.get();
        float best = mining.groundDelta(state, pos, mc.player.getMainHandStack(), true);
        for (int i = 0; i < InventoryManager.MAIN_END; i++) best = Math.max(best, mining.groundDelta(state, pos, InventoryManager.get().stack(i), true));
        return best;
    }

    private float speedBonus(BlockPos pos, float obsidian) {
        if (obsidian <= 0) return 0;
        float delta = bestDelta(mc.world.getBlockState(pos), pos);
        if (delta <= obsidian) return 0;
        return SPEED_BONUS * (1 - obsidian / delta);
    }

    private record Choice(BlockPos pos, float score, BlockPos support) {}

    private List<Choice> choose(Box box, LongSet columns) {
        List<Choice> choices = new ArrayList<>();
        MiningManager mining = MiningManager.get();
        BlockPos background = mining.background();

        List<BlockPos> own = Targets.ownRing();
        List<BlockPos> face = ownFace();
        if (burrow.get()) {
            for (long key : columns) {
                BlockPos pos = BlockPos.fromLong(key);
                if (!candidate(pos, own, face, background, mining) || !Targets.isBurrowedIn(box, pos)) continue;
                BlockState state = mc.world.getBlockState(pos);
                if (state.isOf(Blocks.BEDROCK)) continue;

                choices.add(new Choice(pos, BURROW_SCORE + bestDelta(state, pos), null));
            }
        }

        Vec3d feet = Targets.feet(box);
        float obsidian = -1;
        for (BlockPos pos : Targets.ring(columns, 0)) {
            if (!candidate(pos, own, face, background, mining)) continue;

            BlockPos support = null;
            float damage = gapDamage(pos, box, feet, null);
            boolean noFloor = !Targets.isBase(mc.world.getBlockState(pos.down()));
            if ((noFloor || damage < minDamage.get()) && supportable(pos.down(), own)) {
                support = pos.down();
                damage = Math.max(noFloor ? damage : 0, gapDamage(pos, box, feet, support) - SUPPORT_PENALTY);
            }
            if (damage < minDamage.get()) continue;

            if (obsidian < 0) obsidian = bestDelta(Blocks.OBSIDIAN.getDefaultState(), pos);
            choices.add(new Choice(pos, damage + speedBonus(pos, obsidian), support));
        }

        for (BlockPos pos : Targets.ring(columns, 1)) {
            if (!candidate(pos, own, face, background, mining) || mc.world.getBlockState(pos).isOf(Blocks.BEDROCK)) continue;
            float damage = gapDamage(pos, box, feet, null);
            if (damage < minDamage.get()) continue;
            if (obsidian < 0) obsidian = bestDelta(Blocks.OBSIDIAN.getDefaultState(), pos);
            choices.add(new Choice(pos, damage - FACE_PENALTY + speedBonus(pos, obsidian), null));
        }

        if (cev.get()) {
            for (long key : columns) {
                BlockPos pos = BlockPos.fromLong(key).up(2);
                if (!candidate(pos, own, face, background, mining)) continue;
                if (!mc.world.getBlockState(pos).isOf(Blocks.OBSIDIAN) || !mc.world.getBlockState(pos.up()).isAir() || roofBlocked(pos)) continue;
                float damage = cevDamage(pos, box, feet);
                if (damage < minDamage.get()) continue;
                choices.add(new Choice(pos, damage - FACE_PENALTY, null));
            }
        }

        choices.sort((a, b) -> Float.compare(b.score, a.score));
        return choices;
    }

    private boolean roofCrystal(BlockPos pos) {
        return !mc.world.getOtherEntities(null, new Box(pos.up()), entity -> entity instanceof EndCrystalEntity crystal
            && crystal.getBlockPos().down().equals(pos) && CrystalTracker.get().isAlive(crystal)).isEmpty();
    }

    private boolean roofBlocked(BlockPos pos) {
        BlockPos above = pos.up();
        Box space = new Box(above.getX(), above.getY(), above.getZ(), above.getX() + 1, above.getY() + 2, above.getZ() + 1);
        return !mc.world.getOtherEntities(null, space, entity -> !(entity instanceof EndCrystalEntity) && !entity.isSpectator() && entity.isAlive()
            && !(entity instanceof ItemEntity) && !(entity instanceof ExperienceOrbEntity)).isEmpty();
    }

    private float cevDamage(BlockPos block, Box box, Vec3d feet) {
        AutoCrystal autoCrystal = Modules.get().get(AutoCrystal.class);
        double range = autoCrystal != null && autoCrystal.isActive() ? autoCrystal.placeRange() : 5.5;
        if (new Box(block).squaredMagnitude(mc.player.getEyePos()) > range * range) return 0;
        BlockOverlay view = new BlockOverlay(mc.world).set(block, Blocks.AIR.getDefaultState());
        Vec3d crystal = Targets.crystalCenter(block);
        if (!Targets.safe(crystal, view, maxSelfDamage.get())) return 0;
        return ExplosionDamage.crystal(target, feet, box, crystal, view);
    }

    private boolean supportable(BlockPos support, List<BlockPos> own) {
        if (!placeSupport.get() || own.contains(support)) return false;
        double reach = mc.player.getBlockInteractionRange() - 0.05;
        if (new Box(support).squaredMagnitude(mc.player.getEyePos()) >= reach * reach) return false;
        if (InventoryManager.get().find(stack -> stack.isOf(Items.OBSIDIAN), 0, InventoryManager.MAIN_END) == -1) return false;

        BlockState state = mc.world.getBlockState(support);
        if (Targets.isBase(state)) return false;
        if (state.isReplaceable()) return true;
        return state.getHardness(mc.world, support) >= 0 && MiningManager.get().inRange(support);
    }

    private boolean prepareSupport(BlockPos support) {
        BlockState state = mc.world.getBlockState(support);
        if (Targets.isBase(state)) return true;

        MiningManager mining = MiningManager.get();
        boolean wallOpen = mc.world.getBlockState(current).isReplaceable();
        if (!state.isReplaceable()) {
            if (wallOpen) {
                supportWallBroken = true;
            } else if (supportWallBroken) {
                Trace.log("city {} was refilled before its support {} could be mined, skips it", current, support);
                banned.put(current.asLong(), Util.getMeasuringTimeMs() + REFILL_BAN);
                drop();
                return false;
            }
            BlockPos next = MiningManager.exposed(support) || wallOpen ? support : current;
            mining.mine(this, PRIORITY, next, true, switchMode.get(), swing.get());
            return false;
        }

        if (!wallOpen && !mining.mine(this, PRIORITY, current, true, switchMode.get(), swing.get())) return false;

        InteractionManager interaction = InteractionManager.get();
        int slot = InventoryManager.get().find(stack -> stack.isOf(Items.OBSIDIAN), 0, InventoryManager.MAIN_END);
        BlockHitResult hit = slot == -1 || !interaction.canPlace(support) ? null : interaction.hit(support, true);
        if (hit == null || !interaction.aimed(hit.getPos(), new Box(hit.getBlockPos()), InteractionManager.TRAP_PRIORITY)) return false;

        InventoryManager.get().withItem(slot, switchMode.get(), () -> interaction.place(support, hit, swing.get(), true, InteractionManager.TRAP_PRIORITY));
        return Targets.isBase(mc.world.getBlockState(support));
    }

    private float gapDamage(BlockPos gap, Box box, Vec3d feet, BlockPos support) {
        BlockOverlay view = new BlockOverlay(mc.world).set(gap, Blocks.AIR.getDefaultState());
        if (support != null) view.set(support, Blocks.OBSIDIAN.getDefaultState());
        AutoCrystal autoCrystal = Modules.get().get(AutoCrystal.class);
        double range = autoCrystal != null && autoCrystal.isActive() ? autoCrystal.placeRange() : 5.5;
        Vec3d eye = mc.player.getEyePos();
        Vec3d[] crystals = new Vec3d[9];
        float[] bounds = new float[9];
        int count = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                BlockPos base = gap.add(dx, -1, dz);
                if (new Box(base).squaredMagnitude(eye) > range * range || !Targets.canHoldCrystal(view, base)) continue;

                Vec3d crystal = Targets.crystalCenter(base);
                float bound = ExplosionDamage.upperBound(target, feet, crystal);
                int at = count++;
                while (at > 0 && bounds[at - 1] < bound) {
                    bounds[at] = bounds[at - 1];
                    crystals[at] = crystals[at - 1];
                    at--;
                }
                bounds[at] = bound;
                crystals[at] = crystal;
            }
        }

        float best = 0;
        for (int i = 0; i < count && bounds[i] > best; i++) {
            if (!Targets.safe(crystals[i], view, maxSelfDamage.get())) continue;
            best = Math.max(best, ExplosionDamage.crystal(target, feet, box, crystals[i], view));
        }
        return best;
    }
}
