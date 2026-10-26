package dev.coyduck.modules;

import dev.coyduck.Coyduck;
import dev.coyduck.events.PacketApplyEvent;
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
import dev.coyduck.utils.ExplosionDamage;
import dev.coyduck.utils.PacketBatch;
import dev.coyduck.utils.Targets;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
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
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.combat.AnchorAura;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.RespawnAnchorBlock;
import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
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
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import net.minecraft.world.dimension.DimensionTypes;

import java.util.ArrayList;
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
    private static final float DEFENSE_THREAT = 4.0f;
    private static final float DEFENSE_FREE = 0.5f;
    private static final double RAY_SLACK = 2.0;
    private static final long RENDER_TIME = 500;
    private static final float FIRE_MARGIN = 1.0f;

    private record Target(PlayerEntity player, Box box, Vec3d feet, float health, float minDamage) {}

    private record Choice(BlockPos pos, Target target, float damage, float raw, float self, float score, boolean charged) {
        boolean beats(Choice other) {
            if (other == null) return true;
            if (charged != other.charged && Math.abs(score - other.score) <= 1.0f) return charged;
            if (Math.abs(score - other.score) > 0.5f) return score > other.score;
            return self < other.self;
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
        .description("Range from your eyes to the block an anchor is placed in. The server allows up to 5.5.")
        .defaultValue(5)
        .range(1, 5.5)
        .sliderRange(1, 5.5)
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
        .description("Places anchors without a neighbouring block to click on.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> hurtSync = sgGeneral.add(new BoolSetting.Builder()
        .name("hurt-sync")
        .description("Holds the anchor until the target's damage immunity ends, so no anchor is spent on a hit that deals nothing.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> predict = sgGeneral.add(new BoolSetting.Builder()
        .name("predict")
        .description("Aims at where targets will be when your packets reach the server.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> defend = sgGeneral.add(new BoolSetting.Builder()
        .name("defend")
        .description("Sets off a charged anchor near you while your own damage immunity makes the blast almost harmless, so it is wasted instead of hitting you later.")
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
        .description("Pauses while you eat. When off, anchors can still be detonated, but your inventory is never touched while you eat, since that cuts the eating.")
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
        .description("Shows in the module list why the last anchor was or was not used.")
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
        .description("Most damage an anchor may deal to you. An anchor that would pop or kill you is never used.")
        .defaultValue(8)
        .range(0, 36)
        .sliderRange(0, 36)
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

    private final Setting<Boolean> rotate = sgRotate.add(new BoolSetting.Builder()
        .name("rotate")
        .description("Looks at every anchor before using it. Needed on servers with Grim.")
        .defaultValue(false)
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
    private boolean running;
    private boolean spent;
    private long releaseAt;
    private String status;
    private boolean rejectedSelf;
    private boolean rejectedFriend;
    private List<Vec3d> ownCrystals;
    private PlayerEntity focus;
    private BlockPos renderPos;
    private long renderTime;

    public AutoAnchor() {
        super(Coyduck.CATEGORY, "auto-anchor", "Places, charges and sets off respawn anchors in one go, where crystals cannot reach.");
    }

    @Override
    public void onActivate() {
        Module meteor = Modules.get().get(AnchorAura.class);
        if (meteor != null && meteor.isActive()) {
            meteor.toggle();
            info("Disabled %s, it uses the same anchors.", meteor.title);
        }

        used.clear();
        spent = false;
        releaseAt = 0;
        status = null;
        focus = null;
        renderPos = null;
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
        long now = Util.getMeasuringTimeMs();
        used.values().removeIf(until -> now >= until);
        if (focus != null && !Targets.valid(focus, targetRange.get())) focus = null;
        pass();
    }

    @EventHandler
    private void onPacket(PacketApplyEvent.Post event) {
        if (mc.player == null || mc.world == null) return;

        boolean relevant = switch (event.packet) {
            case BlockUpdateS2CPacket packet -> matters(packet.getState()) && near(Vec3d.ofCenter(packet.getPos()));
            case ChunkDeltaUpdateS2CPacket packet -> {
                boolean[] found = {false};
                packet.visitUpdates((pos, state) -> found[0] |= matters(state) && near(Vec3d.ofCenter(pos)));
                yield found[0];
            }
            case ExplosionS2CPacket packet -> near(packet.center());
            default -> false;
        };
        if (relevant) pass();
    }

    @EventHandler
    private void onWorldReset(WorldResetEvent event) {
        used.clear();
        releaseAt = 0;
        focus = null;
        renderPos = null;
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

    private static boolean matters(BlockState state) {
        return state.isReplaceable() || state.isOf(Blocks.RESPAWN_ANCHOR);
    }

    private boolean near(Vec3d pos) {
        double range = placeRange.get() + RANGE;
        return mc.player.getEyePos().squaredDistanceTo(pos) <= range * range;
    }

    private void pass() {
        if (running || !canAct() || spent) return;

        running = true;
        try {
            run();
        } finally {
            running = false;
        }
    }

    private void run() {
        if (anchorsWork()) {
            status = "anchors set spawn here";
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
        if (pauseEating.get() && mc.player.isUsingItem()) {
            status = "eating";
            return;
        }

        List<Target> targets = targets();
        float health = AutoTotem.popInFlight() ? 1.0f + FIRE_MARGIN : mc.player.getHealth() + mc.player.getAbsorptionAmount();
        ownCrystals = ownCrystals();
        rejectedSelf = false;
        rejectedFriend = false;
        if (defend.get() && defend(targets, health)) return;

        if (targets.isEmpty()) {
            status = null;
            return;
        }

        long now = Util.getMeasuringTimeMs();
        long arrival = now + LatencyManager.get().rtt() / 2;
        rejectedSelf = false;
        rejectedFriend = false;
        Choice best = null;
        Choice held = null;
        long release = 0;

        int anchorSlot = slot(Items.RESPAWN_ANCHOR);
        int glowSlot = slot(Items.GLOWSTONE);

        List<BlockPos> charged = chargedAnchors();
        List<BlockPos> fresh = glowSlot != -1 && !InventoryManager.get().isEating() ? candidates(targets, anchorSlot != -1) : List.of();
        long timing = hurtSync.get() ? arrival : 0;

        for (BlockPos pos : charged) {
            Choice choice = evaluate(targets, pos, health, timing, overlay(pos), true);
            if (choice != null && choice.beats(best)) best = choice;
        }
        for (BlockPos pos : fresh) {
            Choice choice = evaluate(targets, pos, health, timing, overlay(pos), false);
            if (choice != null && choice.beats(best)) best = choice;
        }

        if (best == null && hurtSync.get() && targets.stream().anyMatch(target -> HurtTracker.get().releaseTime(target.player) > now)) {
            List<BlockPos> all = new ArrayList<>(charged);
            all.addAll(fresh);
            for (BlockPos pos : all) {
                Choice ready = evaluate(targets, pos, health, 0, overlay(pos), charged.contains(pos));
                if (ready == null) continue;
                if (ready.beats(held)) held = ready;

                long time = HurtTracker.get().releaseTime(ready.target.player);
                if (time > 0 && (release == 0 || time < release)) release = time;
            }
        }

        if (best == null) {
            if (held != null) {
                status = "hurt-sync";
                if (release > 0 && (releaseAt == 0 || release < releaseAt)) releaseAt = release;
            } else if (charged.isEmpty() && InventoryManager.get().isEating()) {
                status = "eating";
            } else if (charged.isEmpty() && glowSlot == -1) {
                status = anchorSlot == -1 ? "no anchors" : "no glowstone";
            } else if (charged.isEmpty() && fresh.isEmpty()) {
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

    private boolean defend(List<Target> targets, float health) {
        long arrival = Util.getMeasuringTimeMs() + LatencyManager.get().rtt() / 2;

        for (BlockPos pos : chargedAnchors()) {
            Vec3d center = Vec3d.ofCenter(pos);
            BlockOverlay view = new BlockOverlay(mc.world).set(pos, Blocks.AIR.getDefaultState());

            float raw = ExplosionDamage.raw(mc.player, mc.player.getEntityPos(), mc.player.getBoundingBox(), center, POWER, view);
            float full = ExplosionDamage.mitigate(mc.player, raw);
            if (full < DEFENSE_THREAT || full >= health) continue;
            if (HurtTracker.get().effectiveOnSelf(raw, arrival) > DEFENSE_FREE || hurtsFriend(center, view)) continue;
            if (destroysOwnCrystals(center) || evaluate(targets, pos, health, 0, view, true) != null) continue;

            if (!rotationReady(pos)) {
                status = "rotating";
                return true;
            }
            if (!click(pos)) return false;

            finish(pos, targets);
            status = "defend";
            return true;
        }
        return false;
    }

    private void detonate(Choice choice, List<Target> targets) {
        if (!rotationReady(choice.pos)) {
            status = "rotating";
            return;
        }
        if (!click(choice.pos)) {
            used.put(choice.pos.asLong(), Util.getMeasuringTimeMs() + LatencyManager.get().timeout());
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
        if (!rotationReady(pos)) {
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
            if (mc.world.getBlockState(pos).isOf(Blocks.RESPAWN_ANCHOR)) used.put(pos.asLong(), Util.getMeasuringTimeMs() + LatencyManager.get().timeout());
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

        if (!mc.player.getMainHandStack().isOf(Items.GLOWSTONE)) {
            PacketBatch.run(action);
            return true;
        }

        int other = InventoryManager.get().find(stack -> !stack.isOf(Items.GLOWSTONE), 0, InventoryManager.HOTBAR_END);
        return other != -1 && withItem(other, action);
    }

    private boolean withItem(int slot, Runnable action) {
        InventoryManager inventory = InventoryManager.get();
        return pauseEating.get() ? inventory.withItem(slot, switchMode.get(), action) : inventory.withItemWhileEating(slot, switchMode.get(), action);
    }

    private void finish(BlockPos pos, List<Target> targets) {
        Vec3d center = Vec3d.ofCenter(pos);
        BlockOverlay view = new BlockOverlay(mc.world).set(pos, Blocks.AIR.getDefaultState());
        used.put(pos.asLong(), Util.getMeasuringTimeMs() + LatencyManager.get().timeout());
        CrystalTracker.get().explodeAt(center, RANGE);
        for (Target target : targets) {
            float hit = ExplosionDamage.raw(target.player, target.feet, target.box, center, POWER, view);
            if (hit > 0) HurtTracker.get().expect(target.player, hit);
        }
        spent = true;
        renderPos = pos;
        renderTime = Util.getMeasuringTimeMs();
    }

    private void mark(Choice choice) {
        focus = choice.target.player;
        status = String.format("%.1f dmg", choice.damage);
    }

    private Choice evaluate(List<Target> targets, BlockPos pos, float health, long arrival, BlockView view, boolean charged) {
        Vec3d center = Vec3d.ofCenter(pos);

        boolean reachable = false;
        for (Target target : targets) {
            float bound = ExplosionDamage.upperBound(target.player, target.feet, center, POWER);
            if (bound >= target.minDamage || bound >= target.health) {
                reachable = true;
                break;
            }
        }
        if (!reachable) return null;

        float self = ExplosionDamage.explosion(mc.player, mc.player.getEntityPos(), mc.player.getBoundingBox(), center, POWER, view);
        if (self + FIRE_MARGIN >= health || self > maxSelfDamage.get()) {
            rejectedSelf = true;
            return null;
        }
        if (hurtsFriend(center, view)) {
            rejectedFriend = true;
            return null;
        }
        boolean costly = destroysOwnCrystals(center);

        Choice best = null;
        for (Target target : targets) {
            float bound = ExplosionDamage.upperBound(target.player, target.feet, center, POWER);
            if (bound < target.minDamage && bound < target.health) continue;

            float raw = ExplosionDamage.raw(target.player, target.feet, target.box, center, POWER, view);
            float damage = arrival != 0 ? HurtTracker.get().effective(target.player, raw, arrival) : ExplosionDamage.mitigate(target.player, raw);
            if (damage < target.minDamage && damage < target.health) continue;
            if (costly && damage < target.health) continue;

            float score = damage + (target.player == focus ? FOCUS_BONUS : 0) + (damage >= target.health ? LETHAL_BONUS : 0);
            Choice choice = new Choice(pos, target, damage, raw, self, score, charged);
            if (choice.beats(best)) best = choice;
        }
        return best;
    }

    private List<Vec3d> ownCrystals() {
        AutoCrystal autoCrystal = Modules.get().get(AutoCrystal.class);
        if (autoCrystal == null || !autoCrystal.isActive()) return null;

        CrystalTracker tracker = CrystalTracker.get();
        List<Vec3d> crystals = new ArrayList<>();
        for (Entity entity : mc.world.getEntities()) {
            if (entity instanceof EndCrystalEntity crystal && tracker.isOwn(crystal) && tracker.isAlive(crystal)) crystals.add(crystal.getEntityPos());
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

    private boolean hurtsFriend(Vec3d center, BlockView view) {
        double range = RANGE * RANGE;
        for (PlayerEntity player : mc.world.getPlayers()) {
            if (player == mc.player || !Friends.get().isFriend(player) || player.squaredDistanceTo(center) > range) continue;

            float damage = ExplosionDamage.explosion(player, player.getEntityPos(), player.getBoundingBox(), center, POWER, view);
            if (damage + FIRE_MARGIN >= player.getHealth() + player.getAbsorptionAmount() || damage > maxSelfDamage.get()) return true;
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
            targets.add(new Target(player, box, feet, health, min));
        }
        return targets;
    }

    private List<BlockPos> candidates(List<Target> targets, boolean place) {
        InteractionManager interaction = InteractionManager.get();
        LongSet seen = new LongOpenHashSet();
        LongSet own = ownCells();
        List<BlockPos> candidates = new ArrayList<>();

        for (Target target : targets) {
            BlockPos feet = BlockPos.ofFloored(target.feet);
            for (int dx = -SEARCH; dx <= SEARCH; dx++) {
                for (int dz = -SEARCH; dz <= SEARCH; dz++) {
                    for (int dy = -1; dy <= SEARCH_UP; dy++) {
                        BlockPos pos = feet.add(dx, dy, dz);
                        if (!seen.add(pos.asLong()) || own.contains(pos.asLong()) || used.containsKey(pos.asLong())) continue;

                        BlockState state = mc.world.getBlockState(pos);
                        if (state.isOf(Blocks.RESPAWN_ANCHOR)) {
                            if (charges(state) == 0 && inRange(pos)) candidates.add(pos);
                            continue;
                        }
                        if (!place || !interaction.canPlace(pos) || !inRange(pos) || MiningManager.get().isMining(pos)) continue;
                        if (CrystalTracker.get().isPlacing(pos.down()) || !interaction.obstructions(pos).isEmpty()) continue;
                        if (blocksTarget(targets, pos) || interaction.hit(pos, airplace.get()) == null) continue;
                        candidates.add(pos);
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

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos pos = eye.add(dx, dy, dz);
                    if (used.containsKey(pos.asLong()) || !inRange(pos)) continue;

                    BlockState state = mc.world.getBlockState(pos);
                    if (state.isOf(Blocks.RESPAWN_ANCHOR) && charges(state) > 0) anchors.add(pos);
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
        Vec3d center = Vec3d.ofCenter(pos);
        Vec3d offset = mc.player.getEyePos().subtract(center);
        Direction side = Direction.getFacing(offset.x, offset.y, offset.z);
        Vec3d point = center.add(side.getOffsetX() * 0.5, side.getOffsetY() * 0.5, side.getOffsetZ() * 0.5);
        return new BlockHitResult(point, side, pos, false);
    }

    private boolean rotationReady(BlockPos pos) {
        if (!rotate.get()) return true;

        RotationManager rotations = RotationManager.get();
        rotations.request(PRIORITY, Vec3d.ofCenter(pos), rotateSpeed.get().floatValue(), true);
        return rotations.isLookingAt(new Box(pos), placeRange.get() + RAY_SLACK);
    }

    private boolean inRange(BlockPos pos) {
        return new Box(pos).squaredMagnitude(mc.player.getEyePos()) <= placeRange.get() * placeRange.get();
    }

    private boolean anchorsWork() {
        return mc.world.getRegistryKey() == World.NETHER || mc.world.getDimensionEntry().matchesKey(DimensionTypes.THE_NETHER);
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
