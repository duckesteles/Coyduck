package dev.coyduck.modules;

import dev.coyduck.Coyduck;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.registry.tag.BlockTags;

public class NoClimb extends Module {
    private static NoClimb instance;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> ladders = sgGeneral.add(new BoolSetting.Builder()
        .name("ladders")
        .description("Ladders, vines and other climbable plants don't hold or lift you; you walk and fall through them.")
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
        .description("Cobwebs don't slow you down. The server checks web slowdown and sets you back, so it is off by default.")
        .defaultValue(false)
        .build()
    );

    public NoClimb() {
        super(Coyduck.CATEGORY, "no-climb", "Ladders, scaffolding and cobwebs placed on you don't catch you: no climbing, no hanging, no web slowdown.");
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
        if (instance == null || entity != MinecraftClient.getInstance().player || !Modules.get().isActive(NoClimb.class)) return null;
        return instance;
    }
}
