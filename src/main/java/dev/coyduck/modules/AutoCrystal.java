package dev.coyduck.modules;

import dev.coyduck.Coyduck;
import dev.coyduck.events.PacketApplyEvent;
import dev.coyduck.managers.CrystalTracker;
import dev.coyduck.managers.HurtTracker;
import dev.coyduck.managers.InteractionManager;
import dev.coyduck.managers.InventoryManager;
import dev.coyduck.managers.LatencyManager;
import dev.coyduck.managers.MotionTracker;
import dev.coyduck.managers.RotationManager;
import dev.coyduck.utils.ExplosionDamage;
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
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PlayerInteractEntityC2SPacket;
import net.minecraft.network.packet.s2c.play.EntitiesDestroyS2CPacket;
import net.minecraft.network.packet.s2c.play.ExplosionS2CPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.Util;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

public class AutoCrystal extends Module {
    private static final int ROTATION_PRIORITY = 100;
    private static final long RENDER_TIME = 500;

    private record Target(PlayerEntity player, Box box, Vec3d feet, float health, float minDamage) {}

    private record Choice(Target target, float damage, float self) {
        boolean beats(Choice other) {
            if (other == null) return true;
            if (Math.abs(damage - other.damage) > 0.5f) return damage > other.damage;
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
        .description("Silent changes your held slot for each action. InvSwap swaps the item into your hand and back, so it never stops you eating.")
        .defaultValue(InventoryManager.SwitchMode.InvSwap)
        .build()
    );

    private final Setting<Integer> placesPerTick = sgGeneral.add(new IntSetting.Builder()
        .name("places-per-tick")
        .description("Most crystals placed in one tick.")
        .defaultValue(1)
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
        .description("Pauses placing while you eat. Only needed with Silent switch, InvSwap never interrupts eating.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Double> minDamage = sgDamage.add(new DoubleSetting.Builder()
        .name("min-damage")
        .description("Least damage a crystal must deal to the target.")
        .defaultValue(6)
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

    private final Setting<Double> facePlaceHealth = sgDamage.add(new DoubleSetting.Builder()
        .name("face-place-health")
        .description("Uses face place damage once the target's health is at or below this.")
        .defaultValue(8)
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
        .description("Attacks the entity ID a new crystal will most likely get, right after placing it, so it breaks in the same server tick at any ping. Attacking an ID that turns out to be a dropped item kicks you.")
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

    private int placeBudget;
    private int breakBudget;
    private boolean running;

    private PlayerEntity lastTarget;
    private BlockPos renderPos;
    private long renderTime;

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
        breakBudget = breaksPerTick.get();
        lastTarget = null;
        renderPos = null;
    }

    @Override
    public String getInfoString() {
        return lastTarget == null ? null : lastTarget.getName().getString();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        placeBudget = placesPerTick.get();
        breakBudget = breaksPerTick.get();
        pass();
    }

    @EventHandler
    private void onEntityAdded(EntityAddedEvent event) {
        if (event.entity instanceof EndCrystalEntity) pass();
    }

    @EventHandler
    private void onPacket(PacketApplyEvent.Post event) {
        if (event.packet instanceof ExplosionS2CPacket || event.packet instanceof EntitiesDestroyS2CPacket) pass();
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
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
            run();
        } finally {
            running = false;
        }
    }

    private void run() {
        if (pauseMining.get() && mc.interactionManager.isBreakingBlock()) return;

        List<Target> targets = targets();
        if (targets.isEmpty()) {
            lastTarget = null;
            return;
        }

        float health = mc.player.getHealth() + mc.player.getAbsorptionAmount();
        boolean holding = false;

        if (breakBudget > 0) holding = breakCrystals(targets, health);
        if (placeBudget <= 0 || holding) return;
        if (pauseEating.get() && mc.player.isUsingItem()) return;

        int crystalSlot = InventoryManager.get().find(stack -> stack.isOf(Items.END_CRYSTAL), 0, InventoryManager.MAIN_END);
        if (crystalSlot == -1) return;

        BlockPos spot = null;
        Choice spotChoice = null;
        for (BlockPos base : candidates()) {
            Choice choice = evaluate(targets, crystalCenter(base), health);
            if (choice != null && choice.beats(spotChoice)) {
                spot = base;
                spotChoice = choice;
            }
        }

        if (spot != null) {
            placeCrystal(spot, crystalSlot, spotChoice);
        } else if (basePlace.get() && !CrystalTracker.get().hasPlacing()) {
            placeBase(targets, health, crystalSlot);
        }
    }

    private boolean breakCrystals(List<Target> targets, float health) {
        CrystalTracker tracker = CrystalTracker.get();
        long arrival = Util.getMeasuringTimeMs() + LatencyManager.get().rtt() / 2;
        boolean held = false;
        boolean broke = false;

        while (breakBudget > 0) {
            EndCrystalEntity best = null;
            Choice bestChoice = null;

            for (Entity entity : mc.world.getEntities()) {
                if (!(entity instanceof EndCrystalEntity crystal) || !tracker.isAlive(crystal)) continue;
                if (crystal.getBoundingBox().squaredMagnitude(mc.player.getEyePos()) > breakRange.get() * breakRange.get()) continue;

                Choice choice = evaluate(targets, crystal.getEntityPos(), health);
                if (choice == null) continue;

                if (hurtSync.get()) {
                    float effective = HurtTracker.get().effective(choice.target.player, choice.damage, arrival);
                    if (effective < choice.target.minDamage && effective < choice.target.health) {
                        held = true;
                        continue;
                    }
                }

                if (choice.beats(bestChoice)) {
                    best = crystal;
                    bestChoice = choice;
                }
            }

            if (best == null) break;
            if (!rotationReady(best.getBoundingBox(), best.getBoundingBox().getCenter(), breakRange.get())) return true;
            if (!InteractionManager.get().attack(best, switchMode.get(), swing.get())) break;

            killAround(best.getEntityPos());
            HurtTracker.get().expect(bestChoice.target.player, bestChoice.damage);
            lastTarget = bestChoice.target.player;
            breakBudget--;
            broke = true;
        }

        return held && !broke;
    }

    private void killAround(Vec3d center) {
        CrystalTracker tracker = CrystalTracker.get();
        long duration = LatencyManager.get().timeout();
        double range = ExplosionDamage.CRYSTAL_RANGE * ExplosionDamage.CRYSTAL_RANGE;

        for (Entity entity : mc.world.getEntities()) {
            if (entity instanceof EndCrystalEntity && entity.getEntityPos().squaredDistanceTo(center) <= range) tracker.kill(entity, duration);
        }
    }

    private void placeCrystal(BlockPos base, int crystalSlot, Choice choice) {
        Box box = new Box(base);
        Vec3d aim = new Vec3d(base.getX() + 0.5, base.getY() + 1.0, base.getZ() + 0.5);
        if (!rotationReady(box, aim, placeRange.get() + 1)) return;

        InventoryManager.get().withItem(crystalSlot, switchMode.get(), () -> {
            mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, new BlockHitResult(aim, Direction.UP, base, false));
            InteractionManager.get().swing(swing.get());
            CrystalTracker.get().placed(base);
            if (idPredict.get()) predictAttack();
        });

        placeBudget--;
        lastTarget = choice.target.player;
        renderPos = base;
        renderTime = Util.getMeasuringTimeMs();
    }

    private void placeBase(List<Target> targets, float health, int crystalSlot) {
        int blockSlot = baseSlot();
        if (blockSlot == -1) return;

        InteractionManager interaction = InteractionManager.get();
        BlockPos best = null;
        BlockHitResult bestHit = null;
        Choice bestChoice = null;

        for (Target target : targets) {
            BlockPos feet = BlockPos.ofFloored(target.feet);

            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    for (int dy = -1; dy <= 0; dy++) {
                        BlockPos base = feet.add(dx, dy, dz);
                        if (!interaction.canPlace(base) || !mc.world.isAir(base.up())) continue;
                        if (!inPlaceRange(base) || !interaction.obstructions(base).isEmpty() || blocked(base)) continue;
                        if (target.box.intersects(new Box(base))) continue;

                        Choice choice = evaluate(targets, crystalCenter(base), health);
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

        if (best == null || !rotationReady(new Box(best), bestHit.getPos(), placeRange.get() + 1)) return;

        BlockPos base = best;
        BlockHitResult hit = bestHit;
        InventoryManager.get().withItem(blockSlot, switchMode.get(), () -> interaction.place(base, hit, swing.get()));

        if (!isBase(mc.world.getBlockState(base))) return;
        placeCrystal(base, crystalSlot, bestChoice);
    }

    private void predictAttack() {
        if (!InteractionManager.get().canHitWithHand()) return;

        int highest = CrystalTracker.get().highestId();
        for (int i = 1; i <= idPredictCount.get(); i++) {
            int id = highest + i;
            if (mc.world.getEntityById(id) != null) continue;

            EndCrystalEntity fake = new EndCrystalEntity(mc.world, 0, 0, 0);
            fake.setId(id);
            mc.getNetworkHandler().sendPacket(PlayerInteractEntityC2SPacket.attack(fake, mc.player.isSneaking()));
        }
    }

    private Choice evaluate(List<Target> targets, Vec3d crystal, float health) {
        float self = ExplosionDamage.crystal(mc.player, crystal);
        if (self >= health || self > maxSelfDamage.get()) return null;
        if (hurtsFriend(crystal)) return null;

        Choice best = null;
        for (Target target : targets) {
            float bound = ExplosionDamage.upperBound(target.player, target.feet, crystal);
            if (bound < target.minDamage && bound < target.health) continue;
            if (best != null && bound < best.damage) continue;

            float damage = ExplosionDamage.crystal(target.player, target.feet, target.box, crystal, mc.world);
            if (damage < target.minDamage && damage < target.health) continue;

            Choice choice = new Choice(target, damage, self);
            if (choice.beats(best)) best = choice;
        }

        return best;
    }

    private boolean hurtsFriend(Vec3d crystal) {
        double range = ExplosionDamage.CRYSTAL_RANGE * ExplosionDamage.CRYSTAL_RANGE;
        for (PlayerEntity player : mc.world.getPlayers()) {
            if (player == mc.player || !Friends.get().isFriend(player) || player.squaredDistanceTo(crystal) > range) continue;
            if (ExplosionDamage.crystal(player, crystal) >= player.getHealth() + player.getAbsorptionAmount()) return true;
        }
        return false;
    }

    private List<Target> targets() {
        List<Target> targets = new ArrayList<>();
        double range = targetRange.get() * targetRange.get();
        int ticks = predict.get() ? MathHelper.ceil(LatencyManager.get().rtt() / 2.0 / 50.0) : 0;

        for (PlayerEntity player : mc.world.getPlayers()) {
            if (player == mc.player || !player.isAlive() || Friends.get().isFriend(player)) continue;
            if (ExplosionDamage.isInvulnerable(player) || player.squaredDistanceTo(mc.player) > range) continue;

            Box box = MotionTracker.get().predict(player, ticks);
            Vec3d feet = new Vec3d((box.minX + box.maxX) / 2, box.minY, (box.minZ + box.maxZ) / 2);
            float health = player.getHealth() + player.getAbsorptionAmount();
            targets.add(new Target(player, box, feet, health, minDamage(player, health)));
        }

        return targets;
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
                    if (!inPlaceRange(base) || !isBase(mc.world.getBlockState(base))) continue;
                    if (!mc.world.isAir(base.up()) || CrystalTracker.get().isPlacing(base) || blocked(base)) continue;
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
        return !mc.world.getOtherEntities(null, box, entity -> !(entity instanceof EndCrystalEntity) || tracker.isAlive(entity)).isEmpty();
    }

    private boolean rotationReady(Box box, Vec3d aim, double range) {
        if (!rotate.get()) return true;

        RotationManager rotations = RotationManager.get();
        rotations.request(ROTATION_PRIORITY, aim, rotateSpeed.get().floatValue(), moveFix.get());
        return rotations.isLookingAt(box, range);
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
