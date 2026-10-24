package dev.coyduck.modules;

import dev.coyduck.Coyduck;
import dev.coyduck.managers.InteractionManager;
import dev.coyduck.managers.InventoryManager;
import dev.coyduck.managers.MiningManager;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Blocks;
import net.minecraft.block.EntityShapeContext;
import net.minecraft.block.ShapeContext;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;

import java.util.ArrayList;
import java.util.List;

public class AntiWeb extends Module {
    private static final int PRIORITY = 120;
    private static final double EDGE = 1.0E-4;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> solid = sgGeneral.add(new BoolSetting.Builder()
        .name("solid")
        .description("Treats cobwebs as solid blocks for you, so you never walk, jump or fall into one. The server only sees you stop.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> breakWebs = sgGeneral.add(new BoolSetting.Builder()
        .name("break")
        .description("Breaks any cobweb you are stuck in right away with your best tool, two at once when there are two.")
        .defaultValue(true)
        .build()
    );

    private final Setting<InventoryManager.SwitchMode> switchMode = sgGeneral.add(new EnumSetting.Builder<InventoryManager.SwitchMode>()
        .name("switch")
        .description("How the tool is put in your hand for the final break.")
        .defaultValue(InventoryManager.SwitchMode.InvSwap)
        .visible(breakWebs::get)
        .build()
    );

    private final Setting<InteractionManager.SwingMode> swing = sgGeneral.add(new EnumSetting.Builder<InteractionManager.SwingMode>()
        .name("swing")
        .description("How the hand swing is shown.")
        .defaultValue(InteractionManager.SwingMode.Packet)
        .visible(breakWebs::get)
        .build()
    );

    public AntiWeb() {
        super(Coyduck.CATEGORY, "anti-web", "Keeps you out of cobwebs and breaks the ones put on you, without any movement the server could pull you back for.");
    }

    public static boolean solid(BlockPos pos, ShapeContext context) {
        PlayerEntity player = MeteorClient.mc.player;
        if (player == null || !(context instanceof EntityShapeContext entityContext) || entityContext.getEntity() != player) return false;

        AntiWeb module = Modules.get().get(AntiWeb.class);
        if (module == null || !module.isActive() || !module.solid.get()) return false;
        return !player.getBoundingBox().intersects(new Box(pos));
    }

    @Override
    public void onDeactivate() {
        MiningManager.get().release(this);
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;

        MiningManager mining = MiningManager.get();
        List<BlockPos> webs = breakWebs.get() ? webs() : List.of();
        if (webs.isEmpty()) {
            mining.release(this);
            return;
        }

        BlockPos first = webs.getFirst();
        if (mining.owns(this) && webs.contains(mining.pos())) return;

        if (webs.size() > 1) mining.mineTwo(this, PRIORITY, first, webs.get(1), true, switchMode.get(), swing.get());
        else mining.mine(this, PRIORITY, first, true, switchMode.get(), swing.get());
    }

    private List<BlockPos> webs() {
        Box box = mc.player.getBoundingBox();
        List<BlockPos> webs = new ArrayList<>();

        for (int y = MathHelper.floor(box.minY + EDGE); y <= MathHelper.floor(box.maxY - EDGE); y++) {
            for (int x = MathHelper.floor(box.minX + EDGE); x <= MathHelper.floor(box.maxX - EDGE); x++) {
                for (int z = MathHelper.floor(box.minZ + EDGE); z <= MathHelper.floor(box.maxZ - EDGE); z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (mc.world.getBlockState(pos).isOf(Blocks.COBWEB)) webs.add(pos);
                }
            }
        }
        return webs;
    }
}
