package dev.coyduck.modules;

import dev.coyduck.events.TickEvent;
import dev.coyduck.feature.BoolSetting;
import dev.coyduck.feature.Feature;
import dev.coyduck.feature.Features;
import dev.coyduck.feature.Setting;
import dev.coyduck.feature.SettingGroup;
import dev.coyduck.utils.Trace;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

public class NoRotate extends Feature {
    private static final double INSET = 0.05;
    private static final double STILL = 0.003;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> inBlocks = sgGeneral.add(new BoolSetting.Builder()
        .name("in-blocks")
        .description("Sends no moves while standing still in a block.")
        .defaultValue(true)
        .build()
    );

    private boolean frozen;

    public NoRotate() {
        super("no-rotate+", "Keeps your view when the server turns it.");
    }

    public static boolean on() {
        NoRotate module = module();
        return module != null && module.isActive();
    }

    public static boolean frozen() {
        NoRotate module = module();
        return module != null && module.isActive() && module.frozen;
    }

    private static NoRotate module() {
        return Features.get(NoRotate.class);
    }

    @Override
    public void onActivate() {
        displace("it handles the same packets.", "no-rotate");
        frozen = false;
    }

    @Override
    public void onDeactivate() {
        frozen = false;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        boolean now = inBlocks.get() && shouldFreeze();
        if (now != frozen) Trace.log(now ? "no-rotate+ holds move packets, still inside a block" : "no-rotate+ sends move packets again");
        frozen = now;
    }

    private boolean shouldFreeze() {
        if (mc.player == null || mc.world == null || !mc.player.isAlive() || mc.player.isSpectator()) return false;
        if (mc.player.isGliding() || mc.player.hasVehicle() || mc.player.isTouchingWater() || mc.player.isInLava()) return false;
        if (MaceStrike.blocksMoves() || SuffocationGuard.leaving()) return false;
        if (mc.player.input.getMovementInput().lengthSquared() > 1.0E-4f || mc.player.input.playerInput.jump()) return false;
        Vec3d velocity = mc.player.getVelocity();
        if (velocity.x * velocity.x + velocity.z * velocity.z > STILL * STILL) return false;
        Box body = mc.player.getBoundingBox().contract(INSET);
        return mc.world.getBlockCollisions(mc.player, body).iterator().hasNext();
    }
}
