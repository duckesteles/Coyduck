package dev.coyduck.modules;

import dev.coyduck.Coyduck;
import dev.coyduck.events.PacketApplyEvent;
import dev.coyduck.managers.InteractionManager;
import dev.coyduck.managers.CrystalTracker;
import dev.coyduck.managers.HurtTracker;
import dev.coyduck.managers.InventoryManager;
import dev.coyduck.managers.MiningManager;
import dev.coyduck.utils.DuckRender;
import dev.coyduck.utils.BlockOverlay;
import dev.coyduck.utils.ExplosionDamage;
import dev.coyduck.utils.Targets;
import dev.coyduck.utils.Trace;
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
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.util.Util;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Items;
import net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ChunkDeltaUpdateS2CPacket;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.List;

public class AutoCity extends Module implements MiningManager.StopGate {
    public static final int PRIORITY = 50;

    private static final float BURROW_SCORE = 1000;
    private static final float SWITCH_GAIN = 4;
    private static final float SWITCH_PROGRESS = 0.3f;
    private static final long OPEN_LIMIT = 1500;
    private static final long SWITCH_COOLDOWN = 1000;
    private static final float FACE_PENALTY = 1.5f;
    private static final float SUPPORT_PENALTY = 1;

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

    private final Setting<Boolean> placeSupport = sgGeneral.add(new BoolSetting.Builder()
        .name("support")
        .description("When the block under a gap is not obsidian, mines it and places obsidian there first, so a crystal fits in the gap.")
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

    private PlayerEntity target;
    private BlockPos current;
    private BlockPos currentSupport;
    private float currentScore;
    private long openSince;
    private long switchedAt;
    private long stopHeldSince;

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

        target = null;
        current = null;
    }

    @Override
    public void onDeactivate() {
        MiningManager.get().release(this);
        target = null;
        current = null;
    }

    @Override
    public String getInfoString() {
        return target == null ? null : target.getName().getString();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        update();
    }

    @EventHandler
    private void onPacket(PacketApplyEvent.Post event) {
        if (target == null) return;

        boolean relevant = switch (event.packet) {
            case BlockUpdateS2CPacket packet -> packet.getPos().isWithinDistance(target.getBlockPos(), 3);
            case ChunkDeltaUpdateS2CPacket packet -> Targets.touches(packet, target.getBlockPos(), 3);
            default -> false;
        };

        if (relevant) update();
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
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
        if (InventoryManager.get().isOffhandEating()) return;
        if (mc.player == null || mc.world == null || !mc.player.isAlive()) return;

        MiningManager mining = MiningManager.get();
        target = Targets.find(targetRange.get(), current != null ? target : null);

        if (target == null || target.isGliding() || (needCrystals.get() && !hasAmmo())) {
            if (current != null) Trace.log("city idle {}", target == null ? "no target" : target.isGliding() ? "target is gliding" : "no ammo");
            mining.release(this);
            current = null;
            return;
        }

        Box box = Targets.box(target);
        LongSet columns = Targets.columns(box);

        if (current != null && !stillValid(current, columns)) {
            Trace.log("city drop {} no longer valid", current);
            current = null;
        }
        if (current == null || !mc.world.getBlockState(current).isReplaceable()) openSince = 0;
        else if (openSince == 0) openSince = Util.getMeasuringTimeMs();

        List<Choice> choices = choose(box, columns);
        Choice best = choices.isEmpty() ? null : choices.getFirst();
        Choice now = find(choices, current);
        if (now != null) currentScore = now.score;
        String reason = best == null ? null : current == null ? "new" : shouldSwitch(best, now);
        if (reason != null) {
            Trace.log("city target {} -> {} score {} -> {} {} target {}", current, best.pos, now == null ? null : now.score, best.score, reason, target.getName().getString());
            if (current != null) switchedAt = Util.getMeasuringTimeMs();
            stopHeldSince = 0;
            current = best.pos;
            openSince = 0;
            currentScore = best.score;
            currentSupport = best.support;
        }

        if (current == null) {
            mining.release(this);
            return;
        }

        if (!rebreak.get() && mining.owns(this) && mining.isMining(current) && mining.wasBroken()) {
            mining.release(this);
            current = null;
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
    }

    @Override
    public boolean allowStop(BlockPos pos) {
        boolean allow = stopAllowed(pos);
        long now = Util.getMeasuringTimeMs();
        if (!allow && stopHeldSince == 0) {
            stopHeldSince = now;
            Trace.log("city hold stop {} target hurt for {}ms", pos, Math.max(0, HurtTracker.get().releaseTime(target) - now));
        } else if (allow && stopHeldSince != 0) {
            Trace.log("city release stop {} after {}ms", pos, now - stopHeldSince);
            stopHeldSince = 0;
        }
        return allow;
    }

    private boolean stopAllowed(BlockPos pos) {
        if (target == null || !pos.equals(current) || currentScore >= BURROW_SCORE) return true;
        AutoCrystal autoCrystal = Modules.get().get(AutoCrystal.class);
        if (autoCrystal == null || !autoCrystal.isActive() || !InteractionManager.get().hasCrystals()) return true;
        if (!worthOpening(pos)) return false;
        return !hurtSync.get() || HurtTracker.get().releaseTime(target) <= Util.getMeasuringTimeMs();
    }

    private boolean worthOpening(BlockPos pos) {
        if (!mc.world.getBlockState(pos).isReplaceable() && MiningManager.get().wasBroken()) return true;
        Box box = Targets.box(target);
        BlockPos support = currentSupport != null && !Targets.isBase(mc.world.getBlockState(currentSupport)) ? currentSupport : null;
        float damage = gapDamage(pos, box, Targets.feet(box), support);
        if (damage >= minDamage.get() || damage >= target.getHealth() + target.getAbsorptionAmount()) return true;
        if (Trace.on() && stopHeldSince == 0) Trace.log("city waits on {}, the gap would only allow {} damage now", pos, damage);
        return false;
    }

    private String shouldSwitch(Choice best, Choice now) {
        if (best.pos.equals(current)) return null;

        MiningManager mining = MiningManager.get();
        if (!mining.owns(this)) return "not mining";
        boolean onCurrent = mining.isMining(current);
        if (!onCurrent && (currentSupport == null || !mining.isMining(currentSupport))) return "mining elsewhere";
        if (onCurrent && mining.wasBroken()) {
            if (currentScore >= BURROW_SCORE) return best.score > SWITCH_GAIN ? "burrow open" : null;
            if (best.score >= BURROW_SCORE) return "burrow";
            return stuckOpen() && best.score > SWITCH_GAIN ? "open gap unused" : null;
        }
        if (best.score >= BURROW_SCORE && currentScore < BURROW_SCORE) return "burrow";
        if (Util.getMeasuringTimeMs() - switchedAt < SWITCH_COOLDOWN) return null;
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
        if (openSince == 0 || Util.getMeasuringTimeMs() - openSince < OPEN_LIMIT) return false;
        Box cell = new Box(current.getX(), current.getY(), current.getZ(), current.getX() + 1, current.getY() + 2, current.getZ() + 1);
        return mc.world.getOtherEntities(null, cell, entity -> entity instanceof EndCrystalEntity && CrystalTracker.get().isAlive(entity)).isEmpty();
    }

    private boolean stillValid(BlockPos pos, LongSet columns) {
        if (!MiningManager.get().inRange(pos) || Targets.ownRing().contains(pos) || mine(pos)) return false;

        boolean partOfHole = columns.contains(pos.asLong()) || Targets.ring(columns, 0).contains(pos) || Targets.ring(columns, 1).contains(pos);
        if (!partOfHole) return false;

        if (webbed(pos)) return false;
        if (Targets.isMinable(pos)) return true;
        return rebreak.get() && MiningManager.get().owns(this) && MiningManager.get().isMining(pos);
    }

    private boolean hasAmmo() {
        if (InteractionManager.get().hasCrystals()) return true;

        AutoAnchor anchor = Modules.get().get(AutoAnchor.class);
        if (anchor == null || !anchor.isActive() || mc.world.getRegistryKey() == World.NETHER) return false;
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

    private record Choice(BlockPos pos, float score, BlockPos support) {}

    private List<Choice> choose(Box box, LongSet columns) {
        List<Choice> choices = new ArrayList<>();
        MiningManager mining = MiningManager.get();
        BlockPos background = mining.background();

        List<BlockPos> own = Targets.ownRing();
        if (burrow.get()) {
            for (long key : columns) {
                BlockPos pos = BlockPos.fromLong(key);
                if (pos.equals(background) || !Targets.isMinable(pos) || webbed(pos) || own.contains(pos) || mine(pos) || !mining.inRange(pos) || !Targets.isBurrowedIn(box, pos)) continue;
                if (mc.world.getBlockState(pos).isOf(Blocks.BEDROCK)) continue;

                choices.add(new Choice(pos, BURROW_SCORE + mining.delta(mc.world.getBlockState(pos), pos, mc.player.getMainHandStack()), null));
            }
        }

        Vec3d feet = Targets.feet(box);
        List<BlockPos> ring = Targets.ring(columns, 0);

        for (BlockPos pos : ring) {
            if (pos.equals(background) || !Targets.isMinable(pos) || webbed(pos) || !mining.inRange(pos) || own.contains(pos) || mine(pos)) continue;

            BlockPos support = null;
            float damage = gapDamage(pos, box, feet, null);
            boolean noFloor = !Targets.isBase(mc.world.getBlockState(pos.down()));
            if ((noFloor || damage < minDamage.get()) && supportable(pos.down(), own)) {
                support = pos.down();
                damage = Math.max(noFloor ? damage : 0, gapDamage(pos, box, feet, support) - SUPPORT_PENALTY);
            }
            if (damage < minDamage.get()) continue;

            choices.add(new Choice(pos, damage, support));
        }

        for (BlockPos pos : Targets.ring(columns, 1)) {
            if (pos.equals(background) || !Targets.isMinable(pos) || webbed(pos) || !mining.inRange(pos) || own.contains(pos) || mine(pos)) continue;
            if (mc.world.getBlockState(pos).isOf(Blocks.BEDROCK)) continue;
            float damage = gapDamage(pos, box, feet, null);
            if (damage < minDamage.get()) continue;
            choices.add(new Choice(pos, damage - FACE_PENALTY, null));
        }

        choices.sort((a, b) -> Float.compare(b.score, a.score));
        return choices;
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
        if (!state.isReplaceable()) {
            BlockPos target = MiningManager.exposed(support) || mc.world.getBlockState(current).isReplaceable() ? support : current;
            mining.mine(this, PRIORITY, target, true, switchMode.get(), swing.get());
            return false;
        }

        if (!mc.world.getBlockState(current).isReplaceable() && !mining.mine(this, PRIORITY, current, true, switchMode.get(), swing.get())) return false;

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
        float best = 0;

        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                BlockPos base = gap.add(dx, -1, dz);
                if (!Targets.canHoldCrystal(view, base)) continue;
                if (new Box(base).squaredMagnitude(mc.player.getEyePos()) > range * range) continue;

                Vec3d crystal = Targets.crystalCenter(base);
                if (!Targets.safe(crystal, view, maxSelfDamage.get())) continue;

                float damage = ExplosionDamage.crystal(target, feet, box, crystal, view);
                if (damage > best) best = damage;
            }
        }

        return best;
    }
}
