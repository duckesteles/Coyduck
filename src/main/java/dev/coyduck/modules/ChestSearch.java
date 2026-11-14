package dev.coyduck.modules;

import dev.coyduck.Coyduck;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.entry.RegistryEntry;

import java.util.Locale;

public class ChestSearch extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> insideShulkers = sgGeneral.add(new BoolSetting.Builder()
        .name("inside-shulkers")
        .description("Also matches shulker boxes holding something that matches.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> enchantments = sgGeneral.add(new BoolSetting.Builder()
        .name("enchantments")
        .description("Also matches enchantment names, like mending.")
        .defaultValue(true)
        .build()
    );

    private final Setting<SettingColor> color = sgGeneral.add(new ColorSetting.Builder()
        .name("color")
        .description("Highlight on matching slots.")
        .defaultValue(new SettingColor(185, 74, 230, 110))
        .build()
    );

    public ChestSearch() {
        super(Coyduck.CATEGORY, "chest-search", "Adds a search bar to chests, shulkers and ender chests that highlights matching items, including what is inside shulker boxes.");
    }

    public static ChestSearch active() {
        ChestSearch module = Modules.get() == null ? null : Modules.get().get(ChestSearch.class);
        return module != null && module.isActive() ? module : null;
    }

    public int highlight() {
        return color.get().getPacked();
    }

    public boolean matches(ItemStack stack, String query) {
        if (stack.isEmpty() || query.isBlank()) return false;
        String q = query.toLowerCase(Locale.ROOT).trim();
        if (own(stack, q)) return true;
        if (!insideShulkers.get()) return false;
        ContainerComponent container = stack.get(DataComponentTypes.CONTAINER);
        if (container == null) return false;
        for (ItemStack inner : container.iterateNonEmpty()) {
            if (own(inner, q)) return true;
        }
        return false;
    }

    private boolean own(ItemStack stack, String q) {
        if (stack.getName().getString().toLowerCase(Locale.ROOT).contains(q)) return true;
        if (!enchantments.get()) return false;
        for (Object2IntMap.Entry<RegistryEntry<Enchantment>> entry : EnchantmentHelper.getEnchantments(stack).getEnchantmentEntries()) {
            if (Enchantment.getName(entry.getKey(), entry.getIntValue()).getString().toLowerCase(Locale.ROOT).contains(q)) return true;
        }
        return false;
    }
}
