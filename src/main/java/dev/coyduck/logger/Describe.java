package dev.coyduck.logger;

import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.entity.damage.DamageType;
import net.minecraft.util.math.Vec3d;

import java.util.Locale;

public final class Describe {
    private static final EquipmentSlot[] ARMOR = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

    private Describe() {}

    public static String item(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return "empty";
        StringBuilder out = new StringBuilder(Registries.ITEM.getId(stack.getItem()).getPath());
        if (stack.getCount() > 1) out.append('x').append(stack.getCount());
        if (stack.isDamageable() && stack.getMaxDamage() > 0) {
            out.append('(').append(Math.round(100.0 * (stack.getMaxDamage() - stack.getDamage()) / stack.getMaxDamage())).append("%)");
        }
        return out.toString();
    }

    public static String block(BlockState state) {
        return state == null ? "?" : Registries.BLOCK.getId(state.getBlock()).getPath();
    }

    public static String entity(Entity entity, int id) {
        if (entity == null) return "?#" + id;
        if (entity instanceof PlayerEntity player) return player.getName().getString() + "#" + id;
        if (entity instanceof EndCrystalEntity) return "crystal#" + id;
        return Registries.ENTITY_TYPE.getId(entity.getType()).getPath() + "#" + id;
    }

    public static String name(Entity entity) {
        if (entity instanceof PlayerEntity player) return player.getName().getString();
        return entity == null ? "?" : Registries.ENTITY_TYPE.getId(entity.getType()).getPath();
    }

    public static String damage(RegistryEntry<DamageType> type) {
        return type.getKey().map(key -> key.getValue().getPath()).orElse("?");
    }

    public static String armor(PlayerEntity player) {
        StringBuilder out = new StringBuilder();
        for (EquipmentSlot slot : ARMOR) {
            ItemStack stack = player.getEquippedStack(slot);
            if (!out.isEmpty()) out.append('/');
            if (stack.isEmpty()) out.append('-');
            else if (!stack.isDamageable() || stack.getMaxDamage() == 0) out.append(Registries.ITEM.getId(stack.getItem()).getPath());
            else out.append(Math.round(100.0 * (stack.getMaxDamage() - stack.getDamage()) / stack.getMaxDamage())).append('%');
        }
        return out.toString();
    }

    public static String num(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    public static String one(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    public static String signed(double value) {
        return String.format(Locale.ROOT, "%+.2f", value);
    }

    public static String vec(Vec3d value) {
        return signed(value.x) + "," + signed(value.y) + "," + signed(value.z);
    }
}
