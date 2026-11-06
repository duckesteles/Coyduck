package dev.coyduck.modules;

import dev.coyduck.Coyduck;
import dev.coyduck.managers.CrystalTracker;
import dev.coyduck.managers.HurtTracker;
import dev.coyduck.managers.InteractionManager;
import dev.coyduck.managers.InventoryManager;
import dev.coyduck.managers.LatencyManager;
import dev.coyduck.managers.MiningManager;
import dev.coyduck.managers.RotationManager;
import dev.coyduck.utils.ExplosionDamage;
import dev.coyduck.utils.Targets;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.block.Blocks;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.WeaponComponent;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LazyEntityReference;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.entity.Tameable;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.EntityTypeTags;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.util.Util;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class Aura extends Module {
    private static final int PRIORITY = 80;
    private static final float ROTATE_SPEED = 180.0f;
    private static final double AIM_INSET = 0.05;
    private static final double[] GRID = {0.5, 0.15, 0.85};
    private static final double PREFILTER = 3.0;
    private static final float CRIT_PROGRESS = 0.9f;
    private static final float HURT_WORTH = 0.6f;
    private static final int CRIT_WAIT = 10;
    private static final float LEAD_TICKS = 2.5f;
    private static final long LEAD_TIME = 100;
    private static final float CHOICE_EPSILON = 1.0E-3f;

    public enum Priority {
        Health,
        Distance
    }

    private record Choice(LivingEntity entity, Box box, Vec3d aim, boolean free, boolean focus, double metric) {}

    private record Weapon(int slot, float raw, boolean breaksShield) {}

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgTargets = settings.createGroup("Targets");

    private final Setting<Double> range = sgGeneral.add(new DoubleSetting.Builder()
        .name("range")
        .description("Reach from your eyes to the closest point of the target's hitbox. Vanilla is 3.0 and the server enforces it.")
        .defaultValue(3.0)
        .range(1, 6)
        .sliderRange(1, 6)
        .build()
    );

    private final Setting<Double> cooldown = sgGeneral.add(new DoubleSetting.Builder()
        .name("cooldown")
        .description("Attack charge needed before hitting. 1.0 waits for a full charge with whatever weapon the server sees you holding.")
        .defaultValue(1.0)
        .range(0, 1)
        .sliderRange(0, 1)
        .build()
    );

    private final Setting<InventoryManager.SwitchMode> switchMode = sgGeneral.add(new EnumSetting.Builder<InventoryManager.SwitchMode>()
        .name("switch-mode")
        .description("How the best weapon is brought into your main hand for the hit and put back. Your offhand is never touched.")
        .defaultValue(InventoryManager.SwitchMode.Silent)
        .build()
    );

    private final Setting<Boolean> onlyWeapon = sgGeneral.add(new BoolSetting.Builder()
        .name("only-weapon")
        .description("Only hits with a sword, axe or mace.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> crits = sgGeneral.add(new BoolSetting.Builder()
        .name("crits")
        .description("While you are rising from a jump, holds the hit until you start falling so it lands as a critical. Never fakes a jump.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> swingAnimation = sgGeneral.add(new BoolSetting.Builder()
        .name("swing-animation")
        .description("Shows the hand swing. The swing packet is sent with every hit either way.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> rotate = sgGeneral.add(new BoolSetting.Builder()
        .name("rotate")
        .description("Faces the target before hitting it.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> throughWalls = sgGeneral.add(new BoolSetting.Builder()
        .name("through-walls")
        .description("Hits targets you cannot see.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> pauseCrystal = sgGeneral.add(new BoolSetting.Builder()
        .name("pause-crystal")
        .description("Pauses while auto-crystal is placing or breaking so crystals stay first.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Priority> priority = sgTargets.add(new EnumSetting.Builder<Priority>()
        .name("priority")
        .description("Which target is hit first after the auto-crystal target and targets that can take damage right now.")
        .defaultValue(Priority.Health)
        .build()
    );

    private final Setting<Boolean> hostiles = sgTargets.add(new BoolSetting.Builder()
        .name("hostiles")
        .description("Also hits hostile mobs.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> passives = sgTargets.add(new BoolSetting.Builder()
        .name("passives")
        .description("Also hits passive mobs. Your own pets are skipped.")
        .defaultValue(false)
        .build()
    );

    private LivingEntity target;
    private int critWait;

    public Aura() {
        super(Coyduck.CATEGORY, "aura", "Hits players in reach with the best weapon once the attack is charged.");
    }

    @Override
    public void onDeactivate() {
        target = null;
        critWait = 0;
    }

    @Override
    public String getInfoString() {
        return target == null ? null : target.getName().getString();
    }

    @EventHandler(priority = EventPriority.LOW)
    private void onTickPre(TickEvent.Pre event) {
        if (!canAct()) {
            target = null;
            return;
        }

        Choice choice = choose();
        target = choice == null ? null : choice.entity();
        if (choice == null || paused() || !rotate.get()) return;
        if (mc.player.getAttackCooldownProgress(LEAD_TICKS) < cooldown.get()) return;
        if (Util.getMeasuringTimeMs() + LEAD_TIME < HurtTracker.get().releaseTime(choice.entity())) return;
        RotationManager.get().request(PRIORITY, choice.aim(), ROTATE_SPEED, true);
    }

    @EventHandler(priority = EventPriority.LOW)
    private void onTickPost(TickEvent.Post event) {
        if (!canAct()) {
            target = null;
            return;
        }
        if (mc.player.isOnGround()) critWait = 0;

        Choice choice = choose();
        target = choice == null ? null : choice.entity();
        if (choice == null || paused()) return;
        attack(choice);
    }

    private void attack(Choice choice) {
        float progress = mc.player.getAttackCooldownProgress(0.5f);
        if (progress < cooldown.get()) return;

        if (crits.get() && critComing() && critWait < CRIT_WAIT) {
            critWait++;
            return;
        }

        LivingEntity entity = choice.entity();
        boolean crit = progress > CRIT_PROGRESS && critNow();
        Weapon weapon = weapon(entity, progress, crit);
        if (weapon == null) return;

        long now = Util.getMeasuringTimeMs();
        if (!hurtFree(entity, weapon.raw(), now)) return;

        if (rotate.get()) {
            RotationManager rotations = RotationManager.get();
            rotations.request(PRIORITY, choice.aim(), ROTATE_SPEED, true);
            if (!rotations.isLookingAt(choice.box(), range.get())) return;
        }

        InteractionManager.SwingMode swing = swingAnimation.get() ? InteractionManager.SwingMode.Client : InteractionManager.SwingMode.Packet;
        Runnable hit = () -> {
            mc.interactionManager.attackEntity(mc.player, entity);
            InteractionManager.get().swing(swing);
        };

        InventoryManager inventory = InventoryManager.get();
        if (weapon.slot() == inventory.selectedSlot()) hit.run();
        else if (!inventory.withItem(weapon.slot(), switchMode.get(), hit)) return;

        critWait = 0;
        if (!entity.isBlocking()) HurtTracker.get().expect(entity, weapon.raw());
    }

    private Choice choose() {
        Vec3d eye = mc.player.getEyePos();
        double reach = range.get();
        long now = Util.getMeasuringTimeMs();

        AutoCrystal autoCrystal = Modules.get().get(AutoCrystal.class);
        PlayerEntity focus = autoCrystal == null ? null : autoCrystal.target();

        Choice best = null;
        for (Entity entity : mc.world.getEntities()) {
            if (!(entity instanceof LivingEntity living) || !wanted(living, reach)) continue;

            Box box = box(living);
            double distance = box.squaredMagnitude(eye);
            if (distance > reach * reach) continue;

            Vec3d aim = aim(box, eye, reach);
            if (aim == null) continue;

            boolean free = now >= HurtTracker.get().releaseTime(living);
            double metric = priority.get() == Priority.Health ? living.getHealth() + living.getAbsorptionAmount() : distance;
            Choice choice = new Choice(living, box, aim, free, living == focus, metric);
            if (better(choice, best)) best = choice;
        }
        return best;
    }

    private static boolean better(Choice a, Choice b) {
        if (b == null) return true;
        if (a.free() != b.free()) return a.free();
        if (a.focus() != b.focus()) return a.focus();
        return a.metric() < b.metric();
    }

    private boolean wanted(LivingEntity living, double reach) {
        if (living == mc.player || !living.isAlive() || living.isRemoved() || living.getHealth() <= 0) return false;
        if (living instanceof PlayerEntity player) return Targets.valid(player, reach + PREFILTER);
        if (living.squaredDistanceTo(mc.player) > (reach + PREFILTER) * (reach + PREFILTER)) return false;
        if (living instanceof Tameable pet && isOwn(pet)) return false;

        SpawnGroup group = living.getType().getSpawnGroup();
        if (group == SpawnGroup.MONSTER) return hostiles.get();
        return group != SpawnGroup.MISC && passives.get();
    }

    private boolean isOwn(Tameable pet) {
        LazyEntityReference<LivingEntity> owner = pet.getOwnerReference();
        return owner != null && mc.player.getUuid().equals(owner.getUuid());
    }

    private static Box box(LivingEntity living) {
        if (living instanceof PlayerEntity player) return Targets.box(player);
        if (living.hasVehicle()) return living.getBoundingBox();

        Vec3d tracked = living.getTrackedPosition().getPos();
        if (tracked.equals(Vec3d.ZERO)) return living.getBoundingBox();
        return living.getBoundingBox().offset(tracked.subtract(living.getEntityPos()));
    }

    private Vec3d aim(Box box, Vec3d eye, double reach) {
        if (box.contains(eye)) return box.getCenter();

        Box inner = box.contract(AIM_INSET);
        Vec3d closest = new Vec3d(
            MathHelper.clamp(eye.x, inner.minX, inner.maxX),
            MathHelper.clamp(eye.y, inner.minY, inner.maxY),
            MathHelper.clamp(eye.z, inner.minZ, inner.maxZ)
        );

        double limit = reach + 2 * AIM_INSET;
        double limitSquared = limit * limit;
        if (throughWalls.get()) return closest.squaredDistanceTo(eye) <= limitSquared ? closest : null;

        List<Vec3d> points = new ArrayList<>();
        points.add(closest);
        for (double x : GRID) {
            for (double y : GRID) {
                for (double z : GRID) {
                    points.add(new Vec3d(
                        MathHelper.lerp(x, inner.minX, inner.maxX),
                        MathHelper.lerp(y, inner.minY, inner.maxY),
                        MathHelper.lerp(z, inner.minZ, inner.maxZ)
                    ));
                }
            }
        }
        points.sort(Comparator.comparingDouble(point -> point.squaredDistanceTo(eye)));

        for (Vec3d point : points) {
            if (point.squaredDistanceTo(eye) > limitSquared) break;
            if (visible(eye, point)) return point;
        }
        return null;
    }

    private boolean visible(Vec3d eye, Vec3d point) {
        return mc.world.raycast(new RaycastContext(eye, point, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, mc.player)).getType() == HitResult.Type.MISS;
    }

    private boolean hurtFree(LivingEntity entity, float raw, long send) {
        HurtTracker hurt = HurtTracker.get();
        if (send >= hurt.releaseTime(entity)) return true;

        float full = ExplosionDamage.mitigate(entity, raw);
        if (full <= 0) return false;

        float through = hurt.effective(entity, raw, send + LatencyManager.get().rtt() / 2);
        return through < full - CHOICE_EPSILON && through >= full * HURT_WORTH;
    }

    private Weapon weapon(LivingEntity entity, float progress, boolean crit) {
        InventoryManager inventory = InventoryManager.get();
        int selected = inventory.selectedSlot();
        ItemStack held = inventory.stack(selected);
        float base = base(held);
        boolean blocking = entity.isBlocking();

        Weapon best = null;
        if (usable(held) && (!onlyWeapon.get() || isWeapon(held))) {
            best = new Weapon(selected, damage(held, base, entity, progress, crit), breaksShield(held));
        }

        for (int i = 0; i < InventoryManager.MAIN_END; i++) {
            if (i == selected) continue;
            ItemStack stack = inventory.stack(i);
            if (!isWeapon(stack) || !usable(stack)) continue;

            Weapon option = new Weapon(i, damage(stack, base, entity, progress, crit), breaksShield(stack));
            if (beats(option, best, blocking)) best = option;
        }

        return best == null || best.raw() <= 0 ? null : best;
    }

    private static boolean beats(Weapon a, Weapon b, boolean blocking) {
        if (b == null) return true;
        if (blocking && a.breaksShield() != b.breaksShield()) return a.breaksShield();
        return a.raw() > b.raw() + CHOICE_EPSILON;
    }

    private static boolean isWeapon(ItemStack stack) {
        return stack.isIn(ItemTags.SWORDS) || stack.isIn(ItemTags.AXES) || stack.isOf(Items.MACE);
    }

    private boolean usable(ItemStack stack) {
        return !stack.contains(DataComponentTypes.PIERCING_WEAPON) && !mc.player.isBelowMinimumAttackCharge(stack, 0);
    }

    private static boolean breaksShield(ItemStack stack) {
        WeaponComponent weapon = stack.get(DataComponentTypes.WEAPON);
        return weapon != null && weapon.disableBlockingForSeconds() > 0;
    }

    private float base(ItemStack held) {
        double[] damage = {mc.player.getAttributeBaseValue(EntityAttributes.ATTACK_DAMAGE)};
        held.applyAttributeModifiers(EquipmentSlot.MAINHAND, (attribute, modifier) -> {
            if (attribute.equals(EntityAttributes.ATTACK_DAMAGE) && modifier.operation() == EntityAttributeModifier.Operation.ADD_VALUE) damage[0] += modifier.value();
        });

        StatusEffectInstance strength = mc.player.getStatusEffect(StatusEffects.STRENGTH);
        if (strength != null) damage[0] += 3.0 * (strength.getAmplifier() + 1);

        StatusEffectInstance weakness = mc.player.getStatusEffect(StatusEffects.WEAKNESS);
        if (weakness != null) damage[0] -= 4.0 * (weakness.getAmplifier() + 1);

        return (float) damage[0];
    }

    private float damage(ItemStack stack, float base, LivingEntity entity, float progress, boolean crit) {
        float damage = base * (0.2f + progress * progress * 0.8f);
        DamageSource source = mc.player.getDamageSources().playerAttack(mc.player);
        damage += stack.getItem().getBonusAttackDamage(entity, damage, source);
        if (crit) damage *= 1.5f;
        return damage + enchantBonus(stack, entity) * progress;
    }

    private static float enchantBonus(ItemStack stack, LivingEntity entity) {
        float bonus = 0;
        for (var entry : stack.getEnchantments().getEnchantmentEntries()) {
            int level = entry.getIntValue();
            if (entry.getKey().matchesKey(Enchantments.SHARPNESS)) bonus += 0.5f * level + 0.5f;
            else if (entry.getKey().matchesKey(Enchantments.SMITE) && entity.getType().isIn(EntityTypeTags.SENSITIVE_TO_SMITE)) bonus += 2.5f * level;
            else if (entry.getKey().matchesKey(Enchantments.BANE_OF_ARTHROPODS) && entity.getType().isIn(EntityTypeTags.SENSITIVE_TO_BANE_OF_ARTHROPODS)) bonus += 2.5f * level;
        }
        return bonus;
    }

    private boolean critCapable() {
        return !mc.player.isClimbing() && !mc.player.isTouchingWater() && !mc.player.isInLava() && !mc.player.hasVehicle()
            && !mc.player.isSprinting() && !mc.player.isGliding() && !mc.player.getAbilities().flying
            && !mc.player.hasStatusEffect(StatusEffects.BLINDNESS) && !mc.player.hasStatusEffect(StatusEffects.LEVITATION)
            && !mc.player.hasStatusEffect(StatusEffects.SLOW_FALLING) && !inWeb();
    }

    private boolean critNow() {
        return critCapable() && !mc.player.isOnGround() && mc.player.fallDistance > 0;
    }

    private boolean critComing() {
        return critCapable() && !mc.player.isOnGround() && mc.player.fallDistance <= 0;
    }

    private boolean inWeb() {
        Box box = mc.player.getBoundingBox();
        for (BlockPos pos : BlockPos.iterate(MathHelper.floor(box.minX), MathHelper.floor(box.minY), MathHelper.floor(box.minZ),
            MathHelper.floor(box.maxX), MathHelper.floor(box.maxY), MathHelper.floor(box.maxZ))) {
            if (mc.world.getBlockState(pos).isOf(Blocks.COBWEB)) return true;
        }
        return false;
    }

    private boolean paused() {
        InventoryManager inventory = InventoryManager.get();
        if (inventory.isEating() || inventory.isOffhandEating()) return true;
        if (mc.interactionManager.isBreakingBlock() || MiningManager.get().isHolding()) return true;
        if (mc.currentScreen instanceof HandledScreen<?>) return true;
        return pauseCrystal.get() && crystalBusy();
    }

    private boolean crystalBusy() {
        AutoCrystal autoCrystal = Modules.get().get(AutoCrystal.class);
        if (autoCrystal == null || !autoCrystal.isActive()) return false;
        if (CrystalTracker.get().hasPlacing()) return true;

        InteractionManager interaction = InteractionManager.get();
        for (Entity entity : mc.world.getEntities()) {
            if (entity instanceof EndCrystalEntity && interaction.recentlyAttacked(entity)) return true;
        }
        return false;
    }

    private boolean canAct() {
        return mc.player != null && mc.world != null && mc.interactionManager != null && mc.getNetworkHandler() != null
            && mc.player.isAlive() && !mc.player.isSpectator();
    }
}
