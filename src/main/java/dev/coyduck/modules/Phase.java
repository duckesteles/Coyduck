package dev.coyduck.modules;

import dev.coyduck.Coyduck;
import dev.coyduck.managers.InventoryManager;
import dev.coyduck.managers.LatencyManager;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.EntityPose;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;
import net.minecraft.util.Util;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

public class Phase extends Module {
    private static final double POWER = 1.5;
    private static final double DRAG = 0.99;
    private static final double GRAVITY = 0.03;
    private static final double SPAWN_DROP = 0.10000000149011612;
    private static final double WALL_GAP = 0.05;
    private static final double FLOOR_GAP = 0.01;
    private static final long LAND_SLACK = 300;
    private static final int LEAVE_TICKS = 5;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<InventoryManager.SwitchMode> switchMode = sgGeneral.add(new EnumSetting.Builder<InventoryManager.SwitchMode>()
        .name("switch")
        .description("How the ender pearl is put in your hand for the throw.")
        .defaultValue(InventoryManager.SwitchMode.InvSwap)
        .build()
    );

    private final Setting<Boolean> corners = sgGeneral.add(new BoolSetting.Builder()
        .name("corners")
        .description("Prefers a corner, so you end up inside two walls instead of one.")
        .defaultValue(true)
        .build()
    );

    private long thrownAt;
    private boolean clipped;
    private int freeTicks;

    public Phase() {
        super(Coyduck.CATEGORY, "phase", "Throws an ender pearl at your feet so you land partly inside the walls next to you.");
    }

    public static boolean holding() {
        Phase phase = Modules.get().get(Phase.class);
        return phase != null && phase.isActive() && (phase.clipped || phase.thrownAt != 0);
    }

    @Override
    public void onActivate() {
        clipped = false;
        freeTicks = 0;
        thrownAt = 0;
        if (mc.player == null || !throwPearl()) toggle();
    }

    @Override
    public void onDeactivate() {
        clipped = false;
        thrownAt = 0;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) {
            toggle();
            return;
        }

        boolean inside = !mc.world.isSpaceEmpty(mc.player, mc.player.getBoundingBox().contract(1.0E-3));
        if (!clipped) {
            if (inside) {
                clipped = true;
                thrownAt = 0;
            } else if (Util.getMeasuringTimeMs() - thrownAt > LatencyManager.get().timeout() + LAND_SLACK) {
                info("The pearl did not land in the wall.");
                toggle();
            }
            return;
        }

        if (inside) freeTicks = 0;
        else if (++freeTicks >= LEAVE_TICKS) toggle();
    }

    private boolean throwPearl() {
        if (!mc.player.isOnGround() || mc.player.getPose() != EntityPose.STANDING || mc.player.hasVehicle()) {
            info("Stand still on the ground first.");
            return false;
        }
        if (mc.player.getVelocity().horizontalLengthSquared() > 1.0E-6 || mc.player.input.getMovementInput().lengthSquared() > 1.0E-4f) {
            info("Stand still on the ground first.");
            return false;
        }

        InventoryManager inventory = InventoryManager.get();
        int slot = inventory.find(stack -> stack.isOf(Items.ENDER_PEARL), 0, InventoryManager.MAIN_END);
        if (slot == -1) {
            info("No ender pearls.");
            return false;
        }
        if (mc.player.getItemCooldownManager().isCoolingDown(inventory.stack(slot))) {
            info("Ender pearls are on cooldown.");
            return false;
        }

        Vec3d landing = landing();
        if (landing == null) {
            info("No wall next to you to phase into.");
            return false;
        }

        Vec3d spawn = new Vec3d(mc.player.getX(), mc.player.getEyeY() - SPAWN_DROP, mc.player.getZ());
        double hx = (landing.x - spawn.x) / DRAG;
        double hz = (landing.z - spawn.z) / DRAG;
        double down = Math.sqrt(POWER * POWER - hx * hx - hz * hz);
        double landY = spawn.y + DRAG * (-down - GRAVITY);
        if (landY < mc.player.getY() + FLOOR_GAP) {
            info("Not enough room to throw the pearl.");
            return false;
        }

        float yaw = (float) Math.toDegrees(Math.atan2(-hx, hz));
        float pitch = (float) Math.toDegrees(Math.asin(down / POWER));

        boolean thrown = inventory.withItem(slot, switchMode.get(), () -> {
            float oldYaw = mc.player.getYaw();
            float oldPitch = mc.player.getPitch();
            mc.player.setYaw(yaw);
            mc.player.setPitch(pitch);
            mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
            mc.player.setYaw(oldYaw);
            mc.player.setPitch(oldPitch);
        });
        if (!thrown) {
            info("Could not switch to the pearl while eating.");
            return false;
        }

        thrownAt = Util.getMeasuringTimeMs();
        return true;
    }

    private Vec3d landing() {
        BlockPos feet = BlockPos.ofFloored(mc.player.getX(), mc.player.getY() + 1.0E-3, mc.player.getZ());
        Vec3d best = null;
        int bestWalls = 0;
        double bestDistance = Double.MAX_VALUE;

        for (int sx = -1; sx <= 1; sx++) {
            for (int sz = -1; sz <= 1; sz++) {
                if (sx == 0 && sz == 0) continue;
                if (sx != 0 && sz != 0 && !corners.get()) continue;
                if (sx != 0 && !wall(feet.add(sx, 0, 0))) continue;
                if (sz != 0 && !wall(feet.add(0, 0, sz))) continue;
                if (sx != 0 && sz != 0 && suffocates(feet.add(sx, 1, sz))) continue;

                double x = sx == 0 ? mc.player.getX() : sx > 0 ? feet.getX() + 1 - WALL_GAP : feet.getX() + WALL_GAP;
                double z = sz == 0 ? mc.player.getZ() : sz > 0 ? feet.getZ() + 1 - WALL_GAP : feet.getZ() + WALL_GAP;
                int walls = (sx != 0 ? 1 : 0) + (sz != 0 ? 1 : 0);
                double distance = Math.hypot(x - mc.player.getX(), z - mc.player.getZ());

                if (walls > bestWalls || (walls == bestWalls && distance < bestDistance)) {
                    best = new Vec3d(x, mc.player.getY(), z);
                    bestWalls = walls;
                    bestDistance = distance;
                }
            }
        }

        return best;
    }

    private boolean wall(BlockPos pos) {
        return mc.world.getBlockState(pos).isFullCube(mc.world, pos) && !suffocates(pos.up());
    }

    private boolean suffocates(BlockPos pos) {
        return mc.world.getBlockState(pos).shouldSuffocate(mc.world, pos);
    }
}
