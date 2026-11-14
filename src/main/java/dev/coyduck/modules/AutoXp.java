package dev.coyduck.modules;

import dev.coyduck.Coyduck;
import dev.coyduck.managers.InventoryManager;
import dev.coyduck.managers.MiningManager;
import dev.coyduck.managers.RotationManager;
import dev.coyduck.utils.Targets;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.ExperienceOrbEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.thrown.ExperienceBottleEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Box;

public class AutoXp extends Module {
    private static final int ROTATE_PRIORITY = 40;
    private static final int BOTTLE_VALUE = 7;
    private static final double ORB_RANGE = 3;
    private static final double REPAIR_PER_XP = 2;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Integer> start = sgGeneral.add(new IntSetting.Builder()
        .name("start")
        .description("Starts mending once a mending armor piece drops below this durability percent.")
        .defaultValue(70)
        .range(1, 99)
        .sliderRange(10, 95)
        .build()
    );

    private final Setting<Integer> stop = sgGeneral.add(new IntSetting.Builder()
        .name("stop")
        .description("Keeps mending until every mending armor piece reaches this durability percent.")
        .defaultValue(98)
        .range(2, 100)
        .sliderRange(50, 100)
        .build()
    );

    private final Setting<Integer> perTick = sgGeneral.add(new IntSetting.Builder()
        .name("bottles-per-tick")
        .description("Bottles thrown each tick while mending. None go out while the experience already thrown covers the repair.")
        .defaultValue(2)
        .range(1, 5)
        .sliderRange(1, 5)
        .build()
    );

    private final Setting<Boolean> onlyHoled = sgGeneral.add(new BoolSetting.Builder()
        .name("only-in-hole")
        .description("Only mends while you stand in a hole.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> enemyRange = sgGeneral.add(new DoubleSetting.Builder()
        .name("enemy-range")
        .description("Waits while an enemy is closer than this. 0 mends during fights too.")
        .defaultValue(0)
        .range(0, 12)
        .sliderRange(0, 12)
        .build()
    );

    private final Setting<Boolean> pauseMining = sgGeneral.add(new BoolSetting.Builder()
        .name("pause-mining")
        .description("Waits while you are mining.")
        .defaultValue(true)
        .build()
    );

    private boolean mending;

    public AutoXp() {
        super(Coyduck.CATEGORY, "auto-xp", "Throws experience bottles at your feet to mend your armor once it wears down. Turns your head only when nothing more important needs it.");
    }

    @Override
    public void onActivate() {
        mending = false;
    }

    @Override
    public String getInfoString() {
        return mending && mc.player != null ? lowest() + "%" : null;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null || mc.interactionManager == null || !mc.player.isAlive()) return;

        int lowest = lowest();
        if (lowest < 0) {
            mending = false;
            return;
        }
        if (lowest < start.get()) mending = true;
        else if (lowest >= stop.get()) mending = false;
        if (!mending) return;

        if (InventoryManager.get().isEating() || InventoryManager.get().isOffhandEating()) return;
        MiningManager mining = MiningManager.get();
        if (pauseMining.get() && mining.pos() != null && !mining.wasBroken()) return;
        if (onlyHoled.get() && !Targets.isHoled(Targets.columns(mc.player.getBoundingBox()))) return;
        if (enemyRange.get() > 0 && Targets.find(enemyRange.get()) != null) return;
        if (shared()) return;

        int throwable = throwable();
        if (throwable <= 0) return;

        InventoryManager inventory = InventoryManager.get();
        int slot = inventory.find(stack -> stack.isOf(Items.EXPERIENCE_BOTTLE), 0, InventoryManager.MAIN_END);
        if (slot == -1) return;
        if (slot >= InventoryManager.HOTBAR_END && !emptyHotbar()) return;

        RotationManager rotations = RotationManager.get();
        float yaw = rotations.serverYaw();
        rotations.request(ROTATE_PRIORITY, yaw, 90, 180, false);
        if (!rotations.isLookingAt(new Box(mc.player.getBlockPos().down()), 3)) return;

        inventory.withItem(slot, InventoryManager.SwitchMode.Silent, () -> {
            float pitch = mc.player.getPitch();
            float cameraYaw = mc.player.getYaw();
            mc.player.setPitch(90);
            mc.player.setYaw(yaw);
            for (int i = 0; i < throwable && mc.player.getMainHandStack().isOf(Items.EXPERIENCE_BOTTLE); i++) {
                mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
            }
            mc.player.setPitch(pitch);
            mc.player.setYaw(cameraYaw);
        });
    }

    private int throwable() {
        Box area = mc.player.getBoundingBox().expand(ORB_RANGE);
        int pending = 0;
        for (ExperienceOrbEntity orb : mc.world.getEntitiesByClass(ExperienceOrbEntity.class, area, orb -> !orb.isRemoved())) pending += orb.getValue();
        for (ExperienceBottleEntity bottle : mc.world.getEntitiesByClass(ExperienceBottleEntity.class, area.expand(ORB_RANGE), bottle -> bottle.getOwner() == mc.player)) pending += BOTTLE_VALUE;
        if (pending * REPAIR_PER_XP >= needed()) return 0;
        return perTick.get();
    }

    private double needed() {
        double needed = 0;
        double keep = 1 - stop.get() / 100.0;
        for (EquipmentSlot slot : EquipmentSlot.VALUES) {
            ItemStack stack = mc.player.getEquippedStack(slot);
            if (stack.isEmpty() || !stack.isDamageable() || !stack.isDamaged() || !mends(stack)) continue;
            double damage = stack.getDamage();
            needed += slot.getType() == EquipmentSlot.Type.HUMANOID_ARMOR ? Math.max(0, damage - keep * stack.getMaxDamage()) : damage;
        }
        return needed;
    }

    private boolean shared() {
        Box ours = mc.player.getBoundingBox().expand(0.5);
        for (PlayerEntity player : mc.world.getPlayers()) {
            if (player != mc.player && !player.isSpectator() && player.isAlive() && player.getBoundingBox().expand(1, 0.5, 1).intersects(ours)) return true;
        }
        return false;
    }

    private boolean emptyHotbar() {
        int reserved = AutoTotem.totemSlot();
        for (int i = 0; i < InventoryManager.HOTBAR_END; i++) {
            if (i != reserved && InventoryManager.get().stack(i).isEmpty()) return true;
        }
        return false;
    }

    private int lowest() {
        int lowest = -1;
        for (EquipmentSlot slot : EquipmentSlot.VALUES) {
            if (slot.getType() != EquipmentSlot.Type.HUMANOID_ARMOR) continue;
            ItemStack stack = mc.player.getEquippedStack(slot);
            if (stack.isEmpty() || !stack.isDamageable() || !mends(stack)) continue;
            int percent = (int) Math.round(100.0 * (stack.getMaxDamage() - stack.getDamage()) / stack.getMaxDamage());
            if (lowest == -1 || percent < lowest) lowest = percent;
        }
        return lowest;
    }

    private boolean mends(ItemStack stack) {
        for (var entry : stack.getEnchantments().getEnchantmentEntries()) {
            if (entry.getKey().matchesKey(Enchantments.MENDING)) return true;
        }
        return false;
    }
}
