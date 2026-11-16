package dev.coyduck.modules;

import dev.coyduck.events.PlayerMoveEvent;
import dev.coyduck.events.TickEvent;
import dev.coyduck.feature.DoubleSetting;
import dev.coyduck.feature.Feature;
import dev.coyduck.feature.Features;
import dev.coyduck.feature.IntSetting;
import dev.coyduck.feature.Setting;
import dev.coyduck.feature.SettingGroup;
import dev.coyduck.managers.InventoryManager;
import dev.coyduck.utils.Trace;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.entity.MovementType;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

public class SuffocationGuard extends Feature {
    private static final double SPEED = 0.2;
    private static final double ARRIVED = 0.05;
    private static final int GIVE_UP_TICKS = 40;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Integer> totems = sgGeneral.add(new IntSetting.Builder()
        .name("totems")
        .description("Leaves the wall at this many totems or fewer.")
        .defaultValue(1)
        .range(0, 10)
        .sliderRange(0, 5)
        .build()
    );

    private final Setting<Double> health = sgGeneral.add(new DoubleSetting.Builder()
        .name("health")
        .description("Leaves the wall at this health or lower while not eating.")
        .defaultValue(6)
        .range(0, 36)
        .sliderRange(0, 20)
        .build()
    );

    private Vec3d exit;
    private int ticks;

    public static boolean leaving() {
        SuffocationGuard module = Features.get(SuffocationGuard.class);
        return module != null && module.isActive() && module.exit != null;
    }

    public SuffocationGuard() {
        super("suffocation-guard", "Leaves the wall before suffocation kills you.");
    }

    @Override
    public void onDeactivate() {
        exit = null;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null || !mc.player.isAlive()) {
            exit = null;
            return;
        }
        if (!mc.player.isInsideWall()) {
            if (exit != null) Trace.log("suffocation-guard is out of the wall");
            exit = null;
            return;
        }
        if (exit != null) {
            if (++ticks > GIVE_UP_TICKS) {
                Trace.log("suffocation-guard could not get out");
                exit = null;
                return;
            }
            if (exit.y > mc.player.getY() + 0.5 && mc.player.isOnGround()) mc.player.jump();
            return;
        }
        if (!danger()) return;
        exit = findExit();
        ticks = 0;
        if (exit != null) Trace.log("suffocation-guard leaves the wall, {} totems, {} health", totemCount(), mc.player.getHealth() + mc.player.getAbsorptionAmount());
    }

    @EventHandler(priority = EventPriority.HIGH)
    private void onMove(PlayerMoveEvent event) {
        if (exit == null || event.type != MovementType.SELF || mc.player == null) return;
        double dx = exit.x - mc.player.getX();
        double dz = exit.z - mc.player.getZ();
        double length = Math.sqrt(dx * dx + dz * dz);
        if (length < ARRIVED) return;
        double step = Math.min(SPEED, length);
        event.set(dx / length * step, event.y(), dz / length * step);
    }

    private boolean danger() {
        if (totemCount() <= totems.get()) return true;
        boolean eating = mc.player.isUsingItem() || InventoryManager.get().isOffhandEating();
        return !eating && mc.player.getHealth() + mc.player.getAbsorptionAmount() <= health.get();
    }

    private int totemCount() {
        return InventoryManager.get().count(stack -> stack.isOf(Items.TOTEM_OF_UNDYING));
    }

    private Vec3d findExit() {
        Vec3d level = findExit(0);
        return level != null ? level : findExit(1);
    }

    private Vec3d findExit(int up) {
        BlockPos feet = mc.player.getBlockPos();
        Vec3d pos = mc.player.getEntityPos();
        Box box = mc.player.getBoundingBox();
        double y = up == 0 ? pos.y : Math.floor(pos.y) + up;
        Vec3d best = null;
        double closest = Double.MAX_VALUE;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                Vec3d center = new Vec3d(feet.getX() + dx + 0.5, y, feet.getZ() + dz + 0.5);
                Box moved = box.offset(center.x - pos.x, center.y - pos.y, center.z - pos.z);
                if (!mc.world.isSpaceEmpty(mc.player, moved)) continue;
                if (up > 0 && !mc.world.isSpaceEmpty(mc.player, box.offset(0, center.y - pos.y, 0))) continue;
                double distance = center.squaredDistanceTo(pos);
                if (distance < closest) {
                    closest = distance;
                    best = center;
                }
            }
        }
        return best;
    }
}
