package dev.coyduck.logic;

import net.minecraft.enchantment.Enchantment;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.RegistryKey;

public final class Enchants {
    private Enchants() {}

    public static int level(ItemStack stack, RegistryKey<Enchantment> key) {
        for (var entry : stack.getEnchantments().getEnchantmentEntries()) {
            if (entry.getKey().matchesKey(key)) return entry.getIntValue();
        }
        return 0;
    }
}
