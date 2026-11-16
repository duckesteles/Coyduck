package dev.coyduck.modules;

import dev.coyduck.Coyduck;
import dev.coyduck.events.TickEvent;
import dev.coyduck.events.WorldResetEvent;
import dev.coyduck.managers.InteractionManager;
import dev.coyduck.managers.InventoryManager;
import dev.coyduck.managers.MiningManager;
import dev.coyduck.utils.DuckRender;
import meteordevelopment.meteorclient.events.entity.player.StartBreakingBlockEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.util.Util;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

public class PacketMinePlus extends Module implements MiningManager.HidesMining, MiningManager.RebreaksOwn {
    private static final int PRIORITY = 150;
    private static final Color PROGRESS_START = new Color(230, 60, 60);
    private static final Color PROGRESS_END = new Color(80, 220, 90);

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");

    private final Setting<Boolean> rebreak = sgGeneral.add(new BoolSetting.Builder()
        .name("rebreak")
        .description("Rebreaks anything placed back in the block.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> rebreakTime = sgGeneral.add(new IntSetting.Builder()
        .name("rebreak-time")
        .description("Rebreak time in seconds. 0 keeps it until you click away.")
        .defaultValue(15)
        .range(0, 300)
        .sliderRange(0, 60)
        .visible(rebreak::get)
        .build()
    );

    private final Setting<Boolean> doubleMine = sgGeneral.add(new BoolSetting.Builder()
        .name("double-mine")
        .description("Mines a second block while the first keeps going.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> hideMining = sgGeneral.add(new BoolSetting.Builder()
        .name("hide-mining")
        .description("Hides the cracks from others.")
        .defaultValue(true)
        .build()
    );

    private final Setting<InventoryManager.SwitchMode> switchMode = sgGeneral.add(new EnumSetting.Builder<InventoryManager.SwitchMode>()
        .name("switch")
        .description("How to switch to the best tool.")
        .defaultValue(InventoryManager.SwitchMode.Silent)
        .build()
    );

    private final Setting<InteractionManager.SwingMode> swing = sgGeneral.add(new EnumSetting.Builder<InteractionManager.SwingMode>()
        .name("swing")
        .description("How to swing while mining.")
        .defaultValue(InteractionManager.SwingMode.Client)
        .build()
    );

    private final Setting<Boolean> render = sgRender.add(new BoolSetting.Builder()
        .name("render")
        .description("Draws the block being mined, filling from red to green.")
        .defaultValue(true)
        .build()
    );

    private final Setting<SettingColor> sideColor = sgRender.add(new ColorSetting.Builder()
        .name("side-color")
        .description("Fill alpha. The color follows the progress.")
        .defaultValue(new SettingColor(255, 255, 255, 40))
        .visible(render::get)
        .build()
    );

    private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder()
        .name("line-color")
        .description("Outline alpha. The color follows the progress.")
        .defaultValue(new SettingColor(255, 255, 255, 200))
        .visible(render::get)
        .build()
    );

    private final DuckRender.Smooth smooth = new DuckRender.Smooth();
    private final Color sideShade = new Color();
    private final Color lineShade = new Color();
    private BlockPos target;
    private long brokenAt;

    public PacketMinePlus() {
        super(Coyduck.CATEGORY, "packet-mine+", "Packet mines the blocks you click.");
    }

    @Override
    public void onActivate() {
        Module meteor = Modules.get().get(meteordevelopment.meteorclient.systems.modules.world.PacketMine.class);
        if (meteor != null && meteor.isActive()) {
            meteor.toggle();
            info("Disabled %s, it handles the same clicks.", meteor.title);
        }
        target = null;
        brokenAt = 0;
    }

    @Override
    public void onDeactivate() {
        MiningManager.get().release(this);
        target = null;
    }

    @Override
    public boolean hideMining() {
        return hideMining.get();
    }

    @EventHandler
    private void onWorldReset(WorldResetEvent event) {
        MiningManager.get().release(this);
        target = null;
    }

    @EventHandler
    private void onStartBreaking(StartBreakingBlockEvent event) {
        if (mc.player == null || mc.world == null || mc.player.getAbilities().creativeMode) return;
        if (mc.world.getBlockState(event.blockPos).getHardness(mc.world, event.blockPos) < 0) return;
        event.cancel();

        BlockPos pos = event.blockPos.toImmutable();
        MiningManager mining = MiningManager.get();
        if (pos.equals(target) && mining.owns(this) && mining.isMining(pos)) return;
        if (!mining.inRange(pos)) return;
        boolean paired = doubleMine.get() && target != null && mining.owns(this) && mining.isMining(target) && !mining.wasBroken() && mining.background() == null;
        if (paired && mining.mineTwo(this, PRIORITY, pos, target, true, switchMode.get(), swing.get())) {
            target = pos;
            brokenAt = 0;
            return;
        }
        if (mining.mine(this, PRIORITY, pos, true, switchMode.get(), swing.get())) {
            target = pos;
            brokenAt = 0;
        }
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (target == null) return;
        MiningManager mining = MiningManager.get();
        if (!mining.owns(this) || !mining.isMining(target)) {
            target = null;
            return;
        }
        if (!mining.wasBroken()) return;
        long now = Util.getMeasuringTimeMs();
        if (brokenAt == 0) brokenAt = now;
        boolean expired = rebreakTime.get() > 0 && now - brokenAt > rebreakTime.get() * 1000L;
        if (!rebreak.get() || expired) {
            mining.release(this);
            target = null;
        }
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (!render.get() || target == null || !MiningManager.get().owns(this)) return;
        double progress = smooth.toward(MiningManager.get().progress(), 12);
        Color side = DuckRender.lerp(sideShade, PROGRESS_START.a(sideColor.get().a), PROGRESS_END.a(sideColor.get().a), progress);
        Color line = DuckRender.lerp(lineShade, PROGRESS_START.a(lineColor.get().a), PROGRESS_END.a(lineColor.get().a), progress);
        Box box = new Box(target);
        DuckRender.exact(event.renderer, box.contract((1 - progress) * 0.5), side, line, ShapeMode.Sides, 1);
        DuckRender.exact(event.renderer, box, side, line, ShapeMode.Lines, 1);
    }
}
