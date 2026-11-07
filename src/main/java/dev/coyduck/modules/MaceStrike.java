package dev.coyduck.modules;

import dev.coyduck.Coyduck;
import dev.coyduck.managers.InteractionManager;
import dev.coyduck.managers.InventoryManager;
import dev.coyduck.managers.RotationManager;
import dev.coyduck.utils.Targets;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.util.Util;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

public class MaceStrike extends Module {
    private static final int PRIORITY = 85;
    private static final float ROTATE_SPEED = 180.0f;
    private static final double MIN_HEIGHT = 2.0;
    private static final double HEIGHT_STEP = 0.5;
    private static final double AIM_INSET = 0.05;

    private enum Phase {
        Idle,
        Lift,
        Drop
    }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Double> range = sgGeneral.add(new DoubleSetting.Builder()
        .name("range")
        .description("Reach from your eyes to the closest point of the target's hitbox.")
        .defaultValue(3.0)
        .range(1, 6)
        .sliderRange(1, 6)
        .build()
    );

    private final Setting<Double> height = sgGeneral.add(new DoubleSetting.Builder()
        .name("height")
        .description("How far up the server is told you went before the hit. The fall back down is what the mace smash counts. Lowered on its own when blocks are above you.")
        .defaultValue(8.0)
        .range(MIN_HEIGHT, 9.5)
        .sliderRange(MIN_HEIGHT, 9.5)
        .build()
    );

    private final Setting<Integer> interval = sgGeneral.add(new IntSetting.Builder()
        .name("interval")
        .description("Milliseconds between smashes.")
        .defaultValue(500)
        .range(100, 3000)
        .sliderRange(100, 2000)
        .build()
    );

    private final Setting<InventoryManager.SwitchMode> switchMode = sgGeneral.add(new EnumSetting.Builder<InventoryManager.SwitchMode>()
        .name("switch")
        .description("How the mace gets into your hand for the hit. Your hand goes back right after.")
        .defaultValue(InventoryManager.SwitchMode.Silent)
        .build()
    );

    private final Setting<Boolean> rotate = sgGeneral.add(new BoolSetting.Builder()
        .name("rotate")
        .description("Faces the target before the smash starts.")
        .defaultValue(true)
        .build()
    );

    private Phase phase = Phase.Idle;
    private PlayerEntity target;
    private double lift;
    private long lastStrike;
    private boolean sending;

    public MaceStrike() {
        super(Coyduck.CATEGORY, "mace-strike", "Smashes players in reach with a mace from your hotbar or inventory. The server is told you went up and fell back down over two ticks, one move packet each, so the hit counts as a fall smash.");
    }

    @Override
    public void onDeactivate() {
        phase = Phase.Idle;
        target = null;
    }

    @Override
    public String getInfoString() {
        return target == null ? null : target.getName().getString();
    }

    @EventHandler(priority = EventPriority.LOW)
    private void onTickPre(TickEvent.Pre event) {
        if (phase != Phase.Idle) return;
        if (!canAct() || maceSlot() == -1) {
            target = null;
            return;
        }

        target = choose();
        if (target == null) return;

        Box box = Targets.box(target);
        if (rotate.get()) {
            RotationManager rotations = RotationManager.get();
            rotations.request(PRIORITY, aim(box), ROTATE_SPEED, true);
            if (!rotations.isLookingAt(box, range.get())) return;
        }
        if (Util.getMeasuringTimeMs() - lastStrike < interval.get()) return;

        lift = headroom();
        if (lift < MIN_HEIGHT) return;
        phase = Phase.Lift;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    private void onSend(PacketEvent.Send event) {
        if (!sending && phase != Phase.Idle && event.packet instanceof PlayerMoveC2SPacket) event.cancel();
    }

    @EventHandler(priority = EventPriority.LOW)
    private void onTickPost(TickEvent.Post event) {
        if (phase == Phase.Idle || mc.player == null || mc.getNetworkHandler() == null) {
            phase = Phase.Idle;
            return;
        }

        double x = mc.player.getX();
        double y = mc.player.getY();
        double z = mc.player.getZ();
        boolean collision = mc.player.horizontalCollision;

        if (phase == Phase.Lift) {
            move(new PlayerMoveC2SPacket.PositionAndOnGround(x, y + lift, z, false, collision));
            phase = Phase.Drop;
            return;
        }

        phase = Phase.Idle;
        int slot = maceSlot();
        boolean strike = slot != -1 && target != null && target.isAlive() && inReach(target);
        move(new PlayerMoveC2SPacket.PositionAndOnGround(x, y, z, !strike && mc.player.isOnGround(), collision));
        if (!strike) return;

        PlayerEntity victim = target;
        InventoryManager.get().withItem(slot, switchMode.get(), () -> {
            mc.interactionManager.attackEntity(mc.player, victim);
            InteractionManager.get().swing(InteractionManager.SwingMode.Packet);
        });
        lastStrike = Util.getMeasuringTimeMs();
    }

    private void move(PlayerMoveC2SPacket packet) {
        sending = true;
        try {
            mc.getNetworkHandler().sendPacket(packet);
        } finally {
            sending = false;
        }
    }

    private boolean canAct() {
        if (mc.player == null || mc.world == null || mc.interactionManager == null || !mc.player.isAlive()) return false;
        if (mc.player.isGliding() || mc.player.hasVehicle() || mc.player.isSpectator()) return false;
        if (mc.player.isTouchingWater() || mc.player.isInLava() || mc.player.isClimbing()) return false;
        return !InventoryManager.get().isEating() && !mc.player.isUsingItem();
    }

    private int maceSlot() {
        return InventoryManager.get().find(stack -> stack.isOf(Items.MACE), 0, InventoryManager.MAIN_END);
    }

    private PlayerEntity choose() {
        PlayerEntity best = null;
        float bestHealth = Float.MAX_VALUE;
        for (PlayerEntity player : mc.world.getPlayers()) {
            if (player == mc.player || !Targets.valid(player, range.get() + 3) || !inReach(player)) continue;
            float health = player.getHealth() + player.getAbsorptionAmount();
            if (health < bestHealth) {
                best = player;
                bestHealth = health;
            }
        }
        return best;
    }

    private boolean inReach(PlayerEntity player) {
        double reach = range.get();
        return Targets.box(player).squaredMagnitude(mc.player.getEyePos()) <= reach * reach;
    }

    private double headroom() {
        Box box = mc.player.getBoundingBox();
        for (double h = height.get(); h >= MIN_HEIGHT; h -= HEIGHT_STEP) {
            if (mc.world.isSpaceEmpty(mc.player, box.stretch(0, h, 0))) return h;
        }
        return 0;
    }

    private Vec3d aim(Box box) {
        Vec3d eye = mc.player.getEyePos();
        if (box.contains(eye)) return box.getCenter();
        Box inner = box.contract(AIM_INSET);
        return new Vec3d(
            MathHelper.clamp(eye.x, inner.minX, inner.maxX),
            MathHelper.clamp(eye.y, inner.minY, inner.maxY),
            MathHelper.clamp(eye.z, inner.minZ, inner.maxZ)
        );
    }
}
