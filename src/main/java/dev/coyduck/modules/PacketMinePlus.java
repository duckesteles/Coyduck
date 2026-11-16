package dev.coyduck.modules;

import dev.coyduck.events.RenderWorldEvent;
import dev.coyduck.events.TickEvent;
import dev.coyduck.events.WorldResetEvent;
import dev.coyduck.feature.BoolSetting;
import dev.coyduck.feature.ColorSetting;
import dev.coyduck.feature.EnumSetting;
import dev.coyduck.feature.Feature;
import dev.coyduck.feature.IntSetting;
import dev.coyduck.feature.Rgba;
import dev.coyduck.feature.Setting;
import dev.coyduck.feature.SettingGroup;
import dev.coyduck.managers.InteractionManager;
import dev.coyduck.managers.InventoryManager;
import dev.coyduck.managers.MiningManager;
import dev.coyduck.render.DuckRender;
import dev.coyduck.render.Shape;
import meteordevelopment.meteorclient.events.entity.player.StartBreakingBlockEvent;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.util.Util;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

public class PacketMinePlus extends Feature implements MiningManager.HidesMining, MiningManager.RebreaksOwn {
    private static final int PRIORITY = 150;

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

    private final ColorSetting sideColor = sgRender.add(new ColorSetting.Builder()
        .name("side-color")
        .description("Fill alpha. The color follows the progress.")
        .defaultValue(new Rgba(255, 255, 255, 40))
        .visible(render::get)
        .build()
    );

    private final ColorSetting lineColor = sgRender.add(new ColorSetting.Builder()
        .name("line-color")
        .description("Outline alpha. The color follows the progress.")
        .defaultValue(new Rgba(255, 255, 255, 200))
        .visible(render::get)
        .build()
    );

    private final DuckRender.Smooth smooth = new DuckRender.Smooth();
    private BlockPos target;
    private long brokenAt;

    public PacketMinePlus() {
        super("packet-mine+", "Packet mines the blocks you click.");
    }

    @Override
    public void onActivate() {
        displace("it handles the same clicks.", "packet-mine");
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
    private void onRender(RenderWorldEvent event) {
        if (!render.get() || target == null || !MiningManager.get().owns(this)) return;
        double progress = smooth.toward(MiningManager.get().progress(), 12);
        int side = DuckRender.progress(progress, sideColor.argb());
        int line = DuckRender.progress(progress, lineColor.argb());
        Box box = new Box(target);
        DuckRender.exact(event.canvas, box.contract((1 - progress) * 0.5), side, line, Shape.Sides, 1);
        DuckRender.exact(event.canvas, box, side, line, Shape.Lines, 1);
    }
}
