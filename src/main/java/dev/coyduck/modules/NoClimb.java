package dev.coyduck.modules;

import dev.coyduck.feature.BoolSetting;
import dev.coyduck.feature.Feature;
import dev.coyduck.feature.Setting;
import dev.coyduck.feature.SettingGroup;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.registry.tag.BlockTags;

public class NoClimb extends Feature {
    private static NoClimb instance;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> ladders = sgGeneral.add(new BoolSetting.Builder()
        .name("ladders")
        .description("Ladders, vines and climbable plants don't hold you.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> scaffolding = sgGeneral.add(new BoolSetting.Builder()
        .name("scaffolding")
        .description("Scaffolding you are inside doesn't hold or lift you.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> webs = sgGeneral.add(new BoolSetting.Builder()
        .name("webs")
        .description("Cobwebs don't slow you down.")
        .defaultValue(false)
        .build()
    );

    public NoClimb() {
        super("no-climb", "Ignores ladders, scaffolding and webs.");
        instance = this;
    }

    public static boolean ignoresClimb(Entity entity, BlockState state) {
        NoClimb module = active(entity);
        if (module == null || !state.isIn(BlockTags.CLIMBABLE)) return false;
        return state.isOf(Blocks.SCAFFOLDING) ? module.scaffolding.get() : module.ladders.get();
    }

    public static boolean ignoresSlow(Entity entity, BlockState state) {
        NoClimb module = active(entity);
        return module != null && module.webs.get() && state.isOf(Blocks.COBWEB);
    }

    private static NoClimb active(Entity entity) {
        if (instance == null || entity != MinecraftClient.getInstance().player || !instance.isActive()) return null;
        return instance;
    }
}
